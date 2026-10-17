package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CoreProtectKnockoutsTest {
    /** Stands in for CoreProtect's main class, which exposes its API through getAPI(). */
    public abstract static class CoreProtectPlugin implements Plugin {
        public abstract Object getAPI();
    }

    /** Stands in for a CoreProtect API with knockout logging. */
    public static class KnockoutApi {
        final List<Player[]> logged = new ArrayList<>();
        boolean result = true;

        public boolean logPvpKnockout(Player attacker, Player victim) {
            logged.add(new Player[] { attacker, victim });
            return result;
        }
    }

    /** Stands in for a CoreProtect API from before knockout logging. */
    public static class OldApi {
    }

    private MockedStatic<Bukkit> bukkit;
    private PluginManager plugins;
    private CoreProtectPlugin coreProtect;
    private final Player attacker = mock(Player.class);
    private final Player victim = mock(Player.class);
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { warnings.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach
    void setUp() {
        CoreProtectKnockouts.resetWarning();
        plugins = mock(PluginManager.class);
        coreProtect = mock(CoreProtectPlugin.class);
        when(coreProtect.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("CoreProtect")).thenReturn(coreProtect);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        Logger.getLogger("RPCharacters").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("RPCharacters").removeHandler(capture);
        bukkit.close();
    }

    @Test
    void knockoutIsPassedToCoreProtectsApi() {
        KnockoutApi api = new KnockoutApi();
        when(coreProtect.getAPI()).thenReturn(api);
        assertTrue(CoreProtectKnockouts.log(attacker, victim));
        assertEquals(1, api.logged.size());
        assertSame(attacker, api.logged.getFirst()[0]);
        assertSame(victim, api.logged.getFirst()[1]);
        api.result = false;
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingPlayersOrAMissingDisabledOrUnreadyCoreProtectLogNothing() {
        assertFalse(CoreProtectKnockouts.log(null, victim));
        assertFalse(CoreProtectKnockouts.log(attacker, null));
        when(coreProtect.getAPI()).thenReturn(null);
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        when(coreProtect.isEnabled()).thenReturn(false);
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        when(plugins.getPlugin("CoreProtect")).thenReturn(null);
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        verify(coreProtect, times(1)).getAPI();
        assertTrue(warnings.isEmpty());
    }

    @Test
    void olderCoreProtectWarnsOnceAndNeverBreaksTheKnockout() {
        when(coreProtect.getAPI()).thenReturn(new OldApi());
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        bukkit.when(Bukkit::getPluginManager).thenThrow(new IllegalStateException("server stopping"));
        assertFalse(CoreProtectKnockouts.log(attacker, victim));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().getMessage().contains("logPvpKnockout"));
    }
}
