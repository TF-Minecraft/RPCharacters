package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.scheduler.BukkitTask;

import io.lumine.mythic.lib.gui.PluginInventory;
import io.lumine.mythic.lib.player.skillmod.SkillModifier;
import io.lumine.mythic.lib.player.skillmod.SkillModifierMap;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import net.Indyuce.mmocore.api.event.PlayerChangeClassEvent;
import net.Indyuce.mmocore.api.event.PlayerDataLoadEvent;
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
 * bound skill. MMOCore removes slot buffs by UUID on unbind, so that still clears the copy. MMOCore fires
 * no bind event, so binds are scoped right after RPCharacters' own class switch and restore, a tick
 * after skill GUI clicks, data load and class change, and by a 20-tick pass for the rest (admin
 * commands, reload).
 */
public final class SlotBuffScope implements Listener {

	private static final long PERIOD_TICKS = 20L;
	private static final long WARN_INTERVAL_MS = 60_000L;
	private static final Set<UUID> QUEUED = ConcurrentHashMap.newKeySet();

	private static BukkitTask task;
	private static long lastWarnMs = 0L;

	public static void start() {
		shutdown();
		lastWarnMs = 0L;
		task = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, SlotBuffScope::tick, PERIOD_TICKS, PERIOD_TICKS);
	}

	public static void shutdown() {
		if (task != null) {
			task.cancel();
			task = null;
		}
		QUEUED.clear();
	}

	/** Scopes the player's slot buffs now; never throws. */
	public static void scopeNow(PlayerData playerData) {
		if (task == null || playerData == null) {
			return;
		}
		try {
			scope(playerData);
		} catch (RuntimeException ex) {
			warn(playerData.getUniqueId(), ex);
		} catch (LinkageError err) {
			disable(err);
		}
	}

	// MythicLib menus (the MMOCore skill list included) cancel every click before acting on it.
	@EventHandler(priority = EventPriority.MONITOR)
	public void onInventoryClick(InventoryClickEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof PluginInventory) {
			scopeNextTick(event.getWhoClicked().getUniqueId());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDataLoad(PlayerDataLoadEvent event) {
		scopeNextTick(event.getData().getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onClassChange(PlayerChangeClassEvent event) {
		scopeNextTick(event.getData().getUniqueId());
	}

	private static void scopeNextTick(UUID id) {
		if (task == null || !QUEUED.add(id)) {
			return;
		}
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			QUEUED.remove(id);
			Player player = Bukkit.getPlayer(id);
			if (player != null && PlayerData.has(player)) {
				scopeNow(PlayerData.get(player));
			}
		});
	}

	private static void tick() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (task == null) {
				return;
			}
			if (PlayerData.has(player)) {
				scopeNow(PlayerData.get(player));
			}
		}
	}

	/** Returns how many modifiers were narrowed. */
	static int scope(PlayerData playerData) {
		// Each buffed slot's target list (by identity) to the skill bound there.
		Map<List<SkillHandler<?>>, SkillHandler<?>> wide = null;
		for (BoundSkillInfo info : playerData.getBoundSkills().values()) {
			List<SkillModifierTrigger> buffs = info.getSkillSlot().getBuffs();
			if (buffs.isEmpty()) {
				continue;
			}
			SkillHandler<?> handler = info.getClassSkill().getSkill();
			for (SkillModifierTrigger buff : buffs) {
				List<SkillHandler<?>> targets = buff.getTargetSkills();
				if (targets.size() > 1) {
					if (wide == null) {
						wide = new IdentityHashMap<>();
					}
					wide.put(targets, handler);
				}
			}
		}
		if (wide == null) {
			return 0;
		}
		SkillModifierMap map = playerData.getMMOPlayerData().getSkillModifierMap();
		List<SkillModifier> narrowed = new ArrayList<>();
		for (SkillModifier mod : map.getModifiers()) {
			// The trigger hands its own list to the modifier it registers (MMOCore registers it only when the
			// list holds the bound skill); a narrowed copy has its own list.
			SkillHandler<?> handler = wide.get(mod.getSkills());
			if (handler != null) {
				narrowed.add(narrow(mod, handler));
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

	private static void warn(UUID id, RuntimeException ex) {
		long now = System.currentTimeMillis();
		if (now - lastWarnMs < WARN_INTERVAL_MS) {
			return;
		}
		lastWarnMs = now;
		RPCharacters.plugin.getLogger().log(Level.WARNING, "Cannot scope skill slot buffs for " + id
				+ "; their slot buffs may apply to every skill", ex);
	}

	private static void disable(LinkageError err) {
		shutdown();
		RPCharacters.plugin.getLogger().log(Level.SEVERE, "MMOCore/MythicLib no longer match the slot buff API;"
				+ " skill slot buffs apply to every skill again", err);
	}
}
