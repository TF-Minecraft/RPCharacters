package net.tfminecraft.rpcharacters.roll;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.RollLoader;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class RollManagerTest {
    @TempDir Path directory;
    ServerMock server;
    RuntimeTestState state;
    PlayerMock player;
    Plugin plugin;
    Command command;
    RollManager manager;
    MockedStatic<DisplayIdentityService> identity;
    MockedStatic<net.Indyuce.mmocore.api.player.PlayerData> mmoPlayers;
    net.Indyuce.mmocore.api.player.PlayerData mmo;
    PlayerAttributes attributes;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RollLoader.class);
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer("Dice");
        command = mock(Command.class);
        manager = new RollManager();
        new RollLoader().load(Files.writeString(directory.resolve("roll.yml"), """
            attribute-modifiers:
              intelligence: {'0': -4, '10': 3, '20': 6}
            """).toFile());
        Cache.rollPermission = "test.roll";
        Cache.rollAltPermission = "test.roll.alt";
        Cache.rollDefaultMin = Cache.rollDefaultMax = 6;
        Cache.rollAltMin = Cache.rollAltMax = 9;
        Cache.rollD20Min = Cache.rollD20Max = 20;
        Cache.rollBroadcastText = "{player}|{display}|{roll}|{max}|{modifier}";
        Cache.rollBroadcastRange = 10;
        Cache.attributes = new ArrayList<>(List.of("strength"));
        grant(Cache.rollPermission, true);
        grant(Cache.rollAltPermission, false);
        identity = mockStatic(DisplayIdentityService.class);
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn("Hero");
        mmoPlayers = mockStatic(net.Indyuce.mmocore.api.player.PlayerData.class);
        mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
        attributes = new PlayerAttributes(mmo);
        when(mmo.getAttributes()).thenReturn(attributes);
        mmoPlayers.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        attributes.getInstance("intelligence").setBase(10);
    }

    @AfterEach void cleanup() {
        mmoPlayers.close(); identity.close(); state.close(); MockBukkit.unmock();
    }

    void grant(String permission, boolean enabled) { player.addAttachment(plugin, permission, enabled); }
    void roll(String... args) { assertTrue(manager.onCommand(player, command, "roll", args)); }
    String message() { return Objects.requireNonNull(player.nextMessage()); }

    @Test void consoleAndPermissionGuardsDoNotRoll() {
        assertTrue(manager.onCommand(server.getConsoleSender(), command, "roll", new String[0]));
        assertTrue(server.getConsoleSender().nextMessage().contains("Only players"));
        grant(Cache.rollPermission, false); roll();
        assertTrue(message().contains("permission")); assertNull(player.nextMessage());
    }

    @Test void defaultAndAlternatePermissionChooseTheirConfiguredRanges() {
        roll(); assertEquals("Dice|Hero|6|6|", message());
        grant(Cache.rollAltPermission, true); roll(); assertEquals("Dice|Hero|9|9|", message());
    }

    @Test void numericRollsAcceptPositiveNegativeZeroAndTrimmedModifiers() {
        roll("1"); assertEquals("Dice|Hero|1|1|", message());
        roll("1", "+2"); assertEquals("Dice|Hero|1|1| +2", message());
        roll("1", " -3 "); assertEquals("Dice|Hero|1|1| -3", message());
        roll("1", "0"); assertEquals("Dice|Hero|1|1|", message());
        roll("1", Integer.toString(Integer.MIN_VALUE));
        assertEquals("Dice|Hero|1|1| -2147483648", message());
    }

    @Test void invalidMaximumAndModifiersProduceUsefulErrors() {
        for (String bad : Arrays.asList("bad", "0", "-2", "2147483648", null)) {
            roll(bad); assertTrue(message().contains("Usage"));
            assertTrue(message().contains("Invalid roll maximum")); assertNull(player.nextMessage());
        }
        Cache.attributes = null;
        roll("missing"); assertTrue(message().contains("Usage")); message();
        for (String bad : Arrays.asList("bad", " ", "+bad", "2147483648", null)) {
            roll("1", bad); assertTrue(message().contains("Invalid modifier"));
            assertNull(player.nextMessage());
        }
    }

    @Test void doublySignedModifiersAreRejected() {
        roll("1", "+-2");
        assertTrue(message().contains("Invalid modifier"), "A double sign must not silently become a negative modifier");
        roll("1", "++2"); assertTrue(message().contains("Invalid modifier"));
    }

    @Test void maximumIntegerRangeRollsWithoutOverflow() {
        assertDoesNotThrow(() -> roll(Integer.toString(Integer.MAX_VALUE)));
        String[] output = message().split("\\|", -1);
        int value = Integer.parseInt(output[2]);
        assertTrue(value >= 1 && value <= Integer.MAX_VALUE);
        assertEquals(Integer.toString(Integer.MAX_VALUE), output[3]);
        assertDoesNotThrow(() -> RollManager.executeRoll(player, Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
        assertEquals("Dice|Hero|2147483647|2147483647|", message());
    }

    @Test void reversedAndNonPositiveRangesAreHandledBeforeBroadcast() {
        RollManager.executeRoll(null, 1, 2, 0);
        RollManager.executeRoll(player, -10, 0, 0);
        assertTrue(message().contains("Invalid roll range"));
        RollManager.executeRoll(player, 1, -10, 0); assertEquals("Dice|Hero|1|1|", message());
        RollManager.executeRoll(player, 3, 1, 0);
        int rolled = Integer.parseInt(message().split("\\|")[2]); assertTrue(rolled >= 1 && rolled <= 3);
        Cache.rollBroadcastText = ""; RollManager.executeRoll(player, 1, 1, 0);
        assertNull(player.nextMessage());
    }

    @Test void localBroadcastIncludesTheBoundaryButNotOtherWorlds() {
        var world = player.getWorld();
        player.teleport(new Location(world, 0, 64, 0));
        var edge = server.addPlayer("Edge"); edge.teleport(new Location(world, 10, 64, 0));
        var far = server.addPlayer("Far"); far.teleport(new Location(world, 11, 64, 0));
        var remote = server.addPlayer("Remote"); remote.teleport(new Location(server.addSimpleWorld("elsewhere"), 0, 64, 0));
        roll("1"); String output = message();
        assertEquals(output, edge.nextMessage()); assertNull(far.nextMessage()); assertNull(remote.nextMessage());
        assertNull(player.nextMessage());
        Cache.rollBroadcastRange = 0; roll("1"); output = message();
        assertEquals(output, edge.nextMessage()); assertEquals(output, far.nextMessage()); assertEquals(output, remote.nextMessage());
        assertNull(player.nextMessage());
    }

    @Test void attributesUseConfiguredD20AndBaseValueModifiers() {
        roll("intelligence"); assertEquals("Dice|Hero|20|20| +3", message());
        roll("strength"); assertEquals("Dice|Hero|20|20|", message());
        assertEquals(0, AttributeRollResolver.resolveModifier(null, "intelligence"));
        assertEquals(0, AttributeRollResolver.resolveModifier(player, null));
        assertEquals(0, AttributeRollResolver.resolveModifier(player, " "));
        mmoPlayers.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(null);
        assertEquals(0, AttributeRollResolver.resolveModifier(player, "intelligence"));
    }

    @Test void acceptedUppercaseAttributeUsesTheSameRealMmoAllocation() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        roll("INTELLIGENCE"); assertEquals("Dice|Hero|20|20| +3", message());
        assertEquals(1, attributes.getInstances().size(), "Case variants must not create an empty parallel allocation");
    }

    @Test void tabCompletionDeduplicatesConfiguredAttributesAndUsesRootLocale() {
        Cache.attributes = new ArrayList<>(Arrays.asList(null, " ", "INTELLIGENCE", "strength", "strength"));
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals(List.of("intelligence"), manager.onTabComplete(player, command, "roll", new String[]{"IN"}));
        assertEquals(List.of("intelligence", "strength"), manager.onTabComplete(player, command, "roll", new String[]{""}));
        assertEquals(List.of(), manager.onTabComplete(player, command, "roll", new String[]{"z"}));
        assertEquals(List.of(), manager.onTabComplete(player, command, "roll", new String[0]));
        Cache.attributes = null;
        assertEquals(List.of("intelligence"), manager.onTabComplete(player, command, "roll", new String[]{""}));
    }

    @Test void formatterExpandsTokensAndPreservesSupportedColors() {
        assertEquals("", RollFormatter.format(null, 3, 20, 0));
        Cache.rollBroadcastText = null; assertEquals("", RollFormatter.format(player, 3, 20, 0));
        Cache.rollBroadcastText = ""; assertEquals("", RollFormatter.format(player, 3, 20, 0));
        Cache.rollBroadcastText = "&a{display} {player} #abcdef{roll}/{max}{modifier}";
        assertEquals("§aHero Dice §x§a§b§c§d§e§f3/20 -2", RollFormatter.format(player, 3, 20, -2));
    }
}
