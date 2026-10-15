package net.tfminecraft.rpcharacters.will;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.body.CharacterSelectView;
import net.tfminecraft.rpcharacters.body.SelectPortraits;
import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.paidchange.DenarEconomyWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.will.WillForecast.Guild;
import net.tfminecraft.rpcharacters.will.WillScreens.Button;
import net.tfminecraft.rpcharacters.will.WillScreens.Input;
import net.tfminecraft.rpcharacters.will.WillScreens.Screen;
import net.tfminecraft.rpcharacters.will.WillView.Estate;
import net.tfminecraft.rpcharacters.will.WillView.Row;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

/**
 * {@code /will} and the Character Info button (design W3): the current character's heir with their portrait,
 * what the estate holds today and who would take it, and naming or revoking an heir. The session is captured
 * when the menu opens and re-checked on every click; the heir is never told.
 */
public final class WillMenu {
    static final double NEARBY_BLOCKS = 16;
    static final int PORTRAIT_CACHE = 64;
    static BiConsumer<Player, Screen> screens = WillScreens::show;
    static DenarWallet wallet = new DenarEconomyWallet();
    static CharacterSelectView.PortraitLoader portraits = SelectPortraits::load;
    static BiFunction<ClickCallback<Audience>, ClickCallback.Options, ClickEvent> clickEvents = ClickEvent::callback;
    static Executor mainThread = task -> Bukkit.getScheduler().runTask(RPCharacters.plugin, task);
    static LongSupplier clock = System::currentTimeMillis;

