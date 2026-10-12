package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BodyRespawnTest {
    public interface CraftPlayer { Object getHandle(); }
    public interface Handle { Object getRespawnConfig(); }
    public interface RespawnConfig { Object forced(); }

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
