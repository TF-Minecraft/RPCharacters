package net.tfminecraft.rpcharacters.will;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.tfminecraft.rpcharacters.api.CharacterSkull;
import net.tfminecraft.rpcharacters.body.CharacterSelectView;
import net.tfminecraft.rpcharacters.estate.EstateHooks.Held;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.Characters;

/**
 * How the will screen draws an estate: one row per kind of holding with a vanilla item icon, a hover
 * list where the plugin's labels are readable, and inline faces for the people named.
 */
final class WillView {
    static final TextColor HEADING = NamedTextColor.GOLD;
    static final TextColor HEIR = NamedTextColor.GREEN;
    static final TextColor TEXT = CharacterSelectView.PALE;
    static final TextColor MUTED = CharacterSelectView.MUTED;
    static final TextColor LINK = CharacterSelectView.TEAL;
    static final TextColor WARN = NamedTextColor.YELLOW;
    static final int HOVER_LINES = 12;
    static final String MONEY = "cents";
    static final String GUILD_LOCKS = "guild-locks";
    static final String LETTERS = "letters";
    /** The stored body is collected with /estate collect; How wills work says so, not a row. */
    static final String BODY = "rpcharacters-body";

    /** A kind of holding; {@code listed} when the plugins' labels are worth showing on hover. */
    record Kind(String sprite, String one, String many, boolean listed) {}

