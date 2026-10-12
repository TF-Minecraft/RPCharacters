package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.junit.jupiter.api.Test;

class BodyRespawnTest {
    public interface CraftPlayer { Object getHandle(); }
    public interface Handle { Object getRespawnConfig(); }
    public interface RespawnConfig { Object forced(); }
    public enum RemovalReason { KILLED }
    public static class NativePlayers {
        Object received;
        boolean alive;
        RemovalReason removal;
        RespawnReason reason;
        public void respawn(Handle handle, boolean alive, RemovalReason removal, RespawnReason reason) {
            this.received = handle; this.alive = alive; this.removal = removal; this.reason = reason;
        }
    }

    @Test void genuineRespawnUsesTheNativeResetWithoutKillingAnMmoHealedPlayerAgain() throws Exception {
        Player player = player(); Handle handle = mock(Handle.class);
        when(((CraftPlayer) player).getHandle()).thenReturn(handle);
        Server server = mock(Server.class, withSettings().extraInterfaces(CraftPlayer.class));
        when(player.getServer()).thenReturn(server);
        NativePlayers players = new NativePlayers(); when(((CraftPlayer) server).getHandle()).thenReturn(players);
        when(player.getHealth()).thenReturn(.001); when(player.isDead()).thenReturn(false);
        BodyRespawn.respawn(player);
        assertSame(handle,players.received); assertFalse(players.alive);
        assertEquals(RemovalReason.KILLED,players.removal); assertEquals(RespawnReason.PLUGIN,players.reason);
        verify(player,never()).setHealth(anyDouble()); verify(player,never()).spigot();
    }

    @Test void incompatibleOrFailedNativeRespawnStopsRecoveryWithoutMutatingHealth() {
        assertThrows(IOException.class, () -> BodyRespawn.respawn(mock(Player.class)));
        Player player = player(); when(((CraftPlayer) player).getHandle()).thenReturn(mock(Handle.class));
        Server server = mock(Server.class, withSettings().extraInterfaces(CraftPlayer.class));
        when(player.getServer()).thenReturn(server); when(((CraftPlayer) server).getHandle()).thenReturn(new Object());
        assertEquals("Paper player respawn method is unavailable",assertThrows(IOException.class, () -> BodyRespawn.respawn(player)).getMessage());
        NativePlayers players = spy(new NativePlayers()); when(((CraftPlayer) server).getHandle()).thenReturn(players);
        doThrow(new IllegalStateException("native failure")).when(players).respawn(any(),anyBoolean(),any(),any());
        assertThrows(IOException.class, () -> BodyRespawn.respawn(player)); verify(player,never()).setHealth(anyDouble());
    }

    @Test void absentSpawnNeedsNoPaperReflectionAndNullConfigIsNotForced() throws Exception {
        assertFalse(BodyRespawn.forced(mock(Player.class)));
        Player player = player();
        Handle handle = mock(Handle.class);
        when(((CraftPlayer) player).getHandle()).thenReturn(handle);
        assertFalse(BodyRespawn.forced(player));
    }

    @Test void readsBothVanillaForcedStatesWithoutChangingTheSpawnPoint() throws Exception {
        Player player = player();
        Handle handle = mock(Handle.class);
        RespawnConfig config = mock(RespawnConfig.class);
        when(((CraftPlayer) player).getHandle()).thenReturn(handle);
        when(handle.getRespawnConfig()).thenReturn(config);
        when(config.forced()).thenReturn(true, false);
        assertTrue(BodyRespawn.forced(player));
        assertFalse(BodyRespawn.forced(player));
        verify(player, never()).setRespawnLocation(any(), anyBoolean());
    }

    @Test void incompatiblePresentSpawnAndFailedOrMalformedFlagStopBodyCapture() {
        Player incompatible = mock(Player.class);
        when(incompatible.getRespawnLocation(false)).thenReturn(new Location(null, 1, 64, 1));
        assertInstanceOf(NoSuchMethodException.class,
                assertThrows(IOException.class, () -> BodyRespawn.forced(incompatible)).getCause());
        Player player = player();
        assertThrows(IOException.class, () -> BodyRespawn.forced(player)); // No compatible handle.
        Handle handle = mock(Handle.class);
        when(((CraftPlayer) player).getHandle()).thenReturn(handle);
        when(handle.getRespawnConfig()).thenThrow(new IllegalStateException("unavailable"));
        IOException failure = assertThrows(IOException.class, () -> BodyRespawn.forced(player));
        assertInstanceOf(IllegalStateException.class, failure.getCause().getCause());
        RespawnConfig config = mock(RespawnConfig.class);
        doReturn(config).when(handle).getRespawnConfig();
        when(config.forced()).thenReturn("true");
        assertEquals("Invalid Paper forced-respawn flag",
                assertThrows(IOException.class, () -> BodyRespawn.forced(player)).getMessage());
    }

    private Player player() {
        Player player = mock(Player.class, withSettings().extraInterfaces(CraftPlayer.class));
        when(player.getRespawnLocation(false)).thenReturn(new Location(null, 10, 70, 20));
        return player;
    }
}
