package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

public final class PvpCommand implements CommandExecutor, TabCompleter, Listener {

	public static final String COMMAND = "pvp";

	private static final String CONFIRM = "confirm";

	private final Map<PvpSituation, List<BukkitTask>> tasksBySituation = new IdentityHashMap<>();
	/** Players who ran /pvp lethal and may still confirm it, with the character they ran it for. */
	private final Map<UUID, LethalRequest> lethalRequests = new ConcurrentHashMap<>();

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
				if (args.length > 1 && CONFIRM.equalsIgnoreCase(args[1])) {
					confirmLethal(player);
				} else {
					requestLethal(player);
				}
				return true;
			}
			case "nonlethal" -> {
				setNonlethal(player);
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

	/** Lethal is never switched on directly: the player reads the warning and confirms it. */
	private void requestLethal(Player player) {
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			RPTexts.send(player, PvpLoader.getNoCharacter());
			return;
		}
		if (character.isPvpLethal()) {
			RPTexts.send(player, PvpLoader.getAlreadyLethal());
			return;
		}
		lethalRequests.put(player.getUniqueId(), new LethalRequest(character.getId(),
				System.currentTimeMillis() + PvpLoader.getLethalConfirmMs()));
		for (String line : PvpLoader.getLethalWarning().split("\\R|\\\\n")) {
			RPTexts.send(player, line);
		}
		String seconds = String.valueOf(PvpLoader.getLethalConfirmSeconds());
		// Only the button runs the command; children would inherit its click.
		player.sendMessage(Component.empty()
				.append(legacy(PvpLoader.getLethalConfirmButton())
						.clickEvent(ClickEvent.runCommand("/" + COMMAND + " lethal " + CONFIRM))
						.hoverEvent(HoverEvent.showText(legacy(PvpLoader.getLethalConfirmHover()))))
				.append(Component.space())
				.append(legacy(PvpLoader.getLethalConfirmPrompt().replace("{seconds}", seconds))));
	}

	private void confirmLethal(Player player) {
		LethalRequest request = lethalRequests.remove(player.getUniqueId());
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			RPTexts.send(player, PvpLoader.getNoCharacter());
			return;
		}
		// The confirmation only covers the character the warning was shown for.
		if (request == null || System.currentTimeMillis() >= request.expiresAtMs()
				|| !request.characterId().equals(character.getId())) {
			RPTexts.send(player, PvpLoader.getLethalConfirmNone()
					.replace("{seconds}", String.valueOf(PvpLoader.getLethalConfirmSeconds())));
			return;
		}
		character.setPvpLethal(true);
		RPTexts.send(player, PvpLoader.getLethal());
	}

	private void setNonlethal(Player player) {
		lethalRequests.remove(player.getUniqueId());
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			RPTexts.send(player, PvpLoader.getNoCharacter());
			return;
		}
		character.setPvpLethal(false);
		RPTexts.send(player, PvpLoader.getNonlethal());
	}

	private static RPCharacter activeCharacter(Player player) {
		PlayerData pd = PlayerManager.get(player);
		return pd != null && pd.hasActiveCharacter() ? pd.getActiveCharacter() : null;
	}

	private static Component legacy(String raw) {
		return LegacyComponentSerializer.legacySection().deserialize(RPTexts.formatDisplay(raw));
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
		Duels.endFor(targets, Duels.Ending.FIGHT);
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
				int cooldownHours = PvpLoader.getSameTargetCooldownHours();
				String cooldown = cooldownHours <= 0 ? "no extra time"
						: cooldownHours == 1 ? "1 hour" : cooldownHours + " hours";
				TutorialService.show(online, TutorialService.PVP_STRIKES, Map.of(
						"seconds", String.valueOf(PvpLoader.getDecisionSeconds()),
						"time", PvpStrikeService.formatDuration(PvpLoader.getDecisionSeconds()),
						"hours", String.valueOf(cooldownHours),
						"cooldown", cooldown));
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
			for (UUID id : situation.participants()) {
				if (!PvpSituations.remainsActiveElsewhere(id, situation)) {
					PvpStartSessions.end(id);
				}
			}
		}
	}

	private void schedule(PvpSituation situation, BukkitTask task) {
		List<BukkitTask> tasks = tasksBySituation.get(situation);
		tasks.add(task);
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
		Player player = event.getPlayer();
		PvpStartSessions.end(player.getUniqueId());
		lethalRequests.remove(player.getUniqueId());
		PvpStrikeService.handleQuit(player);
	}

	/** Every character starts nonlethal when it becomes active: on login, after a restart and on a switch. */
	@EventHandler
	public void onSessionStart(CharacterSessionStartEvent event) {
		Player player = event.getPlayer();
		lethalRequests.remove(player.getUniqueId());
		RPCharacter character = activeCharacter(player);
		if (character != null) {
			character.setPvpLethal(false);
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 2 && "lethal".equalsIgnoreCase(args[0])
				&& CONFIRM.startsWith(args[1].toLowerCase(Locale.ROOT))) {
			return List.of(CONFIRM);
		}
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

	private record LethalRequest(String characterId, long expiresAtMs) {
	}
}