    /** Painted busts by heir character, so a second open shows the portrait at once. Main thread. */
    private static final Map<UUID, Component> PORTRAITS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Component> eldest) {
            return size() > PORTRAIT_CACHE;
        }
    };

    /** Heirs whose bust is loading. Main thread. */
    private static final Set<UUID> LOADING = new HashSet<>();

    /** One open will menu. */
    private static final class Context {
        final Player player;
        final CharacterSession session;
        final PlayerData data;
        final RPCharacter character;

        Context(Player player, CharacterSession session, PlayerData data, RPCharacter character) {
            this.player = player;
            this.session = session;
            this.data = data;
            this.character = character;
        }
    }

    private WillMenu() {}

    public static void open(Player player) {
        PlayerData data = PlayerManager.get(player);
        Optional<CharacterSession> session = Characters.currentSession(player);
        RPCharacter character = data == null || session.isEmpty() ? null
                : data.getCharacterById(session.get().ref().characterId().toString());
        if (character == null) {
            player.sendMessage("Play a character to make its will.");
            return;
        }
        heir(character).ifPresent(WillMenu::prefetch);
        main(new Context(player, session.get(), data, character));
    }

    /** The character's living heir, if one is named. */
    static Optional<CharacterInfo> heir(RPCharacter character) {
        CharacterWill will = character.getWill();
        return will.named() ? WillService.lookup().info(will.heir()).filter(info -> info.status() == CharacterStatus.ALIVE)
                : Optional.empty();
    }

    static void clearPortraits() {
        PORTRAITS.clear();
        LOADING.clear();
    }

    private static void main(Context ctx) {
        CharacterWill will = ctx.character.getWill();
        String name = WillView.plain(ctx.character.getName());
        UUID self = UUID.fromString(ctx.character.getId());
        Optional<CharacterInfo> heir = will.named() ? WillService.lookup().info(will.heir()) : Optional.empty();
        boolean living = heir.map(info -> info.status() == CharacterStatus.ALIVE).orElse(false);
        boolean unreadable = will.named() && WillService.lookup().unreadable(will.heir());
        Guild guild = WillForecast.guild(self);
        Estate estate = WillView.estate(holdings(self), money(ctx));
        List<Component> lines = new ArrayList<>();
        Component portrait = null;
        if (unreadable) {
            lines.add(WillView.lines(List.of(Component.text("⚠ Your heir can't be checked right now", WillView.WARN, TextDecoration.BOLD),
                    Component.text("If " + name + " died now, the estate would wait until the heir can be read.", WillView.TEXT))));
            if (!estate.isEmpty()) lines.add(heirGets("Your estate today", estate, true));
        } else if (living) {
            CharacterInfo info = heir.get();
            portrait = PORTRAITS.getOrDefault(info.ref().characterId(), CharacterSelectView.placeholder(
                    LOADING.contains(info.ref().characterId()) ? CharacterSelectView.LOADING : CharacterSelectView.NO_PORTRAIT));
            lines.add(WillView.lines(List.of(Component.text(info.name(), WillView.HEIR, TextDecoration.BOLD),
                    Component.text(about(ctx, info, will), WillView.MUTED))));
            lines.add(Component.text("— ✦ —", WillView.MUTED));
            lines.add(heirGets(info.name() + " would get", estate, !(guild instanceof Guild.Member) && !(guild instanceof Guild.Unknown)));
            Component elsewhere = elsewhere(ctx, estate, guild);
            if (elsewhere != null) lines.add(elsewhere);
        } else {
            List<Component> top = new ArrayList<>();
            top.add(Component.text(!will.named() ? "⚠ No heir named"
                    : heir.isEmpty() ? "⚠ Your heir can't be found now" : "⚠ Your heir, " + heir.get().name() + ", has died",
                    WillView.WARN, TextDecoration.BOLD));
            top.add(Component.text(estate.isEmpty() ? "Your estate holds nothing recorded today."
                    : "If " + name + " died for good today:", WillView.TEXT));
            lines.add(WillView.lines(top));
            if (!estate.isEmpty()) lines.add(noHeir(ctx, estate, guild));
            lines.add(Component.text(noHeirNote(guild), WillView.MUTED));
        }
        lines.add(link(ctx, Component.text("How wills work", WillView.LINK, TextDecoration.UNDERLINED),
                "The rules, in six lines", () -> rules(ctx)));
        List<Button> buttons = new ArrayList<>();
        buttons.add(button(ctx, will.named() ? "Change heir" : "Name an heir", values -> ask(ctx)));
        if (will.named()) buttons.add(button(ctx, "Revoke", values -> confirm(ctx, null, "Revoke your will? Nobody will be named.")));
        buttons.add(button(ctx, "Close", values -> ctx.player.closeDialog()));
        show(ctx, new Screen("Will of " + name, portrait, lines, null, buttons));
    }

    private static List<EstateHooks.Held> holdings(UUID self) {
        EstateHooks estates = EstateLink.current();
        return estates == null ? List.of() : estates.holdings(self);
    }

    /** Pouch and bank from the ledger, or null to use DenarEconomy's own line. */
    private static String money(Context ctx) {
        try {
            if (!wallet.available()) return null;
            CharacterRef ref = ctx.session.ref();
            return PaidChangeService.formatCents(wallet.balance(ref, DenarWallet.Account.POUCH)) + " carried, "
                    + PaidChangeService.formatCents(wallet.balance(ref, DenarWallet.Account.BANK)) + " banked";
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** "Elf · one of your characters · named 3 days ago". */
    private static String about(Context ctx, CharacterInfo heir, CharacterWill will) {
        List<String> parts = new ArrayList<>();
        Race race = heir.raceId() == null ? null : RaceLoader.getByString(heir.raceId());
        if (race != null) parts.add(WillView.plain(race.getName()));
        if (heir.ref().ownerId().equals(ctx.player.getUniqueId())) parts.add("one of your characters");
        if (will.updatedAtMs() != null) {
            long days = Math.max(0, (clock.getAsLong() - will.updatedAtMs()) / 86_400_000L);
            parts.add(days == 0 ? "named today" : days == 1 ? "named yesterday" : "named " + days + " days ago");
        }
        return String.join(" · ", parts);
    }

    /** {@code guildLocks}: guild and faction locks go with the rest, as they do to an heir without a guild. */
    private static Component heirGets(String heading, Estate estate, boolean guildLocks) {
        List<Component> lines = new ArrayList<>();
        lines.add(WillView.heading(heading));
        if (estate.isEmpty()) lines.add(Component.text("Nothing recorded today.", WillView.MUTED));
        if (estate.money() != null) lines.add(WillView.money(estate.money()));
        for (Row row : estate.rows()) {
            if (guildLocks || !WillView.GUILD_LOCKS.equals(row.key())) lines.add(WillView.row(row));
        }
        return WillView.lines(lines);
    }

    /** With an heir and a guild, guild and faction locks go to the guild's leader, else the realm's. */
    private static Component elsewhere(Context ctx, Estate estate, Guild guild) {
        Row locks = estate.rows().stream().filter(row -> WillView.GUILD_LOCKS.equals(row.key())).findFirst().orElse(null);
        if (locks == null || guild instanceof Guild.None) return null;
        Component to = guild instanceof Guild.Member member ? locksTo(ctx, member)
                : Component.text("your guild's leader, else your realm's", WillView.TEXT);
        return WillView.lines(List.of(WillView.heading("Goes elsewhere"), WillView.row(locks).append(arrow()).append(to)));
    }

    private static Component noHeir(Context ctx, Estate estate, Guild guild) {
        List<Component> lines = new ArrayList<>();
        Guild.Member member = guild instanceof Guild.Member m ? m : null;
        if (estate.money() != null) {
            Component money = WillView.money(estate.money());
            lines.add(member == null ? money
                    : money.append(arrow()).append(Component.text(member.name() + "'s treasury", WillView.TEXT)));
        }
        for (Row row : estate.rows()) {
            Component line = WillView.row(row);
            if (WillView.LETTERS.equals(row.key())) line = line.append(arrow()).append(Component.text("back to their writers", WillView.TEXT));
            else if (member != null) line = line.append(arrow()).append(WillView.GUILD_LOCKS.equals(row.key())
                    ? locksTo(ctx, member) : propertyTo(ctx, member));
            lines.add(line);
        }
        return WillView.lines(lines);
    }

    private static String noHeirNote(Guild guild) {
        String alts = "Your other characters get nothing unless you name one.";
        if (guild instanceof Guild.None) return "You're in no guild, so it would wait 7 days in case of a revive, then staff would hold it.";
        if (!(guild instanceof Guild.Member member)) {
            return "Money goes to your guild and property to its leader. Without a guild, staff hold it after 7 days.";
        }
        if (member.youLead()) {
            return "You lead " + member.name() + ", so its next leader would take it; with nobody to take over, it waits until one is appointed. " + alts;
        }
        return member.leader().map(leader -> leader.name() + " leads " + member.name() + ". " + alts)
                .orElse(member.name() + " has no leader, so property waits until one is appointed. " + alts);
    }

    /** Property waits while the guild has no living leader, so a successor is never promised. */
    private static Component propertyTo(Context ctx, Guild.Member member) {
        if (member.youLead()) return Component.text(member.name() + "'s next leader (waits if none)", WillView.TEXT);
        return member.leader().map(leader -> WillView.named(leader, ctx.data, WillView.TEXT))
                .orElse(Component.text("waits for " + member.name() + "'s next leader", WillView.TEXT));
    }

    /** The guild's leader, else the realm's; a leader's successor is not known until the death. */
    private static Component locksTo(Context ctx, Guild.Member member) {
        if (member.youLead()) {
            return member.realmLeader().map(realm -> Component.text(member.name() + "'s next leader, else ", WillView.TEXT)
                    .append(WillView.named(realm, ctx.data, WillView.TEXT)))
                    .orElse(Component.text(member.name() + "'s next leader (waits if none)", WillView.TEXT));
        }
        Optional<CharacterInfo> to = member.leader().or(member::realmLeader);
        return to.map(info -> WillView.named(info, ctx.data, WillView.TEXT))
                .orElse(Component.text("waits for " + member.name() + "'s next leader", WillView.TEXT));
    }

    private static Component arrow() {
        return Component.text(" → ", WillView.MUTED);
    }

    /**
     * Loads the heir's bust into the cache. It is only ever cached, never shown when it arrives, so a late portrait
     * cannot appear over another screen; it is fetched when a character starts playing and when an heir is named,
     * so it is usually ready when /will opens.
     */
    static void prefetch(CharacterInfo heir) {
        UUID id = heir.ref().characterId();
        if (PORTRAITS.containsKey(id) || !LOADING.add(id)) return;
        CompletableFuture<BufferedImage> future;
        try {
            Player owner = WillView.online.apply(heir.ref().ownerId());
            future = portraits.load(heir.ref().ownerId(), id.toString(),
                    owner == null ? null : CharacterSelectView.accountSkin(owner));
        } catch (RuntimeException | LinkageError unavailable) {
            future = null;
        }
        if (future == null) future = CompletableFuture.completedFuture(null);
        future.whenComplete((front, error) -> dispatch(() -> {
            BufferedImage image = error == null ? front : null;
            LOADING.remove(id);
            PORTRAITS.put(id, image == null ? CharacterSelectView.placeholder(CharacterSelectView.NO_PORTRAIT)
                    : CharacterSelectView.bust(image));
        }));
    }

    private static void dispatch(Runnable task) {
        try {
            mainThread.execute(task);
        } catch (RuntimeException disabled) {
            // The plugin is shutting down; the menu goes with it.
        }
    }

    private static void rules(Context ctx) {
        List<String> rules = List.of(
                "Name one living character as your heir. Your own characters count. Nobody is told.",
                "Your will is fixed the moment your character dies for good.",
                "Everything passes 24 hours later. A staff revive before then keeps it all.",
                "Your heir collects stored items with /estate collect.",
                "No living heir: money goes to your guild and property to its leader. No guild: it waits 7 days, then staff hold it.",
                "Leading a guild or realm never passes by will.");
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            lines.add(Component.text((i + 1) + " ", WillView.WARN).append(Component.text(rules.get(i), WillView.TEXT)));
        }
        lines.add(Component.text("You can also change it on your Profile at tfminecraft.net.", WillView.MUTED));
        show(ctx, new Screen("How wills work", null, lines, null, List.of(button(ctx, "Back", values -> main(ctx)))));
    }

    private static void ask(Context ctx) {
        List<Component> lines = new ArrayList<>();
        List<CharacterInfo> own = own(ctx);
        if (!own.isEmpty()) lines.add(picks(ctx, "Your characters", own, info -> race(info)));
        List<Map.Entry<CharacterInfo, Integer>> near = nearby(ctx);
        if (!near.isEmpty()) {
            Map<CharacterInfo, Integer> blocks = new LinkedHashMap<>();
            near.forEach(entry -> blocks.put(entry.getKey(), entry.getValue()));
            lines.add(picks(ctx, "Nearby", List.copyOf(blocks.keySet()), info -> blocks.get(info) + " blocks"));
        }
        lines.add(WillView.heading("Anyone else"));
        List<Button> buttons = List.of(button(ctx, "Find", values -> find(ctx, values.apply("heir"))),
                button(ctx, "Back", values -> main(ctx)));
        show(ctx, new Screen("Name an heir", null, lines, new Input("heir", "Character name", List.of()), buttons));
    }

    private static Component picks(Context ctx, String heading, List<CharacterInfo> infos,
            java.util.function.Function<CharacterInfo, String> detail) {
        List<Component> lines = new ArrayList<>();
        lines.add(WillView.heading(heading));
        for (CharacterInfo info : infos) {
            Component name = link(ctx, Component.text(info.name(), WillView.TEXT, TextDecoration.UNDERLINED),
                    "Name " + info.name() + " as your heir", () -> chosen(ctx, info));
            String more = detail.apply(info);
            lines.add(WillView.face(info, ctx.data).append(name)
                    .append(more == null || more.isEmpty() ? Component.empty() : Component.text(" " + more, WillView.MUTED)));
        }
        return WillView.lines(lines);
    }

    /** This account's other living characters. */
    private static List<CharacterInfo> own(Context ctx) {
        List<CharacterInfo> found = new ArrayList<>();
        for (RPCharacter other : ctx.data.getCharacters()) {
            if (other.getId().equalsIgnoreCase(ctx.character.getId())) continue;
            UUID id;
            try {
                id = UUID.fromString(other.getId());
            } catch (IllegalArgumentException notACharacter) {
                continue;
            }
            Characters.info(id).filter(info -> info.status() == CharacterStatus.ALIVE).ifPresent(found::add);
        }
        return found;
    }

    private static void find(Context ctx, String name) {
        List<CharacterInfo> found = new ArrayList<>();
        String wanted = name == null ? "" : name.trim();
        if (!wanted.isEmpty()) {
            for (CharacterInfo info : Characters.findByName(wanted)) {
                if (!info.ref().characterId().toString().equalsIgnoreCase(ctx.character.getId())) found.add(info);
            }
        }
        if (found.isEmpty()) {
            show(ctx, new Screen("No match", null, List.of(Component.text("No other living character is called \"" + wanted + "\".",
                    WillView.TEXT)), null, List.of(button(ctx, "Back", values -> ask(ctx)))));
        } else if (found.size() == 1) {
            chosen(ctx, found.getFirst());
        } else {
            List<String[]> options = new ArrayList<>();
            for (CharacterInfo info : found) options.add(new String[] {info.ref().characterId().toString(), describe(ctx, info)});
            show(ctx, new Screen("Several characters", null, List.of(Component.text("Several living characters share that name.",
                    WillView.TEXT)), new Input("pick", "Character", options), List.of(
                            button(ctx, "Choose", values -> found.stream()
                                    .filter(info -> info.ref().characterId().toString().equals(values.apply("pick")))
                                    .findFirst().ifPresent(info -> chosen(ctx, info))),
                            button(ctx, "Back", values -> ask(ctx)))));
        }
    }

    private static void chosen(Context ctx, CharacterInfo heir) {
        confirm(ctx, heir.ref().characterId(), "Name " + describe(ctx, heir) + " as your heir?");
    }

    private static void confirm(Context ctx, UUID heir, String question) {
        show(ctx, new Screen("Your will", null, List.of(Component.text(question, WillView.TEXT)), null, List.of(
                button(ctx, "Confirm", values -> apply(ctx, heir)), button(ctx, "Back", values -> main(ctx)))));
    }

    private static void apply(Context ctx, UUID heir) {
        WillService.Change change = WillService.change(ctx.data, ctx.character, ctx.player.getUniqueId(), heir, null);
        ctx.player.closeDialog();
        if (change.applied()) {
            ctx.player.sendMessage(heir == null ? "Your will is revoked." : "Your heir is named.");
            heir(ctx.character).ifPresent(WillMenu::prefetch);
            return;
        }
        ctx.player.sendMessage(switch (change.verdict()) {
            case OK -> "Your will could not be saved. Try again or contact staff.";
            case HEIR_UNREADABLE -> "That character can't be checked right now. Try again later.";
            default -> "That character can't be your heir.";
        });
    }

    private static void show(Context ctx, Screen screen) {
        screens.accept(ctx.player, screen);
    }

    private static boolean current(Context ctx) {
        if (Characters.isCurrent(ctx.session)) return true;
        ctx.player.closeDialog();
        ctx.player.sendMessage("Your character changed. Open /will again.");
        return false;
    }

    private static Button button(Context ctx, String label, java.util.function.Consumer<java.util.function.Function<String, String>> action) {
        return new Button(label, values -> {
            if (current(ctx)) action.accept(values);
        });
    }

    /** Body text that acts like a button: once, for this player, while the character is current. */
    private static Component link(Context ctx, Component label, String hover, Runnable action) {
        return label.hoverEvent(HoverEvent.showText(Component.text(hover))).clickEvent(clickEvents.apply(audience -> {
            if (audience == ctx.player && current(ctx)) action.run();
        }, WillScreens.ONCE));
    }

    /** Characters played by players within 16 blocks, excluding this one, with their distance. */
    private static List<Map.Entry<CharacterInfo, Integer>> nearby(Context ctx) {
        List<Map.Entry<CharacterInfo, Integer>> found = new ArrayList<>();
        Player self = ctx.player;
        for (Player other : self.getWorld().getPlayers()) {
            if (other == self) continue;
            double distance = other.getLocation().distance(self.getLocation());
            if (distance > NEARBY_BLOCKS) continue;
            Characters.currentSession(other).flatMap(session -> Characters.info(session.ref().characterId()))
                    .filter(CharacterInfo::alive)
                    .ifPresent(info -> found.add(Map.entry(info, (int) Math.round(distance))));
        }
        return found;
    }

    private static String race(CharacterInfo info) {
        Race race = info.raceId() == null ? null : RaceLoader.getByString(info.raceId());
        return race == null ? info.raceId() : WillView.plain(race.getName());
    }

    private static String describe(Context ctx, CharacterInfo info) {
        return info.name() + " (" + race(info) + ", " + info.ref().characterId().toString().substring(0, 8)
                + (info.ref().ownerId().equals(ctx.player.getUniqueId()) ? ", on your account)" : ")");
    }
}
