package net.tfminecraft.rpcharacters.plots;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.evilrp.EvilRpLoader;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.utils.ClueGiver;

/** The plots' view of RPCharacters: characters, deaths, evil RP sessions and clues. */
final class PlotCharacters {
    private PlotCharacters() {}

    /** The player's living active character, or null. */
    static PlotCharacter active(Player player) {
        var data = player == null ? null : PlayerManager.get(player);
        var character = data == null ? null : data.getActiveCharacter();
        if (character == null || character.getStatus() != Status.ALIVE) return null;
        return new PlotCharacter(character.getId(), player.getUniqueId(), player.getName(), character.getName());
    }

    static Player online(PlotCharacter character) {
        if (character == null || character.playerId == null) return null;
        Player player = Bukkit.getPlayer(character.playerId);
        PlotCharacter active = active(player);
        return active != null && active.is(character.characterId) ? player : null;
    }

    static boolean dead(PlotCharacter character) {
        var data = character == null || character.playerId == null ? null : PlayerManager.get(character.playerId);
        return data != null && data.getCharacters().stream().anyMatch(other ->
                character.characterId.equals(other.getId()) && other.getStatus() == Status.DEAD);
    }

    /** False when the account has not been online for the inactivity period. */
    static boolean active(UUID account, long now) {
        if (account == null) return false;
        if (Bukkit.getPlayer(account) != null) return true;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(account);
        return now - offline.getLastSeen() < PlotConfig.inactivity();
    }

    /** The active character of an offline account, read from its saved character files. */
    static PlotCharacter offline(OfflinePlayer account) {
        if (account == null) return null;
        Path folder = RPCharacters.plugin.getDataFolder().toPath().resolve("data/characterdata").resolve(account.getUniqueId().toString());
        if (!Files.isDirectory(folder)) return null;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                JsonObject json = read(file);
                if (json != null && text(json, "id") != null && text(json, "name") != null
                        && "true".equalsIgnoreCase(text(json, "active")) && !"DEAD".equalsIgnoreCase(text(json, "status")))
                    return new PlotCharacter(text(json, "id"), account.getUniqueId(), account.getName(), text(json, "name"));
            }
        } catch (java.io.IOException ignored) {
            // An unreadable folder is treated like an account without characters.
        }
        return null;
    }

    private static JsonObject read(Path file) {
        try {
            return PlotStore.GSON.fromJson(Files.readString(file), JsonObject.class);
        } catch (java.io.IOException | RuntimeException damaged) {
            return null;
        }
    }

    private static String text(JsonObject json, String key) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsString().isBlank() ? value.getAsString() : null;
    }

    /** The name others see for the player: a mask or alias hides the character. */
    static String seenAs(Player player, PlotCharacter character) {
        String seen = ChatColor.stripColor(DisplayIdentityService.resolveDisplay(player));
        return seen == null || seen.isBlank() ? character.name : seen;
    }

    /** The active character's permanent attributes, with MMOCore's base values when it tracks them. */
    static Map<String, Integer> attributes(Player player) {
        var data = PlayerManager.get(player);
        var character = data == null ? null : data.getActiveCharacter();
        if (character == null) throw new IllegalStateException("An active character is required for an Intrigue roll");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String attribute : Intrigue.WEIGHTS.keySet())
            result.put(attribute, character.getAttributeData().getAmount(new AttributeModifier(attribute, 0)));
        var attributes = net.Indyuce.mmocore.api.player.PlayerData.get(player).getAttributes();
        for (String attribute : result.keySet()) {
            var instance = attributes.getInstance(attribute);
            if (instance != null) result.put(attribute, instance.getBase());
        }
        return result;
    }

    /** Permanently kills the character if it is still the player's living active character. */
    static boolean permakill(Player player, String characterId, Player killer) {
        var data = PlayerManager.get(player);
        var character = data == null ? null : data.getActiveCharacter();
        if (character == null || !characterId.equals(character.getId())) return false;
        return PermadeathService.killCharacter(player, character, PermakillCause.OTHER, killer);
    }

    /** Hunting is evil play: it starts or keeps the assassin's evil RP session, which also bars switching. */
    static void keepEvilSession(Player player, boolean announce) {
        if (announce) {
            EvilRpService.recordPlay(player);
            return;
        }
        var data = PlayerManager.get(player);
        var character = data == null ? null : data.getActiveCharacter();
        if (character == null) return;
        long until = System.currentTimeMillis() + EvilRpLoader.getSessionMs();
        if (character.getEvilRpSessionEndsAtMs() < until) character.setEvilRpSessionEndsAtMs(until);
    }

    /** Leaves up to count of the player's character clues around the location. Returns how many. */
    static int leaveClues(Location at, Player owner, int count) {
        java.util.List<String> left = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            var clue = ClueGiver.spawnClue(at, owner, left, true);
            if (clue == null) break;
            left.add(clue.getClueText());
        }
        return left.size();
    }
}
