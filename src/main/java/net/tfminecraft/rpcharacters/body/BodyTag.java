package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Which body the vanilla player file holds. Saved with the player data, so after a crash it still names the
 * character whose possessions are on the player. The tag records its account and is refused on any other.
 */
public sealed interface BodyTag permits BodyTag.Untagged, BodyTag.Room, BodyTag.Live {
    NamespacedKey KEY = new NamespacedKey("rpcharacters", "body");

    /** No tag yet: the account has not used character bodies. */
    record Untagged() implements BodyTag {
    }

    /** No character body on the player; {@code lastCharacterId} may be null. */
    record Room(UUID lastCharacterId) implements BodyTag {
    }

    /** The player carries this character's body, applied from stored revision {@code rev} (0 = never stored). */
    record Live(UUID characterId, long rev) implements BodyTag {
        public Live {
            Objects.requireNonNull(characterId, "characterId");
            if (rev < 0) throw new IllegalArgumentException("Body revision cannot be negative");
        }
    }

    static BodyTag read(Player player) throws IOException {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (!data.has(KEY)) return new Untagged();
        String value;
        try {
            value = data.get(KEY, PersistentDataType.STRING);
        } catch (IllegalArgumentException error) {
            value = null;
        }
        String[] parts = value == null ? new String[0] : value.split(";", -1);
        if (parts.length < 4 || !parts[0].equals("1")) throw new IOException("Invalid character body tag");
        if (!player.getUniqueId().equals(BodyJson.uuid(parts[1]))) {
            throw new IOException("Character body tag belongs to another account");
        }
        if (parts[2].equals("room") && parts.length == 4) {
            if (parts[3].isEmpty()) return new Room(null);
            UUID last = BodyJson.uuid(parts[3]);
            if (last != null) return new Room(last);
        } else if (parts[2].equals("live") && parts.length == 5) {
            UUID character = BodyJson.uuid(parts[3]);
            long rev = revision(parts[4]);
            if (character != null && rev >= 0) return new Live(character, rev);
        }
        throw new IOException("Invalid character body tag");
    }

    static void live(Player player, UUID characterId, long rev) {
        Live tag = new Live(characterId, rev);
        write(player, "live;" + tag.characterId() + ";" + tag.rev());
    }

    static void room(Player player, UUID lastCharacterId) {
        write(player, "room;" + (lastCharacterId == null ? "" : lastCharacterId.toString()));
    }

    private static void write(Player player, String state) {
        player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING,
                "1;" + player.getUniqueId() + ";" + state);
    }

    private static long revision(String text) {
        if (!text.matches("0|[1-9][0-9]{0,18}")) return -1;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
