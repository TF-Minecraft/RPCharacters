package net.tfminecraft.rpcharacters.wipe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.*;

class WipeCommandTest {
    ServerMock server;
    PlayerMock player;
    RuntimeTestState state;
    MockedStatic<GatewayClient> gateway;
    MockedStatic<ProvinceSystemClient> api;
    MockedStatic<Bukkit> bukkit;
    AtomicLong now;
    final List<Runnable> background = new ArrayList<>(), foreground = new ArrayList<>();

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class, WipeCommand.class);
        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("wipe-test"));
        player = server.addPlayer();
        player.setOp(true);
        now = new AtomicLong(100_000L);
        gateway = mockStatic(GatewayClient.class);
        gateway.when(GatewayClient::realmId).thenReturn("dev");
        api = mockStatic(ProvinceSystemClient.class);
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(RPCharacters.plugin), any(Runnable.class)))
                .thenAnswer(call -> { background.add(call.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTask(eq(RPCharacters.plugin), any(Runnable.class)))
                .thenAnswer(call -> { foreground.add(call.getArgument(1)); return mock(BukkitTask.class); });
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach void cleanup() { bukkit.close(); api.close(); gateway.close(); state.close(); MockBukkit.unmock(); }
    void call(String... args) { assertTrue(WipeCommand.handle(player, args, now::get)); }
    String messages() { StringBuilder text = new StringBuilder(); String message; while ((message = player.nextMessage()) != null) text.append(message).append('\n'); return text.toString(); }
    void arm() { call("wipe", "website"); }
    void confirm() { call("wipe", "website", "confirm"); }
    void finish() { background.removeFirst().run(); foreground.removeFirst().run(); }

    @Test void permissionSyntaxAndUnarmedConfirmationNeverCallTheApi() {
        player.setOp(false); arm(); assertTrue(messages().contains("permission"));
        player.setOp(true);
        call("wipe"); call("wipe", "other"); call("wipe", "website", "wrong");
        call("wipe", "website", "confirm", "extra");
        assertTrue(messages().contains("Usage"));
        confirm(); assertTrue(messages().contains("Nothing to confirm"));
        assertTrue(background.isEmpty()); api.verifyNoInteractions();
    }

    @Test void unavailableRealmCannotArmOrExecuteADeletion() {
        gateway.when(GatewayClient::realmId).thenReturn(null); arm();
        assertTrue(messages().contains("aborted")); confirm();
        assertTrue(messages().contains("Nothing to confirm"));
        gateway.when(GatewayClient::realmId).thenReturn("dev"); arm();
        gateway.when(GatewayClient::realmId).thenReturn(null); confirm();
        assertTrue(messages().contains("aborted"));
        assertTrue(background.isEmpty()); api.verifyNoInteractions();
    }

    @Test void successRunsOffThreadThenReportsCountsAndConsumesConfirmation() {
        api.when(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"))
                .thenReturn(ProvinceSystemClient.RealmWipeResult.success("dev", 7, 3));
        arm(); assertTrue(org.bukkit.ChatColor.stripColor(messages()).contains("realm dev"));
        confirm(); assertEquals(1, background.size()); api.verifyNoInteractions();
        finish();
        String result = messages(); assertTrue(result.contains("Wiped website")); assertTrue(result.contains("7 row(s) and 3"));
        confirm(); assertTrue(messages().contains("Nothing to confirm"));
        api.verify(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"), times(1));
    }

    @Test void apiFailureReportsTheErrorAndCannotReuseConfirmation() {
        api.when(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"))
                .thenReturn(ProvinceSystemClient.RealmWipeResult.fail("offline"));
        arm(); confirm(); finish(); assertTrue(messages().contains("Website wipe failed: offline"));
        confirm(); assertTrue(messages().contains("Nothing to confirm"));
        assertTrue(background.isEmpty());
    }

    @Test void confirmationCannotDeleteADifferentRealmAfterConfigurationReload() {
        arm(); messages();
        gateway.when(GatewayClient::realmId).thenReturn("main");
        confirm();
        assertTrue(background.isEmpty(), "Confirmation for dev must never authorize wiping main");
        assertTrue(messages().contains("changed")); api.verifyNoInteractions();
    }

    @Test void expiredConfirmationDoesNotScheduleDeletion() {
        arm(); messages();
        now.addAndGet(30_001L);
        confirm(); assertTrue(messages().contains("expired"));
        assertTrue(background.isEmpty()); api.verifyNoInteractions();
        confirm(); assertTrue(messages().contains("Nothing to confirm"));
    }

    @Test void confirmationRemainsValidAtExactlyThirtySeconds() {
        api.when(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"))
                .thenReturn(ProvinceSystemClient.RealmWipeResult.success("dev", 1, 0));
        arm(); messages();
        now.addAndGet(30_000L);
        confirm();
        assertEquals(1, background.size(), "Expiry uses a strict greater-than boundary");
        finish();
        assertTrue(messages().contains("Wiped website"));
    }

    @Test void staticEntryPointUsesTheSystemClockAndStillConfirmsImmediately() {
        api.when(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"))
                .thenReturn(ProvinceSystemClient.RealmWipeResult.success("dev", 1, 0));
        assertTrue(WipeCommand.handle(player, new String[] {"wipe", "website"}));
        now.addAndGet(30_001L);
        assertTrue(WipeCommand.handle(player, new String[] {"wipe", "website", "confirm"}));
        assertEquals(1, background.size(), "Production uses the system clock independently of the injected test clock");
        finish();
        assertTrue(WipeCommand.handle(player, new String[] {"wipe", "website", "confirm"}));
        assertTrue(messages().contains("Nothing to confirm"));
        api.verify(() -> ProvinceSystemClient.wipeRealmCharacterData("dev"), times(1));
    }
}
