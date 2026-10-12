package net.tfminecraft.rpcharacters.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CharacterSelectionRequestEventTest {
    @Test void denialCarriesAReasonAndPlainCancellationHasAGeneralOne() {
        Player player = mock(Player.class);
        CharacterSelectionRequestEvent event = new CharacterSelectionRequestEvent(player);
        assertSame(player, event.getPlayer()); assertFalse(event.isCancelled()); assertNull(event.getReason());
        event.setCancelled(true); assertTrue(event.isCancelled()); assertTrue(event.getReason().contains("right now"));
        event.setCancelled(false); assertFalse(event.isCancelled()); assertNull(event.getReason());
        event.deny("Leave the battle first."); event.setCancelled(true);
        assertTrue(event.isCancelled()); assertEquals("Leave the battle first.", event.getReason());
        assertSame(CharacterSelectionRequestEvent.getHandlerList(), event.getHandlers());
    }
}
