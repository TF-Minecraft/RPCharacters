package net.tfminecraft.rpcharacters.plots;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Settings from plots.yml. Invalid values fall back to the defaults; new keys are added to the file. */
public final class PlotConfig {
    static final long DAY = 86_400_000L;
    private static final YamlConfiguration BUNDLED = YamlConfiguration.loadConfiguration(new InputStreamReader(
            PlotConfig.class.getResourceAsStream("/plots.yml"), StandardCharsets.UTF_8));
    private static ConfigurationSection source = BUNDLED;
    private static final Map<PlotApproach, int[]> approaches = new EnumMap<>(PlotApproach.class);
    private static final Map<PlotRole, Integer> difficulty = new EnumMap<>(PlotRole.class);
    private static String staffPermission;
    private static List<String> worlds = List.of();
    private static double requestExpiryDays, recruitingDays, retargetDays, inviteRadius, inviteMinutes, approachHours,
            aloneShare, confidantShare, spymasterShare, confidantHours, inactiveDays, buildDays, readyDays, luckMin, luckMax,
            progressPerCollaborator, masterStrength, assassinStrength, strengthCollaborator, otherCollaborator, requiredBase,
            requiredPerProtection, dailyLoss, lossPerMember, hidePerCollaborator, minimumLoss, leakLoss, revealMembers,
            revealProgress, revealAssassins, earlyProgress, lateProgress, betrayalSpeed, investigationDays, radius,
            findableMinutes, maxDays, huntAlertSecrecy, maxSurplus, extraDayPerSurplus, accompliceRadius, encounterRadius;
    private static int maxAssassins, maxCollaborators, noticeLimit, spymasterPenalty, confidantPenalty, suspicionDifficulty,
            scanSeconds, maxInvestigators, knownBonus, unknownPenalty, strongMargin, huntDays, maxDepth, hitWindowSeconds,
            huntWarmupSeconds, huntMinimumMinutes, areaIntervalSeconds, areaSize, sightingGapMinutes, maxExtraDays,
            killerClues, accompliceClues, encounterMinutes, luckSpread, luckDraws;
    private static double rollMultiplier;

    static { load(BUNDLED); }

    private PlotConfig() {}

