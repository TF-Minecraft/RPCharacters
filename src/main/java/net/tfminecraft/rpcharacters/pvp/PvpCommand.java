package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.tlibs.armour.ArmorEquipEvent;

import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PvpCommand implements CommandExecutor, TabCompleter, Listener {

	public static final String COMMAND = "pvp";

	private final Map<UUID, Long> armorBlockUntil = new HashMap<>();
	private final Map<PvpSituation, List<BukkitTask>> tasksBySituation = new IdentityHashMap<>();

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!COMMAND.equalsIgnoreCase(command.getName())) {
			return false;
		}
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, PvpLoader.getPlayersOnly());
			return true;
		}
		if (args.length == 0) {
			sendUsage(player);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "start" -> {
				startWarning(player);
				return true;
			}
			case "end" -> {
				endSituation(player);
				return true;
			}
			case "lethal" -> {
				setLethal(player, true);
				return true;
			}
			case "nonlethal" -> {
				setLethal(player, false);
				return true;
			}
			default -> {
				sendUsage(player);
				return true;
			}
		}
	}

	private void sendUsage(Player player) {
		RPTexts.send(player, PvpLoader.getUsage());
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return;
		}
		String mode = pd.getActiveCharacter().isPvpLethal() ? "lethal" : "nonlethal";
		RPTexts.send(player, PvpLoader.getCurrent().replace("{mode}", mode));
	}

	private void setLethal(Player player, boolean lethal) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, PvpLoader.getNoCharacter());
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		character.setPvpLethal(lethal);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, lethal ? PvpLoader.getLethal() : PvpLoader.getNonlethal());
	}

	private void startWarning(Player player) {
		Location origin = player.getLocation();
		double radius = PvpLoader.getStartRadius();
		double radiusSq = radius * radius;
		List<UUID> targets = new ArrayList<>();
		for (Player nearby : player.getWorld().getPlayers()) {
			if (nearby.getLocation().distanceSquared(origin) <= radiusSq) {
				targets.add(nearby.getUniqueId());
			}
		}
		long expiry = System.currentTimeMillis() + PvpLoader.getStartWarnSeconds() * 1000L;
		for (UUID id : targets) {
			Long existing = armorBlockUntil.get(id);
			if (existing == null || expiry > existing) {
				armorBlockUntil.put(id, expiry);
			}
		}
		PvpSituation previous = PvpSituations.openFor(player.getUniqueId());
		if (previous != null) {
			finish(previous);
		}
		PvpSituation situation = new PvpSituation(player.getUniqueId(), targets);
		PvpSituations.track(situation);
		tasksBySituation.put(situation, new ArrayList<>());

		String warning = PvpLoader.getStartWarning()
				.replace("{seconds}", String.valueOf(PvpLoader.getStartWarnSeconds()));
		broadcast(targets, warning);
		int situationMinutes = PvpLoader.getStartActiveMinutes();
		if (situationMinutes > 0) {
			broadcast(targets, PvpLoader.getSituationDuration()
					.replace("{minutes}", String.valueOf(situationMinutes)));
		}
		for (UUID id : targets) {
			Player online = Bukkit.getPlayer(id);
			if (online != null) {
				TutorialService.show(online, TutorialService.PVP_STRIKES, Map.of(
						"seconds", String.valueOf(PvpLoader.getDecisionSeconds())));
			}
		}

		int warnSeconds = PvpLoader.getStartWarnSeconds();
		int from = Math.min(PvpLoader.getStartCountdownFrom(), warnSeconds);
		for (int count = from; count >= 1; count--) {
			int delaySeconds = warnSeconds - count;
			int n = count;
			schedule(situation, new BukkitRunnable() {
				@Override
				public void run() {
					if (!situation.isOpen()) {
						return;
					}
					broadcast(targets, PvpLoader.getCountdown().replace("{count}", String.valueOf(n)));
				}
			}.runTaskLater(RPCharacters.plugin, delaySeconds * 20L));
		}

		schedule(situation, new BukkitRunnable() {
			@Override
			public void run() {
				if (!situation.isOpen()) {
					return;
				}
				broadcastStartedTitle(targets, situation);
			}
		}.runTaskLater(RPCharacters.plugin, warnSeconds * 20L));
	}

	private void endSituation(Player player) {
		PvpSituation situation = PvpSituations.openFor(player.getUniqueId());
		if (situation == null) {
			RPTexts.send(player, PvpLoader.getNoSituation());
			return;
		}
		boolean started = situation.hasStarted();
		List<UUID> participants = List.copyOf(situation.participants());
		finish(situation);
		if (!started) {
			broadcast(participants, PvpLoader.getStartCancelled());
		}
	}

	/** Drop scheduled warnings. After the fight has started, title anyone who has not died. */
	private void finish(PvpSituation situation) {
		List<UUID> recipients = situation.close();
		List<BukkitTask> tasks = tasksBySituation.remove(situation);
		if (tasks != null) {
			for (BukkitTask task : tasks) {
				task.cancel();
			}
		}
		PvpSituations.untrack(situation);
		if (recipients.isEmpty()) {
			return;
		}
		String title = PvpLoader.getEndedTitle();
		String subtitle = PvpLoader.getEndedSubtitle();
		for (UUID id : recipients) {
			Player online = Bukkit.getPlayer(id);
			if (online != null && online.isOnline()) {
				RPTexts.longTitle(online, title, subtitle);
			}
			if (!PvpSituations.remainsActiveElsewhere(id, situation)) {
				PvpStartSessions.end(id);
			}
		}
	}

	public void shutdown() {
		for (PvpSituation situation : new ArrayList<>(tasksBySituation.keySet())) {
			situation.close();
			List<BukkitTask> tasks = tasksBySituation.remove(situation);
			if (tasks != null) {
				for (BukkitTask task : tasks) {
					task.cancel();
				}
			}
			PvpSituations.untrack(situation);
		}
	}

	private void schedule(PvpSituation situation, BukkitTask task) {
		List<BukkitTask> tasks = tasksBySituation.get(situation);
		if (tasks == null || !situation.isOpen()) {
			task.cancel();
			return;
		}
		tasks.add(task);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onArmorEquip(ArmorEquipEvent event) {
		Player player = event.getPlayer();
		if (player == null) {
			return;
		}
		UUID id = player.getUniqueId();
		Long until = armorBlockUntil.get(id);
		if (until == null) {
			return;
		}
		if (System.currentTimeMillis() >= until) {
			armorBlockUntil.remove(id);
			return;
		}
		if (event.getNewArmorPiece() == null) {
			return;
		}
		event.setCancelled(true);
	}

	private void broadcast(List<UUID> targets, String raw) {
		for (UUID id : targets) {
			Player online = Bukkit.getPlayer(id);
			if (online != null && online.isOnline()) {
				RPTexts.send(online, raw);
			}
		}
	}

	private void broadcastStartedTitle(List<UUID> targets, PvpSituation situation) {
		String title = PvpLoader.getStartedTitle();
		List<UUID> started = new ArrayList<>();
		for (UUID id : targets) {
			Player online = Bukkit.getPlayer(id);
			if (online != null && online.isOnline()) {
				RPTexts.title(online, title, " ");
				started.add(id);
			}
		}
		situation.markStarted();
		PvpStartSessions.begin(started, System.currentTimeMillis(), PvpLoader.getStartActiveMs());
		long activeMs = PvpLoader.getStartActiveMs();
		if (activeMs <= 0L) {
			return;
		}
		schedule(situation, new BukkitRunnable() {
			@Override
			public void run() {
				if (situation.isOpen()) {
					finish(situation);
				}
			}
		}.runTaskLater(RPCharacters.plugin, activeMs / 50L)); // 50 ms per tick
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		PvpSituations.markDeath(event.getEntity().getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PvpStartSessions.end(event.getPlayer().getUniqueId());
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			for (String opt : new String[] { "start", "end", "lethal", "nonlethal" }) {
				if (opt.startsWith(prefix)) {
					out.add(opt);
				}
			}
			return out;
		}
		return List.of();
	}
}
