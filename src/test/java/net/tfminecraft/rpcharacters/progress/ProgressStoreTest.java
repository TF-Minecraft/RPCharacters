package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ProgressStoreTest extends ProgressFixture {
    @Test void immutableBaselineClonesPoolsWhileCharacterRevisionsStayIndependent() throws Exception {
        var a = character(); var b = character(); var template = progress(a.getId());
        template.setSkillPointsTotal(12); template.setRewardSkillPoints(3); template.setAttributePointsTotal(5);
        template.setProfessionPoints("MINING", 8); template.setClassProgress(20, 16.5);
        var baseline = new ProgressBaseline(account.getUniqueId(), List.of(a.getId(), b.getId()), template);
        assertTrue(store.readBaseline(account.getUniqueId()).isEmpty()); store.createBaseline(baseline);
        String original = Files.readString(store.baselinePath(account.getUniqueId()));
        assertThrows(FileAlreadyExistsException.class, () -> store.createBaseline(baseline));
        var read = store.readBaseline(account.getUniqueId()).orElseThrow();
        assertTrue(read.includes(a.getId().toUpperCase(Locale.ROOT))); assertFalse(read.includes(UUID.randomUUID().toString()));
        var one = read.cloneFor(a.getId()); var two = read.cloneFor(b.getId()); store.create(one); store.create(two);
        one.addSkillPoints(5); one.addAttributePoints(2); one.addProfessionPoints("mining", 1); store.replace(one);
        assertEquals(2, one.getRevision()); assertEquals(17, store.read(account.getUniqueId(), a.getId()).orElseThrow().getSkillPointsTotal());
        assertEquals(12, store.read(account.getUniqueId(), b.getId()).orElseThrow().getSkillPointsTotal());
        assertEquals(3, two.getRewardSkillPoints()); assertEquals(5, two.getAttributePointsTotal()); assertEquals(8, two.getProfessionPoints("MINING"));
        assertEquals(20, two.getClassLevel()); assertEquals(16.5, two.getClassExperience()); assertEquals("character", two.holder());
        assertEquals(original, Files.readString(store.baselinePath(account.getUniqueId())));
        assertThrows(FileAlreadyExistsException.class, () -> store.create(two));
    }
    @Test void staleWriterMissingPrimaryAndMissingBaselineNeverReinitializePlayedProgress() throws Exception {
        var id = character().getId(); var value = progress(id); store.createBaseline(new ProgressBaseline(account.getUniqueId(), List.of(id), value)); store.create(value);
        var stale = store.read(account.getUniqueId(), id).orElseThrow(); value.addSkillPoints(3); store.replace(value);
        assertThrows(IOException.class, () -> store.replace(stale)); assertEquals(0, stale.getSkillPointsTotal());
        Files.delete(store.characterPath(account.getUniqueId(), id));
        assertThrows(IOException.class, () -> store.read(account.getUniqueId(), id)); assertThrows(IOException.class, () -> store.replace(value));
        Files.delete(store.baselinePath(account.getUniqueId())); assertThrows(IOException.class, () -> store.readBaseline(account.getUniqueId()));
        assertThrows(IOException.class, () -> store.replace(progress(UUID.randomUUID().toString())));
    }
    @Test void malformedFilesAndForeignOwnersFailClosed() throws Exception {
        var id = character().getId(); var value = progress(id); store.create(value); Path file = store.characterPath(account.getUniqueId(), id);
        String good = Files.readString(file);
        for (String patch : List.of("owner: 'other'", "format: 999", "character: 'other'", "source: unknown", "revision: 0", "revision: 1.5", "revision: nope", "revision: -2")) {
            String key = patch.substring(0, patch.indexOf(':')); Files.writeString(file, good.replaceAll("(?m)^"+key+":.*$", patch)); assertThrows(IOException.class, () -> store.read(account.getUniqueId(), id), patch);
        }
        Files.writeString(file, "broken: ["); assertThrows(IOException.class, () -> store.read(account.getUniqueId(), id));
        Files.writeString(file, good.replaceAll("(?m)^revision:.*$", "revision: " + Long.MAX_VALUE));
        var max = store.read(account.getUniqueId(), id).orElseThrow(); assertThrows(IOException.class, () -> store.replace(max));
        for (String illegal : Arrays.asList("../escape", "", "1-1-1-1-1", null)) assertThrows(IOException.class, () -> store.characterPath(account.getUniqueId(), illegal));
    }
    @Test void malformedBaselineAndAllocationRecordsAreRejectedBeforeApplying() throws Exception {
        var id = character().getId(); var value = progress(id); store.createBaseline(new ProgressBaseline(account.getUniqueId(), List.of(id), value)); Path file = store.baselinePath(account.getUniqueId()); String good = Files.readString(file);
        for (Object ids : List.of("wrong", List.of(id,id), List.of("../escape"))) { var yaml = YamlConfiguration.loadConfiguration(file.toFile()); yaml.set("characters", ids); Files.writeString(file, yaml.saveToString()); assertThrows(IOException.class, () -> store.readBaseline(account.getUniqueId())); Files.writeString(file, good); }
        var yaml = new YamlConfiguration(); value.writeTo(yaml);
        for (String path : List.of("pools", "mmo", "pools.skill-points", "pools.profession-points", "mmo.level")) { var copy = new YamlConfiguration(); copy.loadFromString(yaml.saveToString()); copy.set(path, null); assertThrows(IOException.class, () -> CharacterProgress.readFrom(copy, account.getUniqueId(), id, CharacterProgress.FRESH)); }
        for (Object invalid : List.of(-1, 1.2, Double.NaN, Double.POSITIVE_INFINITY, ((long)Integer.MAX_VALUE)+1)) { var copy = new YamlConfiguration(); copy.loadFromString(yaml.saveToString()); copy.set("pools.skill-points", invalid); assertThrows(IOException.class, () -> CharacterProgress.readFrom(copy, account.getUniqueId(), id, CharacterProgress.FRESH)); }
        for (Object invalid : List.of(List.of("wrong"), List.of(Map.of("id","", "value",1)), List.of(Map.of("id","mining", "value",-1)), List.of(Map.of("id","mining", "value",1), Map.of("id","MINING", "value",2)))) { yaml.set("pools.profession-points", invalid); assertThrows(IOException.class, () -> CharacterProgress.readFrom(yaml, account.getUniqueId(), id, CharacterProgress.FRESH)); }
    }
    @Test void failedAtomicReplacementKeepsPrimaryRevisionAndRemovesItsTemporaryFile() throws Exception {
        var id=character().getId();var value=progress(id);store.create(value);Path primary=store.characterPath(account.getUniqueId(),id);String before=Files.readString(primary);
        Path previous=primary.resolveSibling(primary.getFileName()+".prev");Files.delete(previous);Files.createDirectory(previous);Files.writeString(previous.resolve("occupied"),"do not replace");
        value.addSkillPoints(9);assertThrows(IOException.class,()->store.replace(value));assertEquals(1,value.getRevision());assertEquals(before,Files.readString(primary));
        try(var files=Files.list(primary.getParent())){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith(".progress-")));}
    }
    @Test void unchangedReplacementKeepsItsRevisionAndFilesWhileChangesStillAdvanceWithPrevious() throws Exception {
        var a = character(); var value = progress(a.getId()); store.create(value); value.addSkillPoints(2); store.replace(value);
        Path primary = store.characterPath(account.getUniqueId(), a.getId()); Path previous = primary.resolveSibling(primary.getFileName() + ".prev");
        String written = Files.readString(primary); String prior = Files.readString(previous); assertEquals(2, value.getRevision());
        store.replace(value); assertEquals(2, value.getRevision()); assertEquals(written, Files.readString(primary)); assertEquals(prior, Files.readString(previous));
        var reread = store.read(account.getUniqueId(), a.getId()).orElseThrow(); store.replace(reread); assertEquals(2, reread.getRevision()); assertEquals(written, Files.readString(primary));
        assertThrows(IOException.class, () -> store.replace(progress(a.getId())), "A copy that is not the revision on disk is still refused");
        reread.addSkillPoints(1); store.replace(reread); assertEquals(3, reread.getRevision()); assertEquals(written, Files.readString(previous));
        assertEquals(3, store.read(account.getUniqueId(), a.getId()).orElseThrow().getSkillPointsTotal());
        assertThrows(IOException.class, () -> store.replace(value), "The older live copy cannot overwrite the newer revision");
    }
    @Test void mutablePoolsClampAndSaturateWithoutSharingClones() {
        var value = progress(UUID.randomUUID().toString()); value.setSkillPointsTotal(-1); value.setAttributePointsTotal(-2); value.setRewardSkillPoints(-3);
        value.addSkillPoints(0); value.addAttributePoints(-1); value.addProfessionPoints("mining",0); value.setProfessionPoints(null,3);
        value.addSkillPoints(Integer.MAX_VALUE); value.addSkillPoints(1); value.addAttributePoints(Integer.MAX_VALUE); value.addAttributePoints(1);
        value.addProfessionPoints("MINING",Integer.MAX_VALUE); value.addProfessionPoints("mining",2);
        assertEquals(Integer.MAX_VALUE,value.getSkillPointsTotal()); assertEquals(Integer.MAX_VALUE,value.getAttributePointsTotal()); assertEquals(Integer.MAX_VALUE,value.getProfessionPoints("mining")); assertEquals(0,value.getProfessionPoints(null)); assertEquals(0,value.getRewardSkillPoints());
        assertThrows(UnsupportedOperationException.class, () -> value.getProfessionPointsMap().clear()); value.clearProfessionPoints(); assertTrue(value.getProfessionPointsMap().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> value.setClassProgress(1,Double.NaN));
    }
}
