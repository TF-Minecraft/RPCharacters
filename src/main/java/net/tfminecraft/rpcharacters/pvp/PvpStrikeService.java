package net.tfminecraft.rpcharacters.pvp;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.evilrp.StrikeOutcome;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Strikes from {@code /pvp start} fights. When a tagged player is killed or knocked out by
 * another player, the killer chooses to spare them or strike them. The strike is labelled
 * Kill when it would kill: the character's last strike, or any strike during an evil RP
 * session. Running out of time spares them.
 */
public final class PvpStrikeService {

	private static final String VERDICT_FILE = "pending-strikes.yml";
	/** A Kill on a living victim kills them next tick; the grave listener reads this in between. */
	private static final long EXECUTION_TTL_MS = 10_000L;
	private static final int JOIN_DELAY_TICKS = 60;
	private static final int JOIN_RETRIES = 10;

	private static final Map<UUID, Decision> pending = new ConcurrentHashMap<>();
	private static final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
	/** Strikes chosen while the victim was offline, applied when they next join. */
	private static final Map<UUID, Decision> verdicts = new ConcurrentHashMap<>();
	private static BukkitTask tickTask;

	private PvpStrikeService() {
	}

	public static void start() {
		shutdown();
		loadVerdicts();
		tickTask = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, PvpStrikeService::tick, 20L, 20L);
	}

	/** Undecided choices lapse into spares, the same as running out of time. */
	public static void shutdown() {
		if (tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		pending.clear();
		executions.clear();
	}

	/** Deaths and knockouts only count in a PvP start, outside battles, when another player did it. */
	static boolean counts(boolean inPvpStart, boolean inBattle, UUID victimId, UUID killerId) {
		return inPvpStart && !inBattle && killerId != null && !killerId.equals(victimId);
	}

	/** A knockout ends the victim's PvP start and, if it counts, asks the attacker what to do. */
	public static void handleKnockout(Player victim, Player attacker) {
		boolean inPvpStart = PvpStartSessions.consume(victim.getUniqueId(), System.currentTimeMillis());
		if (!counts(inPvpStart, PermadeathBattleExemption.isInStartedBattle(victim), victim.getUniqueId(),
				attacker != null ? attacker.getUniqueId() : null)) {
			return;
		}
		openDecision(victim, attacker, false);
	}

	/**
	 * Called from the death event after the battle check. Returns true when a killer's decision
	 * covers this death, so the permadeath-zone roll is skipped. Deaths without a player killer
	 * are an automatic spare.
	 */
	public static boolean handleDeath(Player victim, boolean inPvpStart) {
		UUID victimId = victim.getUniqueId();
		Decision downed = pending.get(victimId);
		if (downed != null) {
			// Killed while down: the killer still decides, but a Kill must not kill them again.
			pending.put(victimId, downed.afterDeath());
			return true;
		}
		Player killer = victim.getKiller();
		if (!counts(inPvpStart, false, victimId, killer != null ? killer.getUniqueId() : null)) {
			return false;
		}
		return openDecision(victim, killer, true);
	}

	public static boolean hasPendingDecision(Player player) {
		return player != null && (pending.containsKey(player.getUniqueId()) || verdicts.containsKey(player.getUniqueId()));
	}

	/** How the grave listener should treat this death. Consumes a pending Kill. */
	public static GraveContext graveContext(Player victim, boolean inPvpStart) {
		UUID victimId = victim.getUniqueId();
		Execution execution = executions.remove(victimId);
		if (execution != null) {
			return new GraveContext(true, execution.evil, execution.killerId);
		}
		Decision downed = pending.get(victimId);
		if (downed != null) {
			return new GraveContext(true, downed.evil, null);
		}
		boolean evil = inPvpStart && !PermadeathBattleExemption.isInStartedBattle(victim)
				&& EvilRpService.isInSession(victim);
		return new GraveContext(inPvpStart, evil, null);
	}

	/** The killer's choice. {@code strike} false spares the victim. */
	public static boolean choose(Player killer, UUID victimId, boolean strike) {
		Decision decision = victimId != null ? pending.get(victimId) : null;
		if (decision == null || !decision.killerId.equals(killer.getUniqueId())) {
			RPTexts.send(killer, RPTexts.ERROR + "There's no one waiting on your decision right now.");
			return false;
		}
		pending.remove(victimId);
		Player victim = Bukkit.getPlayer(victimId);
		if (!strike) {
			RPTexts.send(killer, RPTexts.SUCCESS + "You spared them.");
			if (victim != null) {
				RPTexts.send(victim, RPTexts.SUCCESS + "You were spared.");
			}
			return true;
		}
		if (victim == null || !victim.isOnline() || PlayerManager.get(victim) == null) {
			verdicts.put(victimId, decision);
			saveVerdicts();
			RPTexts.send(killer, RPTexts.WARN + "They're offline. It lands when they return.");
			return true;
		}
		execute(victim, decision, killer);
		return true;
	}

	/** Applies a strike or Kill chosen while the victim was offline. */
	public static void handleJoin(Player player) {
		if (!verdicts.containsKey(player.getUniqueId())) {
			return;
		}
		applyVerdictLater(player, JOIN_RETRIES);
	}

	private static void applyVerdictLater(Player player, int retriesLeft) {
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (!player.isOnline()) {
				return;
			}
			if (PlayerManager.get(player) == null && retriesLeft > 0) {
				applyVerdictLater(player, retriesLeft - 1);
				return;
			}
			Decision verdict = verdicts.remove(player.getUniqueId());
			if (verdict == null) {
				return;
			}
			saveVerdicts();
			execute(player, verdict, Bukkit.getPlayer(verdict.killerId));
		}, JOIN_DELAY_TICKS);
	}

	private static boolean openDecision(Player victim, Player killer, boolean died) {
		RPCharacter character = activeCharacter(victim);
		if (character == null) {
			return false;
		}
		if (EvilRpService.applyDecay(character, System.currentTimeMillis())) {
			RPCharacters.getPlayerManager().savePlayer(victim);
		}
		boolean evil = EvilRpService.isInSession(character);
		Decision decision = new Decision(character.getId(), killer.getUniqueId(), evil, died,
				System.currentTimeMillis() + PvpLoader.getDecisionMs());
		pending.put(victim.getUniqueId(), decision);

		int seconds = PvpLoader.getDecisionSeconds();
		boolean kills = StrikeOutcome.nextStrikeKills(character.getEvilRpStrikes(), evil);
		RPTexts.send(victim, RPTexts.ERROR + "You're at " + RPTexts.formatGui(DisplayIdentityService.resolveDisplay(killer))
				+ RPTexts.ERROR + "'s mercy. " + RPTexts.MUTED + "They have " + seconds + " seconds to spare you or "
				+ (kills ? "kill" : "strike") + " you.");
		sendPrompt(killer, victim, character, evil, kills, seconds);
		return true;
	}

	private static void execute(Player victim, Decision decision, Player killer) {
		RPCharacter character = characterById(victim, decision.characterId);
		if (character == null) {
			return;
		}
		EvilRpService.applyDecay(character, System.currentTimeMillis());
		boolean killEntity = !decision.died;
		boolean kills = StrikeOutcome.nextStrikeKills(character.getEvilRpStrikes(), decision.evil);
		if (kills && killEntity) {
			executions.put(victim.getUniqueId(), new Execution(decision.killerId, decision.evil,
					System.currentTimeMillis() + EXECUTION_TTL_MS));
		}
		character.setEvilRpSessionEndsAtMs(0L);
		String victimName = character.getName();
		if (decision.evil) {
			EvilRpService.killByStrike(victim, character, killer, killEntity);
			RPCharacters.getPlayerManager().savePlayer(victim);
		} else {
			EvilRpService.applyStrike(victim, character, killer, killEntity);
		}
		// A CharacterPermakillEvent listener can cancel the kill.
		boolean killed = character.getStatus() != Status.ALIVE;
		if (!killed) {
			executions.remove(victim.getUniqueId());
		}
		if (killer != null && killer.isOnline()) {
			String outcome = killed ? "You killed " : kills ? "You couldn't kill " : "You struck ";
			RPTexts.send(killer, RPTexts.ERROR + outcome + RPTexts.WARN + victimName + RPTexts.ERROR + ".");
		}
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Decision>> it = pending.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Decision> entry = it.next();
			if (now < entry.getValue().expiresAtMs) {
				continue;
			}
			it.remove();
			Player killer = Bukkit.getPlayer(entry.getValue().killerId);
			if (killer != null) {
				RPTexts.send(killer, RPTexts.MUTED + "You didn't choose in time, so they were spared.");
			}
			Player victim = Bukkit.getPlayer(entry.getKey());
			if (victim != null) {
				RPTexts.send(victim, RPTexts.SUCCESS + "You were spared.");
			}
		}
		executions.values().removeIf(execution -> now >= execution.expiresAtMs);
	}

	private static void sendPrompt(Player killer, Player victim, RPCharacter character, boolean evil, boolean kills,
			int seconds) {
		String victimName = RPTexts.formatGui(DisplayIdentityService.resolveDisplay(victim));
		String command = "/rpcharacter %s " + victim.getUniqueId();
		Component spare = Component.text("[Spare]", NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand(String.format(command, "spare")))
				.hoverEvent(HoverEvent.showText(Component.text("Let them go with no strike", NamedTextColor.GRAY)));
		int next = character.getEvilRpStrikes() + 1;
		Component strike = Component.text(kills ? "[Kill]" : "[Strike " + next + "/" + StrikeOutcome.MAX_STRIKES + "]",
				NamedTextColor.RED)
				.decorate(TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand(String.format(command, kills ? "kill" : "strike")))
				.hoverEvent(HoverEvent.showText(Component.text(kills
						? "Their character dies"
						: next == 2 ? "They get a permanent injury" : "They get a healing injury", NamedTextColor.GRAY)));
		String reason = evil ? " was in an evil RP session, so any strike kills them." : " is at your mercy.";
		killer.sendMessage(Component.empty()
				.append(LegacyComponentSerializer.legacySection().deserialize(victimName))
				.append(Component.text(reason + " If you don't choose in " + seconds + " seconds, they're spared. ",
						NamedTextColor.GRAY))
				.append(spare)
				.append(Component.space())
				.append(strike));
	}

	private static RPCharacter activeCharacter(Player player) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return null;
		}
		RPCharacter character = pd.getActiveCharacter();
		return character.getStatus() == Status.ALIVE ? character : null;
	}

	private static RPCharacter characterById(Player player, String characterId) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return null;
		}
		RPCharacter character = pd.getCharacterById(characterId);
		return character != null && character.getStatus() == Status.ALIVE ? character : null;
	}

	private static File verdictFile() {
		return new File(RPCharacters.plugin.getDataFolder(), VERDICT_FILE);
	}

	private static void loadVerdicts() {
		verdicts.clear();
		File file = verdictFile();
		if (!file.exists()) {
			return;
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		for (String key : config.getKeys(false)) {
			ConfigurationSection section = config.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			try {
				verdicts.put(UUID.fromString(key), new Decision(section.getString("character"),
						UUID.fromString(section.getString("killer", "")), section.getBoolean("evil"),
						section.getBoolean("died"), 0L));
			} catch (IllegalArgumentException | NullPointerException ex) {
				RPCharacters.plugin.getLogger().warning("Skipping bad pending strike for " + key + " in " + VERDICT_FILE);
			}
		}
	}

	private static void saveVerdicts() {
		YamlConfiguration config = new YamlConfiguration();
		for (Map.Entry<UUID, Decision> entry : verdicts.entrySet()) {
			String key = entry.getKey().toString();
			config.set(key + ".character", entry.getValue().characterId);
			config.set(key + ".killer", entry.getValue().killerId.toString());
			config.set(key + ".evil", entry.getValue().evil);
			config.set(key + ".died", entry.getValue().died);
		}
		try {
			config.save(verdictFile());
		} catch (IOException ex) {
			RPCharacters.plugin.getLogger().warning("Could not save " + VERDICT_FILE + ": " + ex.getMessage());
		}
	}

	/** {@code evilVictim} leaves an unlocked grave; {@code killerId} overrides the death's own killer. */
	public record GraveContext(boolean inPvpStart, boolean evilVictim, UUID killerId) {
	}

	private record Decision(String characterId, UUID killerId, boolean evil, boolean died, long expiresAtMs) {
		Decision afterDeath() {
			return new Decision(characterId, killerId, evil, true, expiresAtMs);
		}
	}

	private record Execution(UUID killerId, boolean evil, long expiresAtMs) {
	}
}
