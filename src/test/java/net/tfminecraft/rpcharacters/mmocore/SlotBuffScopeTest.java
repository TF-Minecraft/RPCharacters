package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.player.modifier.ModifierType;
import io.lumine.mythic.lib.player.skillmod.SkillModifier;
import io.lumine.mythic.lib.player.skillmod.SkillModifierMap;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.quest.trigger.SkillModifierTrigger;
import net.Indyuce.mmocore.skill.ClassSkill;
import net.Indyuce.mmocore.skill.binding.BoundSkillInfo;
import net.Indyuce.mmocore.skill.binding.SkillSlot;

class SlotBuffScopeTest {

	private static final String SLOT_KEY = SkillSlot.SKILL_MODIFIER_TRIGGER_KEY;

	private final SkillHandler<?> first = mock(SkillHandler.class);
	private final SkillHandler<?> second = mock(SkillHandler.class);
	private final List<SkillHandler<?>> everySkill = List.of(first, second);
	private final MMOPlayerData mmoData = mock(MMOPlayerData.class);
	private final SkillModifierMap map = new SkillModifierMap(mmoData);
	private final PlayerData playerData = mock(PlayerData.class);
	private final Map<Integer, BoundSkillInfo> bound = new LinkedHashMap<>();

	SlotBuffScopeTest() {
		when(mmoData.getSkillModifierMap()).thenReturn(map);
		when(playerData.getMMOPlayerData()).thenReturn(mmoData);
		when(playerData.getBoundSkills()).thenReturn(bound);
	}

	@Test
	void slotBuffOnlyChangesTheSkillInThatSlot() {
		bind(1, first);
		SkillModifier damage = new SkillModifier(SLOT_KEY, "damage", everySkill, -25, ModifierType.RELATIVE);
		bind(3, second, trigger(damage));
		map.addModifier(damage);

		// What MMOCore leaves behind: the slot III buff also hits the slot I skill.
		assertEquals(7.5, map.calculateValue(first, 10, "damage"), 1e-9);

		assertEquals(1, SlotBuffScope.scope(playerData));
		assertEquals(10, map.calculateValue(first, 10, "damage"), 1e-9);
		assertEquals(7.5, map.calculateValue(second, 10, "damage"), 1e-9);

		// Already narrowed: nothing more to do.
		assertEquals(0, SlotBuffScope.scope(playerData));

		// MMOCore's unbind removes the buff by its UUID, which the copy keeps.
		damage.unregister(mmoData);
		assertEquals(10, map.calculateValue(second, 10, "damage"), 1e-9);
	}

	@Test
	void leavesModifiersFromOtherSourcesAlone() {
		SkillModifier fromTree = new SkillModifier("mmocore_trigger", "damage", everySkill, -25, ModifierType.RELATIVE);
		map.addModifier(fromTree);
		bind(1, first);
		// The slot's own buff has its own target list and is not registered here.
		bind(3, second, trigger(new SkillModifier(SLOT_KEY, "damage", List.of(first, second), -25,
				ModifierType.RELATIVE)));

		assertEquals(0, SlotBuffScope.scope(playerData));
		assertEquals(7.5, map.calculateValue(first, 10, "damage"), 1e-9);
	}

	@Test
	void leavesSingleSkillBuffsAlone() {
		SkillModifier onlySecond = new SkillModifier(SLOT_KEY, "damage", List.of(second), -25,
				ModifierType.RELATIVE);
		bind(3, second, trigger(onlySecond));
		map.addModifier(onlySecond);

		assertEquals(0, SlotBuffScope.scope(playerData));
		assertEquals(7.5, map.calculateValue(second, 10, "damage"), 1e-9);
	}

	@Test
	void eachBuffedSlotKeepsItsBuffOnItsOwnSkill() {
		SkillModifier slot2 = new SkillModifier(SLOT_KEY, "damage", List.of(first, second), -10,
				ModifierType.RELATIVE);
		SkillModifier slot3 = new SkillModifier(SLOT_KEY, "damage", List.of(first, second), -25,
				ModifierType.RELATIVE);
		bind(2, first, trigger(slot2));
		bind(3, second, trigger(slot3));
		map.addModifier(slot2);
		map.addModifier(slot3);

		assertEquals(2, SlotBuffScope.scope(playerData));
		assertEquals(9, map.calculateValue(first, 10, "damage"), 1e-9);
		assertEquals(7.5, map.calculateValue(second, 10, "damage"), 1e-9);
	}

	private SkillModifierTrigger trigger(SkillModifier mod) {
		SkillModifierTrigger trigger = mock(SkillModifierTrigger.class);
		when(trigger.getTargetSkills()).thenReturn(mod.getSkills());
		return trigger;
	}

	private void bind(int slot, SkillHandler<?> handler, SkillModifierTrigger... buffs) {
		SkillSlot skillSlot = mock(SkillSlot.class);
		when(skillSlot.getBuffs()).thenReturn(List.of(buffs));
		ClassSkill classSkill = mock(ClassSkill.class);
		when(classSkill.getSkill()).thenAnswer(ignored -> handler);
		BoundSkillInfo info = mock(BoundSkillInfo.class);
		when(info.getSkillSlot()).thenReturn(skillSlot);
		when(info.getClassSkill()).thenReturn(classSkill);
		bound.put(slot, info);
	}
}
