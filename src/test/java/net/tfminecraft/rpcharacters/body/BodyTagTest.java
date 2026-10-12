package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BodyTagTest {
    private PlayerMock player;
    private final UUID character = UUID.randomUUID();

    @BeforeEach
    void setup() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void cleanup() {
        MockBukkit.unmock();
    }

    @Test
    void recordsWhichBodyThePlayerFileHolds() throws Exception {
        assertEquals("rpcharacters:body", BodyTag.KEY.toString());
        assertEquals(new BodyTag.Untagged(), BodyTag.read(player));

        BodyTag.room(player, null);
        assertEquals(new BodyTag.Room(null), BodyTag.read(player));
        BodyTag.live(player, character, 0);
        assertEquals(new BodyTag.Live(character, 0), BodyTag.read(player));
        BodyTag.live(player, character, Long.MAX_VALUE);
        assertEquals(new BodyTag.Live(character, Long.MAX_VALUE), BodyTag.read(player));
        BodyTag.room(player, character);
        assertEquals(new BodyTag.Room(character), BodyTag.read(player));
        assertEquals("1;" + player.getUniqueId() + ";room;" + character,
                player.getPersistentDataContainer().get(BodyTag.KEY, PersistentDataType.STRING));
    }

    @Test
    void refusesTagsItCannotTrust() {
        String owner = player.getUniqueId().toString();
        String other = UUID.randomUUID().toString();
        for (String bad : List.of("", "1", "2;" + owner + ";room;", "1;" + other + ";room;", "1;nobody;room;",
                "1;" + owner.toUpperCase() + ";room;", "1;" + owner + ";room", "1;" + owner + ";room;x",
                "1;" + owner + ";room;" + character + ";1", "1;" + owner + ";live;" + character,
                "1;" + owner + ";live;x;1", "1;" + owner + ";live;" + character + ";-1",
                "1;" + owner + ";live;" + character + ";01", "1;" + owner + ";live;" + character + ";1.0",
                "1;" + owner + ";live;" + character + ";9999999999999999999",
                "1;" + owner + ";dead;" + character + ";1", "1;" + owner + ";live;1-1-1-1-1;1")) {
            player.getPersistentDataContainer().set(BodyTag.KEY, PersistentDataType.STRING, bad);
            assertThrows(IOException.class, () -> BodyTag.read(player), bad);
        }
        player.getPersistentDataContainer().set(BodyTag.KEY, PersistentDataType.INTEGER, 4);
        assertThrows(IOException.class, () -> BodyTag.read(player));
    }

    @Test
    void wrongTagTypesFromPaperFailClosed() {
        Player paper = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(paper.getPersistentDataContainer()).thenReturn(data);
        when(data.has(BodyTag.KEY)).thenReturn(true);
        when(data.get(BodyTag.KEY, PersistentDataType.STRING)).thenThrow(new IllegalArgumentException("wrong tag type"));
        assertThrows(IOException.class, () -> BodyTag.read(paper));
    }

    @Test
    void liveTagsNeedACharacterAndARevision() {
        assertThrows(NullPointerException.class, () -> new BodyTag.Live(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new BodyTag.Live(character, -1));
        assertThrows(IllegalArgumentException.class, () -> BodyTag.live(player, character, -1));
        assertFalse(player.getPersistentDataContainer().has(BodyTag.KEY));
    }
}
