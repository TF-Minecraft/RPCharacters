package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** Retains the pre-migration body and fixes its single recipient before writing any character body. */
final class CharacterBodyMigration {
    private final Path root;
    private final YamlConfiguration heirs;
    private final BodyStore store;
    private final Location newCharacterSpawn;

    CharacterBodyMigration(Path root, Path heirFile, String expectedHash, BodyStore store,
            Location newCharacterSpawn) throws IOException {
        this.root = root;
        this.store = store;
        this.newCharacterSpawn = newCharacterSpawn.clone();
        byte[] bytes = Files.readAllBytes(heirFile);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(expectedHash)) throw new IOException("Character body heir manifest checksum does not match");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        heirs = parse(new String(bytes, StandardCharsets.UTF_8));
        if (!Integer.valueOf(1).equals(heirs.get("version"))) throw new IOException("Unsupported character body heir manifest");
        var entries = heirs.getConfigurationSection("heirs");
        if (entries == null) throw new IOException("Body heir manifest needs an heirs mapping");
        for (String owner : entries.getKeys(false)) {
            uuid(owner);
            uuid(entries.getString(owner));
        }
    }

    UUID initialize(PlayerData account, BodySnapshot legacy) throws IOException {
        UUID owner = account.getUniqueId();
        Path file = root.resolve(owner + ".yml");
        YamlConfiguration plan;
        if (Files.exists(file)) {
            plan = readPlan(owner);
            if (plan.getBoolean("complete") && !legacy.serialize().equals(plan.getString("legacy-body"))) {
                throw new IOException("Migrated account has lost its body tag; restore its saved player data before playing");
            }
        } else {
            String heir = heirs.getString("heirs." + owner);
            if (heir == null && !account.getCharacters().isEmpty()
                    || heir != null && account.getCharacterById(heir) == null) {
                throw new IOException("No reviewed body heir for " + owner);
            }
            plan = new YamlConfiguration();
            plan.set("version", 1);
            plan.set("owner", owner.toString());
            plan.set("heir", heir);
            plan.set("legacy-body", legacy.serialize());
            plan.set("fresh-body", BodySnapshot.fresh(account.getPlayer(), newCharacterSpawn).serialize());
            plan.set("complete", false);
            plan.createSection("characters");
            for (RPCharacter character : account.getCharacters()) {
                UUID id = uuid(character.getId());
                if (plan.contains("characters." + id)) throw new IOException("Duplicate body migration character: " + id);
                if (store.read(owner, id).isPresent()) throw new IOException("Character body exists without its migration plan: " + id);
                Location at = character.getLastLocation();
                plan.set("characters." + id,
                        at != null && at.getWorld() != null ? at : newCharacterSpawn);
            }
            write(file, plan);
        }
        UUID heir = plan.getString("heir") == null ? null : uuid(plan.getString("heir"));
        BodySnapshot original = BodySnapshot.deserialize(plan.getString("legacy-body"));
        BodySnapshot fresh = BodySnapshot.deserialize(plan.getString("fresh-body"));
        var characters = plan.getConfigurationSection("characters");
        if (characters != null) {
            for (String id : characters.getKeys(false)) {
                UUID characterId = uuid(id);
                BodySnapshot body = characterId.equals(heir) ? original : fresh.withLocation(characters.getLocation(id));
                var saved = store.read(owner, characterId);
                if (saved.isPresent()) {
                    if (saved.get().rev() != 1) {
                        throw new IOException("An untagged player file cannot replace a played character body");
                    }
                    if (!saved.get().body().equals(body)) {
                        throw new IOException("Character body does not match its retained migration plan");
                    }
                    continue;
                }
                if (plan.getBoolean("complete")) throw new IOException("Migrated character body is missing: " + id);
                store.write(owner, characterId, 0, "migration", body);
            }
        }
        if (heir == null) store.quarantine(owner, "account-had-no-character", original);
        plan.set("complete", true);
        write(file, plan);
        return heir;
    }

    boolean migrated(UUID owner) throws IOException {
        Path file = root.resolve(owner + ".yml");
        return Files.exists(file) && readPlan(owner).getBoolean("complete");
    }

    UUID heir(UUID owner) {
        try {
            String value = Files.exists(root.resolve(owner + ".yml")) ? readPlan(owner).getString("heir")
                    : heirs.getString("heirs." + owner);
            return value == null ? null : uuid(value);
        } catch (IOException invalid) {
            return null;
        }
    }

    void ensureBody(PlayerData account, RPCharacter character) throws IOException {
        UUID id = uuid(character.getId());
        if (store.read(account.getUniqueId(), id).isPresent()) return;
        YamlConfiguration plan = readPlan(account.getUniqueId());
        if (!plan.getBoolean("complete")) throw new IOException("Body migration is incomplete");
        if (plan.contains("characters." + id)) throw new IOException("Migrated character body is missing: " + id);
        Location at = character.getLastLocation();
        if (at == null || at.getWorld() == null) at = newCharacterSpawn;
        store.write(account.getUniqueId(), id, 0, "new-character", BodySnapshot.fresh(account.getPlayer(), at));
    }

    private YamlConfiguration readPlan(UUID owner) throws IOException {
        YamlConfiguration plan = parse(Files.readString(root.resolve(owner + ".yml")));
        if (!Integer.valueOf(1).equals(plan.get("version")) || !owner.toString().equals(plan.get("owner"))) {
            throw new IOException("Body migration identity mismatch");
        }
        if (!plan.isBoolean("complete")) throw new IOException("Body migration completion flag is invalid");
        if (plan.getString("heir") != null) uuid(plan.getString("heir"));
        if (!java.util.Objects.equals(plan.getString("heir"), heirs.getString("heirs." + owner))) {
            throw new IOException("Reviewed body heir differs from the retained migration plan");
        }
        BodySnapshot.deserialize(plan.getString("legacy-body"));
        BodySnapshot.deserialize(plan.getString("fresh-body"));
        var characters = plan.getConfigurationSection("characters");
        if (characters == null) throw new IOException("Body migration roster is invalid");
        for (String id : characters.getKeys(false)) {
            uuid(id);
            Location location = characters.getLocation(id);
            if (location == null || location.getWorld() == null) throw new IOException("Body migration location is unavailable");
        }
        if (plan.getString("heir") == null && !characters.getKeys(false).isEmpty()
                || plan.getString("heir") != null && !plan.contains("characters." + plan.getString("heir"))) {
            throw new IOException("Body migration heir is absent from its roster");
        }
        return plan;
    }

    private static UUID uuid(String text) throws IOException {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Invalid body migration UUID", error);
        }
    }

    private static YamlConfiguration parse(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException error) {
            throw new IOException("Invalid body migration data", error);
        }
        return yaml;
    }

    private static void write(Path file, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".migration-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(yaml.saveToString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
