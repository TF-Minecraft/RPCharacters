package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlotConfigTest {
    @TempDir Path directory;

    @AfterEach
    void restore() { PlotConfig.load(new YamlConfiguration()); }

    @Test
    void bundledDefaultsMatchTheDocumentedRules() {
        PlotConfig.load(new YamlConfiguration());
        assertEquals("rpchar.plots.staff", PlotConfig.staffPermission());
        assertEquals(7 * PlotConfig.DAY, PlotConfig.requestExpiry());
        assertEquals(7 * PlotConfig.DAY, PlotConfig.recruiting());
        assertEquals(7 * PlotConfig.DAY, PlotConfig.retargetCooldown());
        assertEquals(8, PlotConfig.inviteRadius());
        assertEquals(600_000, PlotConfig.inviteDuration());
        assertEquals(2, PlotConfig.maxAssassins());
        assertEquals(2, PlotConfig.maxCollaborators());
        assertEquals(4, PlotConfig.maxMembers());
        assertEquals(30, PlotConfig.noticeLimit());
        assertEquals(1.25, PlotConfig.rollMultiplier());
        assertEquals(75, PlotConfig.luckSpread());
        assertEquals(3, PlotConfig.luckDraws());
        assertEquals(86_400_000, PlotConfig.approachCooldown());
        assertEquals(15, PlotConfig.offenseBonus(PlotApproach.OFFENSIVE));
        assertEquals(0, PlotConfig.defenseBonus(PlotApproach.OFFENSIVE));
        assertEquals(8, PlotConfig.defenseBonus(PlotApproach.NEUTRAL));
        assertEquals(.5, PlotConfig.aloneShare());
        assertEquals(.75, PlotConfig.confidantShare());
        assertEquals(1, PlotConfig.spymasterShare());
        assertEquals(86_400_000, PlotConfig.confidantCooldown());
        assertEquals(3 * PlotConfig.DAY, PlotConfig.inactivity());
        assertEquals(7, PlotConfig.buildDays());
        assertEquals(7 * PlotConfig.DAY, PlotConfig.ready());
        assertEquals(.5, PlotConfig.luckMin());
        assertEquals(1.5, PlotConfig.luckMax());
        assertEquals(.5, PlotConfig.progressPerCollaborator());
        assertEquals(.09, PlotConfig.strengthWeight(PlotRole.MASTER, null));
        assertEquals(.055, PlotConfig.strengthWeight(PlotRole.ASSASSIN, null));
        assertEquals(.13, PlotConfig.strengthWeight(PlotRole.COLLABORATOR, CollaboratorTask.STRENGTH));
        assertEquals(.03, PlotConfig.strengthWeight(PlotRole.COLLABORATOR, CollaboratorTask.HIDE));
        assertEquals(0, PlotConfig.strengthWeight(PlotRole.UNASSIGNED, null));
        assertEquals(20, PlotConfig.requiredBase());
        assertEquals(1.3, PlotConfig.requiredPerProtection());
        assertEquals(1.5, PlotConfig.dailyLoss());
        assertEquals(1.5, PlotConfig.lossPerMember());
        assertEquals(6, PlotConfig.hidePerCollaborator());
        assertEquals(.5, PlotConfig.minimumLoss());
        assertEquals(3, PlotConfig.leakLoss());
        assertEquals(60, PlotConfig.revealMembers());
        assertEquals(40, PlotConfig.revealProgress());
        assertEquals(20, PlotConfig.revealAssassins());
        assertEquals(34, PlotConfig.earlyProgress());
        assertEquals(67, PlotConfig.lateProgress());
        assertEquals(2, PlotConfig.betrayalSpeed());
        assertEquals(40, PlotConfig.betrayalPenalty(Betrayal.SPYMASTER));
        assertEquals(20, PlotConfig.betrayalPenalty(Betrayal.CONFIDANT));
        assertEquals(0, PlotConfig.betrayalPenalty(Betrayal.NONE));
        assertEquals(20, PlotConfig.suspicionDifficulty());
        assertEquals(3 * PlotConfig.DAY, PlotConfig.investigation());
        assertEquals(6, PlotConfig.radius());
        assertEquals(5, PlotConfig.scanSeconds());
        assertEquals(2, PlotConfig.maxInvestigators());
        assertEquals(15, PlotConfig.knownBonus());
        assertEquals(15, PlotConfig.unknownPenalty());
        assertEquals(40, PlotConfig.strongMargin());
        assertEquals(30, PlotConfig.difficulty(PlotRole.MASTER));
        assertEquals(10, PlotConfig.difficulty(PlotRole.UNASSIGNED));
        assertEquals(0, PlotConfig.difficulty(PlotRole.INFORMED));
        assertEquals(4, PlotConfig.huntDays());
        assertEquals(30 * 60_000, PlotConfig.findable());
        assertEquals(21 * PlotConfig.DAY, PlotConfig.maxHunt());
        assertEquals(16, PlotConfig.maxDepth());
        assertEquals(List.of(), PlotConfig.worlds());
        assertEquals(120_000, PlotConfig.hitWindow());
        assertEquals(60_000, PlotConfig.huntWarmup());
        assertEquals(660_000, PlotConfig.huntMinimum());
        assertEquals(40, PlotConfig.huntAlertSecrecy());
        assertEquals(120_000, PlotConfig.areaInterval());
        assertEquals(250, PlotConfig.areaSize());
        assertEquals(15 * 60_000, PlotConfig.sightingGap());
        assertEquals(2, PlotConfig.maxSurplus());
        assertEquals(0, PlotConfig.extraDays(1));
        assertEquals(1, PlotConfig.extraDays(1.5));
        assertEquals(2, PlotConfig.extraDays(9));
        assertEquals(2, PlotConfig.killerClues());
        assertEquals(1, PlotConfig.accompliceClues());
        assertEquals(16, PlotConfig.accompliceRadius());
        assertEquals(16, PlotConfig.encounterRadius());
        assertEquals(600_000, PlotConfig.encounterGap());
        assertEquals("&7&oSomething about Ana unsettles you.", PlotConfig.hint(PlotRole.ASSASSIN, false, "Ana"));
    }

    @Test
    void invalidValuesFallBackAndCustomValuesApply() {
        var config = new YamlConfiguration();
        config.set("staff-permission", " ");
        config.set("invite-radius", 0);
        config.set("building.days", Double.NaN);
        config.set("building.luck-min", 2.0);
        config.set("building.luck-max", 1.0);
        config.set("building.reveal.early-progress", 50);
        config.set("building.reveal.late-progress", 20);
        config.set("roles.max-assassins", 3);
        config.set("approach.offensive.offense", 30);
        config.set("execution.findable.worlds", List.of("TFMC_Map"));
        config.set("investigation.hints.master.strong", "{name} knows.");
        PlotConfig.load(config);
        assertEquals("rpchar.plots.staff", PlotConfig.staffPermission());
        assertEquals(8, PlotConfig.inviteRadius());
        assertEquals(7, PlotConfig.buildDays());
        assertEquals(2, PlotConfig.luckMax());
        assertEquals(50, PlotConfig.lateProgress());
        assertEquals(5, PlotConfig.maxMembers());
        assertEquals(30, PlotConfig.offenseBonus(PlotApproach.OFFENSIVE));
        assertEquals(List.of("TFMC_Map"), PlotConfig.worlds());
        assertEquals("Ana knows.", PlotConfig.hint(PlotRole.MASTER, true, "Ana"));
        config.set("staff-permission", "custom.node");
        PlotConfig.load(config);
        assertEquals("custom.node", PlotConfig.staffPermission());
    }

    @Test
    void aMissingHintUsesTheBundledTextOrAPlainFallback() {
        var config = new YamlConfiguration();
        config.set("investigation.hints", null);
        PlotConfig.load(config);
        assertEquals("&7&oAna is acting strangely around you.", PlotConfig.hint(PlotRole.COLLABORATOR, false, "Ana"));
        assertEquals("&7&oAna is hiding something, and it concerns you.", PlotConfig.hint(PlotRole.UNASSIGNED, true, "Ana"));
    }

    @Test
    void theFileGainsMissingKeysWithoutLosingItsOwnValues() throws Exception {
        var file = directory.resolve("plots.yml").toFile();
        PlotConfig.load(file);
        assertTrue(Files.readString(file.toPath()).contains("hunt-days: 4"));
        Files.writeString(file.toPath(), "invite-radius: 12\n");
        PlotConfig.load(file);
        assertEquals(12, PlotConfig.inviteRadius());
        String saved = Files.readString(file.toPath());
        assertTrue(saved.contains("invite-radius: 12") && saved.contains("max-surplus: 2.0"));
        long modified = file.lastModified();
        PlotConfig.load(file);
        assertEquals(modified, file.lastModified(), "nothing to add, nothing written");
        Files.writeString(file.toPath(), "bad: [unterminated");
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () -> PlotConfig.load(file));
    }
}
