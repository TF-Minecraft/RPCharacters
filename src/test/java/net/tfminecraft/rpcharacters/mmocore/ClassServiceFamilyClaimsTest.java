package net.tfminecraft.rpcharacters.mmocore;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.experience.droptable.ExperienceItem;
import net.Indyuce.mmocore.experience.droptable.ExperienceTable;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;

class ClassServiceFamilyClaimsTest {

    static PlayerClass playerClass(String id, ExperienceTable table) {
        var result = mock(PlayerClass.class);
        when(result.getId()).thenReturn(id);
        when(result.hasExperienceTable()).thenReturn(table != null);
        when(result.getExperienceTable()).thenReturn(table);
        return result;
    }

    static ExperienceTable table(String id, ExperienceItem... items) {
        var table = mock(ExperienceTable.class);
        when(table.getId()).thenReturn(id);
        when(table.getItems()).thenReturn(List.of(items));
        return table;
    }

    @Test void aPathTakesTheHighestClaimOfItsFamilyOnTheSameTable() {
        var runes = mock(ExperienceItem.class);
        var slot = mock(ExperienceItem.class);
        var shared = table("mage_exp_table", runes, slot);
        var mage = playerClass("mage", shared);
        var evoker = playerClass("evoker", shared);
        var arcanist = playerClass("arcanist", shared);
        var otherTable = playerClass("odd", table("other_table"));
        var noTable = playerClass("plain", null);
        var mmo = mock(PlayerData.class);
        when(mmo.getClaims(mage, shared, runes)).thenReturn(1);
        when(mmo.getClaims(arcanist, shared, slot)).thenReturn(1);
        when(mmo.getClaims(evoker, shared, slot)).thenReturn(1);
        when(mmo.getClaims(otherTable, shared, runes)).thenReturn(5);
        try (var picks = mockStatic(ClassPickService.class)) {
            picks.when(() -> ClassPickService.rewardFamily(evoker)).thenReturn(List.of(mage, evoker, arcanist, otherTable, noTable));
            ClassService.shareFamilyClaims(mmo, evoker, shared);
        }
        verify(mmo).setClaims(evoker, shared, runes, 1);
        verify(mmo, never()).setClaims(eq(evoker), eq(shared), eq(slot), anyInt());
        verify(mmo, never()).setClaims(eq(evoker), eq(shared), eq(runes), eq(5));
    }

    @Test void classesOutsideAFamilyKeepTheirOwnClaims() {
        var shared = table("martial_exp_table", mock(ExperienceItem.class));
        var berserker = playerClass("berserker", shared);
        var mmo = mock(PlayerData.class);
        try (var picks = mockStatic(ClassPickService.class)) {
            picks.when(() -> ClassPickService.rewardFamily(berserker)).thenReturn(List.of());
            ClassService.shareFamilyClaims(mmo, berserker, shared);
        }
        verifyNoInteractions(mmo);
    }
}
