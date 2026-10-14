package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.body.BodyStore;
import net.tfminecraft.rpcharacters.body.CharacterBodies;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterRevivedEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;

/** Wires estates into the running plugin: services, listeners, the command and the two clocks. */
final class EstateModule implements EstateHooks, Listener {
    static final long MINUTE_TICKS = 20L * 60L;
    static final long HOUR_TICKS = MINUTE_TICKS * 60L;
    static String readyEvent = "net.tfminecraft.simplefactions.events.SimpleFactionsReadyEvent";

    private final RPCharacters plugin;
    private final EstateCoordinator coordinator;
    private final EstateCollections collections;
    private final BodyEstate body;
    private final List<BukkitTask> tasks = new ArrayList<>();

    EstateModule(RPCharacters plugin, EstateCoordinator coordinator, EstateCollections collections, BodyEstate body) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        this.collections = collections;
        this.body = body;
    }

    /** Builds estates on the live server, then runs boot reconcile, due decisions and WAITING re-checks. */
    static EstateModule start(RPCharacters plugin) throws IOException {
        Path data = plugin.getDataFolder().toPath().resolve("data");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                plugin.getDataFolder().toPath().resolve("config.yml").toFile());
        double hours = config.getDouble("estates.wait-hours", 24);
        double guildlessHours = Math.max(hours, config.getDouble("estates.guildless-wait-hours", 168));
        Clock clock = Clock.systemUTC();
        EstateBook book = new EstateBook(data.resolve("estates"), DurableFiles.DISK, clock, plugin.getLogger());
        EstateCoordinator coordinator = new EstateCoordinator(book,
                new ServerWorld(RPCharacters::getCharacterIdentity, data.resolve("characterdata")), clock,
                Duration.ofMillis((long) (hours * 3_600_000L)), Duration.ofMillis((long) (guildlessHours * 3_600_000L)),
                plugin.getLogger(), RosterSyncService::pushRosterAsync,
                Bukkit.getPluginManager()::callEvent);
        CharacterBodies bodies = RPCharacters.getCharacterBodies();
        BodyEstate body = bodies == null ? null : new BodyEstate(book, new BodyEstate.Bodies() {
            @Override
            public BodyStore store() {
                return bodies.store();
            }

            @Override
            public boolean hasLease(UUID owner, UUID character) throws IOException {
                return bodies.hasLease(owner, character);
            }
        }, Estates::guardHolds, net.tfminecraft.rpcharacters.grave.GraveLoader::keepOutOfGrave, plugin.getLogger());
        coordinator.bodies(body);
        EstateCollections collections = new EstateCollections(book, ItemStack::deserializeBytes,
                RosterSyncService::pushRosterAsync, plugin.getLogger());
        EstateModule module = new EstateModule(plugin, coordinator, collections, body);
        book.load();
        try {
            module.enable();
        } catch (RuntimeException | LinkageError error) {
            module.stop();
            throw error;
        }
        return module;
    }

    void enable() {
        var services = Bukkit.getServicesManager();
        services.register(EstateService.class, coordinator, plugin, ServicePriority.Normal);
        if (body != null) services.register(EstateParticipant.class, body, plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listenForSimpleFactions();
        EstateCommand command = new EstateCommand(new EstateStaff(coordinator, plugin.getLogger()), collections,
                player -> Characters.currentSession(player).map(session -> session.ref().characterId()));
        plugin.getCommand("estate").setExecutor(command);
        plugin.getCommand("estate").setTabCompleter(command);
        var scheduler = Bukkit.getScheduler();
        tasks.add(scheduler.runTaskTimer(plugin, coordinator::decideDue, MINUTE_TICKS, MINUTE_TICKS));
        tasks.add(scheduler.runTaskTimer(plugin, coordinator::hourly, HOUR_TICKS, HOUR_TICKS));
        coordinator.reconcile();
        coordinator.closeRevived();
        afterSimpleFactions();
    }

    /** Decisions postponed for want of SimpleFactions run again once it is up (design N2, P2). */
    void afterSimpleFactions() {
        coordinator.decideDue();
        coordinator.recheckWaiting();
    }

    private void listenForSimpleFactions() {
        try {
            Class<? extends Event> ready = Class.forName(readyEvent).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(ready, this, EventPriority.MONITOR,
                    (listener, event) -> afterSimpleFactions(), plugin);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException absent) {
            plugin.getLogger().info("SimpleFactions' ready event is not available; estates re-check when it enables.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("SimpleFactions")) afterSimpleFactions();
    }

    /** "You have an inheritance to collect", clickable, at the start of the recipient's session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSessionStart(CharacterSessionStartEvent event) {
        int waiting = collections.waitingFor(event.getSession().ref().characterId()).size();
        if (waiting == 0) return;
        event.getPlayer().sendMessage(Component.text("You have an inheritance to collect. ", NamedTextColor.GOLD)
                .append(Component.text("[Collect]", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/estate collect"))));
    }

    @Override
    public void died(UUID owner, RPCharacter character, boolean wasActive, String cause, UUID killerAccount,
            CharacterRef killer) {
        try {
            CharacterRef ref = new CharacterRef(owner, UUID.fromString(character.getId()));
            coordinator.died(ref, ClueFormatter.stripColor(character.getName()).trim(), character.getDeathId(),
                    character.getDiedAt(), character.getWill().heir(), character.getWill().revision(), wasActive, cause,
                    killerAccount, killer);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Estate for " + character.getId() + " was not opened; reconcile will", error);
        }
    }

    @Override
    public List<String> reviveBlockers(UUID deathId) {
        return coordinator.reviveBlockers(deathId);
    }

    @Override
    public void revived(CharacterRef character, UUID previousDeathId, boolean forced, String by) {
        coordinator.revived(previousDeathId, forced, by);
        Bukkit.getPluginManager().callEvent(new CharacterRevivedEvent(character, previousDeathId));
    }

    @Override
    public void appendRoster(JSONObject row, UUID deathId) {
        coordinator.book().get(deathId).ifPresent(record -> putEstate(row, record));
    }

    @SuppressWarnings("unchecked")
    private static void putEstate(JSONObject row, EstateRecord record) {
        row.put("estate", EstateRoster.estate(record));
    }

    @Override
    public List<String> holdings(UUID characterId) {
        List<String> lines = new ArrayList<>();
        for (EstateParticipant participant : coordinator.world().participants()) {
            try {
                Holdings held = participant.describe(characterId);
                if (!held.labels().isEmpty()) lines.addAll(held.labels());
                else for (Map.Entry<String, Integer> count : held.counts().entrySet()) {
                    if (count.getValue() > 0) lines.add(count.getValue() + " " + count.getKey());
                }
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().log(Level.WARNING, "Estate participant " + participant.id() + " could not describe", error);
            }
        }
        return lines;
    }

    @Override
    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        HandlerList.unregisterAll(this);
        Bukkit.getServicesManager().unregister(EstateService.class, coordinator);
        if (body != null) Bukkit.getServicesManager().unregister(EstateParticipant.class, body);
    }
}
