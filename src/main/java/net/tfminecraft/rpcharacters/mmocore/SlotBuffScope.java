package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import io.lumine.mythic.lib.player.skillmod.SkillModifier;
import io.lumine.mythic.lib.player.skillmod.SkillModifierMap;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.quest.trigger.SkillModifierTrigger;
import net.Indyuce.mmocore.skill.binding.BoundSkillInfo;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Keeps a skill slot's {@code skill-buffs} on the skill bound in that slot. MMOCore 1.13.1 registers
 * each slot buff with the target list of its {@code formula} (every skill by default), and MythicLib's
 * {@code SkillModifier.register(data, handler)} ignores the handler, so a buffed slot changed every skill
 * the player has: a martial's Slot III -25% also hit the Slot I passive.
 * <p>
 * Each such modifier is replaced in the player's map by a copy with the same UUID that targets only the
 * bound skill. MMOCore removes slot buffs by UUID on unbind, so that still clears the copy. MMOCore has no
 * bind event, so a timer looks for new slot buffs; a fresh bind keeps the wide buff until the next pass.
 */
public final class SlotBuffScope {

	private static final long PERIOD_TICKS = 20L;

	private static BukkitTask task;
	private static boolean warned;

	private SlotBuffScope() {
	}

	public static void start() {
		shutdown();
		task = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, SlotBuffScope::tick, PERIOD_TICKS, PERIOD_TICKS);
	}

	public static void shutdown() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	private static void tick() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			try {
				if (PlayerData.has(player)) {
					scope(PlayerData.get(player));
				}
			} catch (RuntimeException ex) {
				// One player's data must not stop the others.
				warnOnce(player, ex);
			}
		}
	}

	/** Returns how many modifiers were narrowed. */
	static int scope(PlayerData playerData) {
		Collection<BoundSkillInfo> bound = playerData.getBoundSkills().values();
		if (bound.isEmpty()) {
			return 0;
		}
		SkillModifierMap map = null;
		List<SkillModifier> narrowed = new ArrayList<>();
		for (BoundSkillInfo info : bound) {
			List<SkillModifierTrigger> buffs = info.getSkillSlot().getBuffs();
			if (buffs.isEmpty()) {
				continue;
			}
			if (map == null) {
				map = playerData.getMMOPlayerData().getSkillModifierMap();
			}
			SkillHandler<?> handler = info.getClassSkill().getSkill();
			for (SkillModifierTrigger buff : buffs) {
				List<SkillHandler<?>> targets = buff.getTargetSkills();
				for (SkillModifier mod : map.getModifiers()) {
					// The trigger hands its own list to the modifier it registers; a narrowed copy has its own.
					if (mod.getSkills() == targets && targets.size() > 1 && targets.contains(handler)) {
						narrowed.add(narrow(mod, handler));
					}
				}
			}
		}
		for (SkillModifier mod : narrowed) {
			map.addModifier(mod);
		}
		return narrowed.size();
	}

	static SkillModifier narrow(SkillModifier mod, SkillHandler<?> handler) {
		return new SkillModifier(mod.getUniqueId(), mod.getKey(), mod.getParameter(), List.of(handler),
				mod.getValue(), mod.getType(), mod.getSlot(), mod.getSource());
	}

	private static void warnOnce(Player player, RuntimeException ex) {
		if (warned) {
			return;
		}
		warned = true;
		RPCharacters.plugin.getLogger().warning("Cannot scope skill slot buffs for " + player.getName()
				+ "; their slot buffs may apply to every skill: " + ex);
	}
}