    private static final String REVIEW = "item/barrier";
    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("vehicles", new Kind("item/minecart", "vehicle", "vehicles", true)),
            Map.entry("locks", new Kind("block/tripwire_hook", "lock", "locks", true)),
            Map.entry(GUILD_LOCKS, new Kind("block/tripwire_hook", "guild or faction lock", "guild and faction locks", true)),
            Map.entry("shops", new Kind("item/emerald", "shop", "shops", true)),
            Map.entry("stations", new Kind("item/writable_book", "research station", "research stations", true)),
            Map.entry(LETTERS, new Kind("item/paper", "letter", "letters", true)),
            Map.entry("sites", new Kind("item/brush", "dig site", "dig sites", true)),
            Map.entry("pets-and-houses", new Kind("item/bone", "pet or pet house", "pets and pet houses", true)),
            Map.entry("animals", new Kind("item/lead", "animal", "animals", false)),
            Map.entry("projects", new Kind("item/music_disc_cat", "studio project", "studio projects", false)),
            Map.entry("private-tables", new Kind("item/map", "game table", "game tables", false)),
            Map.entry("gear", new Kind("item/blaze_rod", "magic item", "magic items", false)),
            Map.entry("returns", new Kind("item/hopper", "Recycler return", "Recycler returns", false)),
            Map.entry("heldGear", new Kind(REVIEW, "magic item for staff review", "magic items for staff review", true)),
            Map.entry("heldQueues", new Kind(REVIEW, "Recycler queue for staff review", "Recycler queues for staff review", true)),
            Map.entry("heldLibraries", new Kind(REVIEW, "studio library for staff review", "studio libraries for staff review", true)),
            Map.entry("table-review", new Kind(REVIEW, "game table for staff review", "game tables for staff review", true)));

    /** One row: a kind, how many and the labels behind it. */
    record Row(String key, Kind kind, int count, List<String> labels) {
        Component text() {
            return Component.text(count + " " + (count == 1 ? kind.one() : kind.many()), TEXT);
        }
    }

    /** The money line, and every other kind of holding in a steady order. */
    record Estate(String money, List<Row> rows) {
        boolean isEmpty() {
            return money == null && rows.isEmpty();
        }
    }

    static Function<UUID, Player> online = Bukkit::getPlayer;
    static Function<RPCharacter, ItemStack> skulls = CharacterSkull::of;

    private WillView() {}

    /** Groups what the participants hold by kind; money comes from {@code money} when it can say. */
    static Estate estate(List<Held> held, String money) {
        String moneyLine = null;
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Held one : held) {
            if (BODY.equals(one.participant())) continue;
            if (one.counts().getOrDefault(MONEY, 0) > 0) {
                moneyLine = money != null ? money
                        : one.labels().isEmpty() ? null : one.labels().getFirst().replaceFirst("^Money: ", "");
                continue;
            }
            for (Map.Entry<String, Integer> count : one.counts().entrySet()) {
                if (count.getValue() <= 0) continue;
                Kind kind = KINDS.getOrDefault(count.getKey(), new Kind("item/bundle", count.getKey(), count.getKey(), false));
                List<String> labels = labels(count.getKey(), one);
                rows.merge(count.getKey(), new Row(count.getKey(), kind, count.getValue(), labels), (a, b) -> {
                    List<String> both = new ArrayList<>(a.labels());
                    both.addAll(b.labels());
                    return new Row(a.key(), a.kind(), a.count() + b.count(), both);
                });
            }
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> Integer.compare(order(a.key()), order(b.key())));
        return new Estate(moneyLine, sorted);
    }

    /** A participant with two kinds marks its guild and faction labels; others label one kind. */
    private static List<String> labels(String key, Held held) {
        long kinds = held.counts().values().stream().filter(count -> count > 0).count();
        if (kinds == 1) return held.labels();
        if (!held.counts().containsKey(GUILD_LOCKS)) return List.of();
        return held.labels().stream().filter(label -> guildLock(label) == GUILD_LOCKS.equals(key)).toList();
    }

    private static boolean guildLock(String label) {
        return label.endsWith("(guild)") || label.endsWith("(faction)");
    }

    private static int order(String key) {
        List<String> steady = List.of("vehicles", "locks", "shops", "animals", "pets-and-houses", "stations", "sites",
                "projects", "private-tables", "gear", "returns", LETTERS, GUILD_LOCKS);
        int at = steady.indexOf(key);
        return at < 0 ? steady.size() : at;
    }

    /** An icon, the count and, where the labels are readable, a hover list of them. */
    static Component row(Row row) {
        Component text = row.text();
        if (row.kind().listed() && !row.labels().isEmpty()) {
            boolean many = row.labels().size() > HOVER_LINES;
            List<String> shown = many ? byKind(row.labels()) : row.labels();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("Your " + row.kind().many() + (many ? ", by kind" : ""), TEXT));
            shown.stream().limit(HOVER_LINES).forEach(label -> lines.add(Component.text(label, NamedTextColor.GRAY)));
            if (shown.size() > HOVER_LINES) {
                lines.add(Component.text("and " + (shown.size() - HOVER_LINES) + " more", NamedTextColor.DARK_GRAY));
            }
            text = text.decorate(TextDecoration.UNDERLINED)
                    .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), lines)));
        }
        return icon(row.kind().sprite()).append(Component.space()).append(text);
    }

    /** Long lists as "Chest ×212": labels grouped by the words before " at ", most first. */
    static List<String> byKind(List<String> labels) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String label : labels) {
            int at = label.indexOf(" at ");
            kinds.merge(at < 0 ? label : label.substring(0, at), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(kinds.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sorted.stream().map(kind -> kind.getKey() + " \u00d7" + kind.getValue()).toList();
    }

    static Component money(String line) {
        return icon("item/gold_nugget").append(Component.space()).append(Component.text(line, TEXT));
    }

    /** A vanilla block-atlas texture drawn inline. */
    static Component icon(String sprite) {
        return Component.object(ObjectContents.sprite(Key.key("minecraft", "blocks"), Key.key("minecraft", sprite)))
                .shadowColor(ShadowColor.none());
    }

    static Component heading(String text) {
        return Component.text(text, HEADING, TextDecoration.BOLD);
    }

    static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * A character's face, then their name: the live skin when they are playing that character, their wardrobe
     * when they are one of {@code viewer}'s characters, else the name alone.
     */
    static Component named(CharacterInfo info, PlayerData viewer, TextColor colour) {
        return face(info, viewer).append(Component.text(info.name(), colour));
    }

    static Component face(CharacterInfo info, PlayerData viewer) {
        Player owner = online.apply(info.ref().ownerId());
        if (owner != null && Characters.currentSession(owner).map(session -> session.ref().equals(info.ref())).orElse(false)) {
            return face(owner.getUniqueId(), owner.getPlayerProfile());
        }
        if (viewer != null && viewer.getUniqueId().equals(info.ref().ownerId())) {
            RPCharacter character = viewer.getCharacterById(info.ref().characterId().toString());
            ItemStack head = character == null ? null : skulls.apply(character);
            if (head != null && head.getItemMeta() instanceof SkullMeta meta && meta.getPlayerProfile() != null) {
                return face(info.ref().ownerId(), meta.getPlayerProfile());
            }
        }
        return Component.empty();
    }

    private static Component face(UUID id, PlayerProfile profile) {
        PlayerHeadObjectContents.Builder head = ObjectContents.playerHead().id(id).hat(true);
        boolean textured = false;
        for (ProfileProperty property : profile.getProperties()) {
            if (!"textures".equals(property.getName())) continue;
            head.profileProperty(PlayerHeadObjectContents.property(property.getName(), property.getValue(), property.getSignature()));
            textured = true;
        }
        return textured ? Component.object(head.build()).append(Component.space()) : Component.empty();
    }

    static String plain(String text) {
        return ClueFormatter.stripColor(text).trim();
    }
}
