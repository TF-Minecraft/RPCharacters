package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.*;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ItemPointsTest extends ProgressFixture {
    @Test void itemPointsReachEveryCharacterOnceIncludingOnesCreatedAfterADeath() throws Exception {
        var a = character(); var b = character(); enable(); service.prepare(account);
        assertFalse(ClassService.tryGrantItemSkillPoints(player, 2), "The character room has no pools");
        assertFalse(AttributePointService.tryGrantItemAttributePoints(player, 3));
        play(a);
        assertTrue(ClassService.tryGrantItemSkillPoints(player, 2)); assertTrue(AttributePointService.tryGrantItemAttributePoints(player, 3));
        assertEquals(14, a.getProgress().getSkillPointsTotal()); assertEquals(10, a.getProgress().getAttributePointsTotal());
        assertEquals(2, account.getItemSkillPoints()); assertEquals(3, account.getItemAttributePoints());
        assertEquals(12, account.getAccountSkillPointsTotal()); assertEquals(7, account.getAccountAttributePointsTotal(), "Legacy account pools are never written");
        var saved = store.read(account.getUniqueId(), a.getId()).orElseThrow();
        assertEquals(2, saved.getItemSkillPoints()); assertEquals(3, saved.getItemAttributePoints()); assertEquals(14, saved.getSkillPointsTotal());

        play(b);
        assertEquals(14, b.getProgress().getSkillPointsTotal()); assertEquals(10, b.getProgress().getAttributePointsTotal());
        verify(net.tfminecraft.rpcharacters.RPCharacters.plugin.getLogger()).info(contains("item skill and attribute points"));
        assertTrue(ClassService.tryGrantSkillPoints(player, 1)); assertTrue(AttributePointService.tryGrantAttributePoints(player, 1));
        assertEquals(2, account.getItemSkillPoints(), "Other grants stay with the character");
        assertEquals(15, b.getProgress().getSkillPointsTotal()); assertEquals(11, b.getProgress().getAttributePointsTotal());

        play(a);
        assertEquals(14, a.getProgress().getSkillPointsTotal(), "A character never receives the same item points twice");
        assertEquals(10, a.getProgress().getAttributePointsTotal());

        when(a.getStatus()).thenReturn(net.tfminecraft.rpcharacters.enums.Status.DEAD);
        var c = character(); service.ensureFresh(account, c);
        int skill = c.getProgress().getSkillPointsTotal(), attribute = c.getProgress().getAttributePointsTotal();
        play(c);
        assertEquals(skill + 2, c.getProgress().getSkillPointsTotal()); assertEquals(attribute + 3, c.getProgress().getAttributePointsTotal());
    }

    @Test void sharedProgressAddsItemPointsToTheAccountTotals() {
        character();
        assertTrue(ClassService.tryGrantItemSkillPoints(player, 2)); assertTrue(AttributePointService.tryGrantItemAttributePoints(player, 3));
        assertEquals(14, account.getAccountSkillPointsTotal()); assertEquals(10, account.getAccountAttributePointsTotal());
        assertEquals(0, account.getItemSkillPoints()); assertEquals(0, account.getItemAttributePoints());
    }

    @Test void aCharacterHoldingMoreThanItsAccountGetsNothingAndCountersSaturate() {
        var progress = progress(UUID.randomUUID().toString()); progress.setItemSkillPoints(5); progress.setItemAttributePoints(5);
        account.setItemSkillPoints(2); account.setItemAttributePoints(2);
        assertFalse(ItemPoints.catchUp(account, progress)); assertEquals(0, progress.getSkillPointsTotal()); assertEquals(5, progress.getItemSkillPoints());
        account.setItemSkillPoints(-1); account.setItemAttributePoints(-1); progress.setItemSkillPoints(-1); progress.setItemAttributePoints(-1);
        assertEquals(0, account.getItemSkillPoints()); assertEquals(0, account.getItemAttributePoints());
        assertEquals(0, progress.getItemSkillPoints()); assertEquals(0, progress.getItemAttributePoints());
        account.addItemSkillPoints(0); account.addItemAttributePoints(-1); assertEquals(0, account.getItemSkillPoints()); assertEquals(0, account.getItemAttributePoints());
        account.addItemSkillPoints(Integer.MAX_VALUE); account.addItemSkillPoints(1); account.addItemAttributePoints(Integer.MAX_VALUE); account.addItemAttributePoints(1);
        assertEquals(Integer.MAX_VALUE, account.getItemSkillPoints()); assertEquals(Integer.MAX_VALUE, account.getItemAttributePoints());
    }

    @Test void itemCountersAreSavedClonedAndOptionalInOlderFiles() throws Exception {
        var id = UUID.randomUUID().toString(); var value = progress(id); value.setItemSkillPoints(4); value.setItemAttributePoints(6);
        var copy = value.copyFor(id, CharacterProgress.BASELINE); assertEquals(4, copy.getItemSkillPoints()); assertEquals(6, copy.getItemAttributePoints());
        var yaml = new YamlConfiguration(); value.writeTo(yaml);
        var read = CharacterProgress.readFrom(yaml, account.getUniqueId(), id, CharacterProgress.FRESH);
        assertEquals(4, read.getItemSkillPoints()); assertEquals(6, read.getItemAttributePoints());
        yaml.set("pools.item-skill-points", null); yaml.set("pools.item-attribute-points", null);
        var older = CharacterProgress.readFrom(yaml, account.getUniqueId(), id, CharacterProgress.FRESH);
        assertEquals(0, older.getItemSkillPoints()); assertEquals(0, older.getItemAttributePoints());
        for (String key : List.of("pools.item-skill-points", "pools.item-attribute-points")) {
            var broken = new YamlConfiguration(); value.writeTo(broken); broken.set(key, 1.5);
            assertThrows(IOException.class, () -> CharacterProgress.readFrom(broken, account.getUniqueId(), id, CharacterProgress.FRESH), key);
        }
    }

}
