package net.tfminecraft.rpcharacters.plots;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.MockedStatic;

/** A MockBukkit server with characters, Intrigue, factions and battles as test doubles, on a real plot store. */
final class PlotWorld implements AutoCloseable {
    final ServerMock server = MockBukkit.mock();
    final RuntimeTestState state = new RuntimeTestState(RPCharacters.class, PlotService.class, PlotHunt.class, PlotLog.class,
            PlotDefense.class, Intrigue.class);
    final RPCharacters plugin = mock(RPCharacters.class);
    final World world = spy(new WorldMock());
    final World otherWorld = spy(new WorldMock());
    final MockedStatic<PlotCharacters> characters = mockStatic(PlotCharacters.class);
    final MockedStatic<Intrigue> intrigues = mockStatic(Intrigue.class);
    final MockedStatic<PlotFactions> factions = mockStatic(PlotFactions.class);
    final MockedStatic<PermadeathBattleExemption> battles = mockStatic(PermadeathBattleExemption.class);
    final Map<UUID, PlotCharacter> active = new HashMap<>();
    final Map<UUID, PlayerMock> players = new HashMap<>();
    final Map<String, Integer> intrigue = new HashMap<>();
    final Map<String, PlotCharacter> offline = new HashMap<>();
    final Map<String, PlotFactions.Spymaster> spymasters = new HashMap<>();
    final Set<String> dead = new HashSet<>();
    final Set<UUID> absent = new HashSet<>();
    final List<String> permakilled = new ArrayList<>();
    final List<String> clues = new ArrayList<>();
    final Map<PlayerMock, List<String>> messages = new HashMap<>();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final ScriptedRandom random = new ScriptedRandom();
    final Path directory;

    PlotWorld(Path directory) throws Exception {
        this.directory = directory;
        server.addWorld((WorldMock) world);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PlotWorld"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("RPCharacters");
        RPCharacters.plugin = plugin;
        doReturn(0).when(world).getHighestBlockYAt(anyInt(), anyInt());
        doReturn(0).when(otherWorld).getHighestBlockYAt(anyInt(), anyInt());
        PlotConfig.load(new YamlConfiguration());
        PlotService.clock = clock::get;
        PlotService.random = () -> random;
        PlotService.load(directory.resolve("plots.json"));
        PlotLog.start(directory.resolve("logs"));
        characters.when(() -> PlotCharacters.active(any(Player.class))).thenAnswer(call -> {
            Player player = call.getArgument(0);
            return player == null ? null : active.get(player.getUniqueId());
        });
        characters.when(() -> PlotCharacters.online(any())).thenAnswer(call -> {
            PlotCharacter character = call.getArgument(0);
            PlotCharacter playing = character == null ? null : active.get(character.playerId);
            return playing != null && playing.is(character.characterId) ? players.get(character.playerId) : null;
        });
        characters.when(() -> PlotCharacters.dead(any())).thenAnswer(call -> {
            PlotCharacter character = call.getArgument(0);
            return character != null && dead.contains(character.characterId);
        });
        characters.when(() -> PlotCharacters.active(any(UUID.class), anyLong())).thenAnswer(call -> !absent.contains(call.<UUID>getArgument(0)));
        characters.when(() -> PlotCharacters.offline(any())).thenAnswer(call -> {
            OfflinePlayer account = call.getArgument(0);
            return account == null ? null : offline.get(account.getName());
        });
        characters.when(() -> PlotCharacters.seenAs(any(), any())).thenAnswer(call -> ((PlotCharacter) call.getArgument(1)).name);
        characters.when(() -> PlotCharacters.permakill(any(), anyString(), any())).thenAnswer(call -> {
            permakilled.add(call.getArgument(1));
            return true;
        });
        characters.when(() -> PlotCharacters.leaveClues(any(), any(), anyInt())).thenAnswer(call -> {
            for (int index = 0; index < call.<Integer>getArgument(2); index++) clues.add(((Player) call.getArgument(1)).getName());
            return call.<Integer>getArgument(2);
        });
        intrigues.when(() -> Intrigue.known(any())).thenAnswer(call -> intrigue.get((String) call.getArgument(0)));
        intrigues.when(() -> Intrigue.of(any(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(1);
            intrigue.putIfAbsent(id, 40);
            return intrigue.get(id);
        });
        factions.when(() -> PlotFactions.spymaster(any())).thenAnswer(call -> spymasters.get((String) call.getArgument(0)));
    }

    /** An online player at the origin of the shared world, playing the character with this id and Intrigue. */
    PlayerMock player(String name, String characterId, Integer intrigueValue) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(new Location(world, 0, 64, 0));
        messages.put(player, new ArrayList<>());
        players.put(player.getUniqueId(), player);
        if (characterId != null) active.put(player.getUniqueId(), new PlotCharacter(characterId, player.getUniqueId(), name, "Char " + name));
        if (intrigueValue != null) intrigue.put(characterId, intrigueValue);
        return player;
    }

    PlotCharacter character(Player player) { return active.get(player.getUniqueId()); }

    void at(PlayerMock player, double x) { player.teleport(new Location(world, x, 64, 0)); }

    void at(PlayerMock player, double x, double z) { player.teleport(new Location(world, x, 64, z)); }

    void elsewhere(PlayerMock player) { player.teleport(new Location(otherWorld, 0, 64, 0)); }

    void logOff(Player player) { active.remove(player.getUniqueId()); }

    void play(Player player, String characterId) {
        active.put(player.getUniqueId(), new PlotCharacter(characterId, player.getUniqueId(), player.getName(), "Char " + player.getName()));
    }

    /** Everything the player has been sent so far, as legacy text. */
    List<String> said(PlayerMock player) {
        List<String> log = messages.get(player);
        Component next;
        while ((next = player.nextComponentMessage()) != null) log.add(LegacyComponentSerializer.legacySection().serialize(next));
        return log;
    }

    String last(PlayerMock player) {
        List<String> log = said(player);
        return log.isEmpty() ? null : log.getLast();
    }

    boolean heard(PlayerMock player, String fragment) {
        return said(player).stream().anyMatch(line -> line.contains(fragment));
    }

    void pass(long millis) { clock.addAndGet(millis); }

    PlotStore store() { return PlotService.store(); }

    @Override
    public void close() {
        PlotConfig.load(new YamlConfiguration());
        battles.close();
        factions.close();
        intrigues.close();
        characters.close();
        state.close();
        MockBukkit.unmock();
    }
}