    /** Reads plots.yml, adding any bundled keys it lacks (comments included) without touching set values. */
    public static void load(File file) throws IOException, InvalidConfigurationException {
        var settings = new YamlConfiguration();
        boolean changed = !file.exists();
        if (changed) settings.options().setHeader(BUNDLED.options().getHeader());
        else settings.load(file);
        for (var entry : BUNDLED.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection) && !settings.contains(entry.getKey())) {
                settings.set(entry.getKey(), entry.getValue());
                settings.setComments(entry.getKey(), BUNDLED.getComments(entry.getKey()));
                changed = true;
            }
        }
        if (changed) {
            Files.createDirectories(file.toPath().toAbsolutePath().getParent());
            var pending = file.toPath().resolveSibling(file.getName() + ".pending");
            Files.writeString(pending, settings.saveToString(), StandardCharsets.UTF_8);
            Files.move(pending, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        load(settings);
    }

    public static void load(ConfigurationSection config) {
        source = config;
        String permission = config.getString("staff-permission", "");
        staffPermission = permission == null || permission.isBlank() ? "rpchar.plots.staff" : permission.strip();
        requestExpiryDays = number("request-expiry-days", 7, 0, 3650);
        recruitingDays = number("recruiting-days", 7, 0, 3650);
        retargetDays = number("retarget-cooldown-days", 7, 0, 3650);
        inviteRadius = number("invite-radius", 8, 1, 256);
        inviteMinutes = number("invite-minutes", 10, 1, 10_080);
        maxAssassins = (int) number("roles.max-assassins", 2, 1, 20);
        maxCollaborators = (int) number("roles.max-collaborators", 2, 0, 20);
        noticeLimit = (int) number("notice-limit", 30, 1, 1000);
        approachHours = number("approach.change-cooldown-hours", 24, 0, 87_600);
        for (PlotApproach approach : PlotApproach.values()) {
            String key = "approach." + approach.key() + ".";
            approaches.put(approach, new int[]{(int) number(key + "offense", approach.defaultOffense(), -100, 100),
                    (int) number(key + "defense", approach.defaultDefense(), -100, 100)});
        }
        rollMultiplier = number("checks.intrigue-multiplier", 1.25, .01, 10);
        luckSpread = (int) number("checks.luck-spread", 75, 0, 1000);
        luckDraws = (int) number("checks.luck-draws", 3, 1, 20);
        aloneShare = number("defense.alone-share", .5, 0, 1);
        confidantShare = number("defense.confidant-share", .75, 0, 1);
        spymasterShare = number("defense.spymaster-share", 1, 0, 2);
        confidantHours = number("defense.confidant-change-cooldown-hours", 24, 0, 87_600);
        inactiveDays = number("defense.inactive-days", 3, .01, 3650);
        buildDays = number("building.days", 7, .01, 3650);
        readyDays = number("building.ready-days", 7, .01, 3650);
        luckMin = number("building.luck-min", .5, 0, 10);
        luckMax = Math.max(luckMin, number("building.luck-max", 1.5, 0, 10));
        progressPerCollaborator = number("building.progress-per-collaborator", .5, 0, 10);
        masterStrength = number("building.strength.master", .09, 0, 10);
        assassinStrength = number("building.strength.assassin", .055, 0, 10);
        strengthCollaborator = number("building.strength.strength-collaborator", .13, 0, 10);
        otherCollaborator = number("building.strength.other-collaborator", .03, 0, 10);
        requiredBase = number("building.strength.required-base", 20, 0, 10_000);
        requiredPerProtection = number("building.strength.required-per-protection", 1.3, 0, 100);
        dailyLoss = number("building.secrecy.daily-loss", 1.5, 0, 100);
        lossPerMember = number("building.secrecy.loss-per-member", 1.5, 0, 100);
        hidePerCollaborator = number("building.secrecy.hide-per-collaborator", 6, 0, 100);
        minimumLoss = number("building.secrecy.minimum-loss", .5, 0, 100);
        leakLoss = number("building.secrecy.leak-loss", 3, 0, 100);
        revealMembers = number("building.reveal.member-count", 60, 0, 100);
        revealProgress = number("building.reveal.progress", 40, 0, 100);
        revealAssassins = number("building.reveal.assassins", 20, 0, 100);
        earlyProgress = number("building.reveal.early-progress", 34, 0, 100);
        lateProgress = Math.max(earlyProgress, number("building.reveal.late-progress", 67, 0, 100));
        betrayalSpeed = number("betrayal.speed-multiplier", 2, 1, 100);
        spymasterPenalty = (int) number("betrayal.spymaster-investigation-penalty", 40, 0, 1000);
        confidantPenalty = (int) number("betrayal.confidant-investigation-penalty", 20, 0, 1000);
        suspicionDifficulty = (int) number("betrayal.suspicion-difficulty", 20, -1000, 1000);
        investigationDays = number("investigation.days", 3, 0, 3650);
        radius = number("investigation.radius", 6, 1, 256);
        scanSeconds = (int) number("investigation.scan-seconds", 5, 1, 3600);
        maxInvestigators = (int) number("investigation.max-investigators", 2, 0, 10);
        knownBonus = (int) number("investigation.known-bonus", 15, 0, 1000);
        unknownPenalty = (int) number("investigation.unknown-penalty", 15, 0, 1000);
        strongMargin = (int) number("investigation.strong-margin", 40, 0, 10_000);
        for (PlotRole role : PlotRole.values())
            difficulty.put(role, (int) number("investigation.difficulty." + role.key(), role.defaultDifficulty(), -1000, 1000));
        huntDays = (int) number("execution.hunt-days", 4, 1, 365);
        findableMinutes = number("execution.findable-minutes", 30, 0, 1440);
        maxDays = number("execution.max-days", 21, .01, 3650);
        maxDepth = (int) number("execution.findable.max-depth", 16, 0, 4096);
        worlds = List.copyOf(source.getStringList("execution.findable.worlds"));
        hitWindowSeconds = (int) number("execution.hit-window-seconds", 120, 0, 86_400);
        huntWarmupSeconds = (int) number("execution.hunt.warmup-seconds", 60, 0, 86_400);
        huntMinimumMinutes = (int) number("execution.hunt.minimum-minutes", 10, 0, 10_080);
        huntAlertSecrecy = number("execution.hunt.alert-secrecy", 40, 0, 100);
        areaIntervalSeconds = (int) number("execution.area.interval-seconds", 120, 0, 86_400);
        areaSize = (int) number("execution.area.size", 250, 1, 100_000);
        sightingGapMinutes = (int) number("execution.sighting-gap-minutes", 15, 0, 10_080);
        maxSurplus = number("execution.max-surplus", 2, 1, 100);
        extraDayPerSurplus = number("execution.extra-day-per-surplus", .5, .01, 100);
        maxExtraDays = (int) number("execution.max-extra-days", 2, 0, 365);
        killerClues = (int) number("execution.crime-scene.killer-clues", 2, 0, 20);
        accompliceClues = (int) number("execution.crime-scene.accomplice-clues", 1, 0, 20);
        accompliceRadius = number("execution.crime-scene.accomplice-radius", 16, 0, 256);
        encounterRadius = number("log.encounter-radius", 16, 1, 256);
        encounterMinutes = (int) number("log.encounter-minutes", 10, 1, 10_080);
    }

    private static double number(String key, double fallback, double min, double max) {
        double value = source.getDouble(key, fallback);
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

    /** A hint for an investigation roll; {name} is the name the investigator sees. */
    public static String hint(PlotRole role, boolean strong, String name) {
        String key = "investigation.hints." + role.key() + "." + (strong ? "strong" : "weak");
        String text = source.getString(key);
        if (text == null) text = BUNDLED.getString(key, "&7&o{name} is acting strangely.");
        return text.replace("{name}", name);
    }

    public static String staffPermission() { return staffPermission; }
    public static long requestExpiry() { return millis(requestExpiryDays); }
    public static long recruiting() { return millis(recruitingDays); }
    public static long retargetCooldown() { return millis(retargetDays); }
    public static double inviteRadius() { return inviteRadius; }
    public static long inviteDuration() { return Math.round(inviteMinutes * 60_000); }
    public static int maxAssassins() { return maxAssassins; }
    public static int maxCollaborators() { return maxCollaborators; }
    public static int maxMembers() { return maxAssassins + maxCollaborators; }
    public static int noticeLimit() { return noticeLimit; }
    public static long approachCooldown() { return Math.round(approachHours * 3_600_000); }
    public static int offenseBonus(PlotApproach approach) { return approaches.get(approach)[0]; }
    public static int defenseBonus(PlotApproach approach) { return approaches.get(approach)[1]; }
    public static double rollMultiplier() { return rollMultiplier; }
    public static int luckSpread() { return luckSpread; }
    public static int luckDraws() { return luckDraws; }
    public static double aloneShare() { return aloneShare; }
    public static double confidantShare() { return confidantShare; }
    public static double spymasterShare() { return spymasterShare; }
    public static long confidantCooldown() { return Math.round(confidantHours * 3_600_000); }
    public static long inactivity() { return millis(inactiveDays); }
    public static double buildDays() { return buildDays; }
    public static long ready() { return millis(readyDays); }
    public static double luckMin() { return luckMin; }
    public static double luckMax() { return luckMax; }
    public static double progressPerCollaborator() { return progressPerCollaborator; }
    public static double strengthWeight(PlotRole role, CollaboratorTask task) {
        return switch (role) {
            case MASTER -> masterStrength;
            case ASSASSIN -> assassinStrength;
            case COLLABORATOR -> task == CollaboratorTask.STRENGTH ? strengthCollaborator : otherCollaborator;
            default -> 0;
        };
    }
    public static double requiredBase() { return requiredBase; }
    public static double requiredPerProtection() { return requiredPerProtection; }
    public static double dailyLoss() { return dailyLoss; }
    public static double lossPerMember() { return lossPerMember; }
    public static double hidePerCollaborator() { return hidePerCollaborator; }
    public static double minimumLoss() { return minimumLoss; }
    public static double leakLoss() { return leakLoss; }
    public static double revealMembers() { return revealMembers; }
    public static double revealProgress() { return revealProgress; }
    public static double revealAssassins() { return revealAssassins; }
    public static double earlyProgress() { return earlyProgress; }
    public static double lateProgress() { return lateProgress; }
    public static double betrayalSpeed() { return betrayalSpeed; }
    public static int betrayalPenalty(Betrayal betrayal) {
        return betrayal == Betrayal.SPYMASTER ? spymasterPenalty : betrayal == Betrayal.CONFIDANT ? confidantPenalty : 0;
    }
    public static int suspicionDifficulty() { return suspicionDifficulty; }
    public static long investigation() { return millis(investigationDays); }
    public static double radius() { return radius; }
    public static int scanSeconds() { return scanSeconds; }
    public static int maxInvestigators() { return maxInvestigators; }
    public static int knownBonus() { return knownBonus; }
    public static int unknownPenalty() { return unknownPenalty; }
    public static int strongMargin() { return strongMargin; }
    public static int difficulty(PlotRole role) { return difficulty.get(role); }
    public static int huntDays() { return huntDays; }
    public static long findable() { return Math.round(findableMinutes * 60_000); }
    public static long maxHunt() { return millis(maxDays); }
    public static int maxDepth() { return maxDepth; }
    /** Worlds where a target can be found; empty means every world. */
    public static List<String> worlds() { return worlds; }
    public static long hitWindow() { return hitWindowSeconds * 1000L; }
    public static long huntWarmup() { return huntWarmupSeconds * 1000L; }
    /** From the start of the hunt, including the warm-up. */
    public static long huntMinimum() { return huntWarmup() + huntMinimumMinutes * 60_000L; }
    public static double huntAlertSecrecy() { return huntAlertSecrecy; }
    public static long areaInterval() { return areaIntervalSeconds * 1000L; }
    public static int areaSize() { return areaSize; }
    public static long sightingGap() { return sightingGapMinutes * 60_000L; }
    public static double maxSurplus() { return maxSurplus; }
    /** Extra hunt days bought by strength beyond the requirement. */
    public static int extraDays(double surplus) {
        return (int) Math.min(maxExtraDays, Math.floor((Math.min(maxSurplus, surplus) - 1) / extraDayPerSurplus + 1e-9));
    }
    public static int killerClues() { return killerClues; }
    public static int accompliceClues() { return accompliceClues; }
    public static double accompliceRadius() { return accompliceRadius; }
    public static double encounterRadius() { return encounterRadius; }
    public static long encounterGap() { return encounterMinutes * 60_000L; }

    private static long millis(double days) { return Math.round(days * DAY); }
}
