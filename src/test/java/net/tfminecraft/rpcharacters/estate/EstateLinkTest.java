package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.RPCharacters;

class EstateLinkTest {
    @AfterEach
    void tearDown() {
        EstateLink.install(null);
    }

    @Test
    void probesNeedEveryEstateClass() {
        assertTrue(EstateLink.present(EstateLink.REQUIRED));
        assertFalse(EstateLink.present("net.tfminecraft.tlibs.character.estate.Estates", "net.tfminecraft.tlibs.Missing"));
    }

    @Test
    void anOlderTLibsLeavesEstatesOffAndAFailedStartIsOnlyLogged() throws Exception {
        RPCharacters plugin = mock(RPCharacters.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        String saved = EstateLink.REQUIRED[0];
        EstateLink.REQUIRED[0] = "net.tfminecraft.tlibs.Missing";
        try {
            EstateLink.start(plugin);
        } finally {
            EstateLink.REQUIRED[0] = saved;
        }
        assertNull(EstateLink.current());
        verify(logger).warning(contains("TLibs 2.6.0 is required"));
        try (var module = mockStatic(EstateModule.class)) {
            module.when(() -> EstateModule.start(plugin)).thenThrow(new IOException("unreadable"));
            EstateLink.start(plugin);
            assertNull(EstateLink.current());
            module.when(() -> EstateModule.start(plugin)).thenThrow(new NoSuchMethodError("Estates.decided"));
            EstateLink.start(plugin);
            assertNull(EstateLink.current());
            verify(logger).log(eq(java.util.logging.Level.SEVERE), contains("could not start"), any(NoSuchMethodError.class));
            EstateModule running = mock(EstateModule.class);
            module.when(() -> EstateModule.start(plugin)).thenReturn(running);
            EstateLink.start(plugin);
            assertSame(running, EstateLink.current());
            EstateLink.stop();
            verify(running).stop();
            assertNull(EstateLink.current());
            EstateLink.stop();
        }
        verify(logger).log(eq(java.util.logging.Level.SEVERE), contains("could not start"), any(IOException.class));
    }
}
