package net.tfminecraft.rpcharacters.plots;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Everything a plot does, one file per plot, for staff. CoreProtect already keeps chat, commands, logins
 * and kills; this adds menu actions, rolls, leaks, hunts, encounters and the plot side of each death.
 */
final class PlotLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Map<String, Long> encounters = new HashMap<>();
    static Path folder;

    private PlotLog() {}

    static void start(Path logs) {
        folder = logs;
        encounters.clear();
    }

    /** A character as staff need it: character name and account. */
    static String who(PlotCharacter character) {
        return character == null ? "-" : character.name + " (" + character.playerName + ")";
    }

    static String where(Location at) {
        return at == null || at.getWorld() == null ? "-"
                : at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    static String where(Player player) { return player == null ? "-" : where(player.getLocation()); }

    static void write(Plot plot, String event, String details) {
        if (folder == null) return;
        String line = TIME.format(Instant.ofEpochMilli(PlotService.now())) + " UTC  " + event + "  " + details + System.lineSeparator();
        try {
            Files.createDirectories(folder);
            Files.writeString(file(plot.code), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            RPCharacters.plugin.getLogger().log(Level.WARNING, "Could not write the log of plot " + plot.code, error);
        }
    }

    static Path file(String code) { return folder.resolve(code.toUpperCase(java.util.Locale.ROOT) + ".log"); }

    /** A conspirator near the target, at most once per pair per encounter gap. */
    static void encounter(Plot plot, PlotCharacter suspect, Player suspected, Player target) {
        String key = plot.code + "|" + suspect.characterId;
        long now = PlotService.now();
        Long last = encounters.get(key);
        if (last != null && now - last < PlotConfig.encounterGap()) return;
        encounters.put(key, now);
        write(plot, "ENCOUNTER", who(suspect) + " (" + plot.role(suspect.characterId).label() + ") within "
                + Math.round(suspected.getLocation().distance(target.getLocation())) + " blocks of the target at " + where(target));
    }

    /** Lines of the plot's log, newest last, or an empty list without one. */
    static List<String> read(String code) throws IOException {
        Path log = file(code);
        return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
    }
}
