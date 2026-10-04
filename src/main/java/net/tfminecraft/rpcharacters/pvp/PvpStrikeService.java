package net.tfminecraft.rpcharacters.pvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
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
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

/**
 * Strikes from {@code /pvp start} fights. When a tagged player is killed or knocked out by
 * another player, the killer chooses to spare them or strike them. The same player cannot
 * strike that character again until the cooldown in pvp.yml has passed, so one person
 * cannot land all three strikes in a day. The strike is labelled Kill when it would kill:
 * the character's last strike, or any strike during an evil RP session. Then the killer can
 * also Wound (healing injury) or Maim (permanent injury) instead, with no strike. Running
 * out of time spares them.
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

	/** The killer's choice. */
	public static boolean choose(Player killer, UUID victimId, StrikeChoice choice) {
		Decision decision = victimId != null ? pending.get(victimId) : null;
		if (decision == null || !decision.killerId.equals(killer.getUniqueId())) {
			RPTexts.send(killer, RPTexts.ERROR + "There's no one waiting on your decision right now.");
			return false;
		}
		Player victim = Bukkit.getPlayer(victimId);
		if (System.currentTimeMillis() >= decision.expiresAtMs) {
			pending.remove(victimId, decision);
			RPTexts.send(killer, RPTexts.MUTED + "You didn't choose in time, so they were spared.");
			if (victim != null) {
				RPTexts.send(victim, RPTexts.SUCCESS + "You were spared.");
			}
			return false;
		}
		if (!choice.isOffered(decision.strikeKills)) {
			RPTexts.send(killer, RPTexts.ERROR + "You can only wound or maim someone whose next strike would kill them.");
			return false;
		}
		decision = decision.withChoice(choice);
		PlayerData data = victim != null && victim.isOnline() ? PlayerManager.get(victim) : null;
		boolean injury = choice == StrikeChoice.WOUND || choice == StrikeChoice.MAIM;
		if (injury && data != null) {
			RPCharacter character = characterById(data, decision.characterId);
			// Keep the decision open when there's no injury left to give, so they can pick again.
			if (character != null && !injure(victim, character, choice == StrikeChoice.MAIM, killer)) {
				RPTexts.send(killer, RPTexts.ERROR + "They have no " + (choice == StrikeChoice.MAIM ? "permanent" : "healing")
						+ " injuries left to give. Choose another option.");
				return false;
			}
			pending.remove(victimId);
			return true;
		}
		if (choice == StrikeChoice.SPARE) {
			pending.remove(victimId);
			RPTexts.send(killer, RPTexts.SUCCESS + "You spared them.");
			if (victim != null) {
				RPTexts.send(victim, RPTexts.SUCCESS + "You were spared.");
			}
			return true;
		}
		if (data == null) {
			Decision previous = verdicts.put(victimId, decision);
			if (!saveVerdicts()) {
				if (previous == null) verdicts.remove(victimId, decision);
				else verdicts.put(victimId, previous);
				RPTexts.send(killer, RPTexts.ERROR + "Could not save that decision. Please try again.");
				return false;
			}
			pending.remove(victimId);
			RPTexts.send(killer, RPTexts.WARN + "They're offline. It lands when they return.");
			return true;
		}
		pending.remove(victimId);
		execute(victim, data, decision, killer);
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
			PlayerData data = PlayerManager.get(player);
			if (data == null) {
				// Keep the verdict for their next join if their data never loads.
				if (retriesLeft > 0) {
					applyVerdictLater(player, retriesLeft - 1);
				}
				return;
			}
			Decision verdict = verdicts.remove(player.getUniqueId());
			if (verdict == null) {
				return;
			}
			if (!saveVerdicts()) {
				verdicts.put(player.getUniqueId(), verdict);
				return;
			}
			execute(player, data, verdict, Bukkit.getPlayer(verdict.killerId));
		}, JOIN_DELAY_TICKS);
	}

	private static boolean openDecision(Player victim, Player killer, boolean died) {
		RPCharacter character = activeCharacter(victim);
		if (character == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (EvilRpService.applyDecay(character, now)) {
			RPCharacters.getPlayerManager().savePlayer(victim);
		}
		// A spare here still covers the death, so a permadeath-zone roll does not stack on it.
		if (onCooldown(character, killer.getUniqueId(), now)) {
			sendCooldown(victim, killer, character, killer.getUniqueId(), now);
			return true;
		}
		boolean evil = EvilRpService.isInSession(character);
		boolean kills = StrikeOutcome.nextStrikeKills(character.getEvilRpStrikes(), evil);
		Decision decision = new Decision(character.getId(), killer.getUniqueId(), evil, died, kills,
				System.currentTimeMillis() + PvpLoader.getDecisionMs(), StrikeChoice.STRIKE);
		pending.put(victim.getUniqueId(), decision);

		int seconds = PvpLoader.getDecisionSeconds();
		RPTexts.send(victim, RPTexts.ERROR + "You're at " + RPTexts.formatGui(DisplayIdentityService.resolveDisplay(killer))
				+ RPTexts.ERROR + "'s mercy. " + RPTexts.MUTED + "They have " + seconds + " seconds to "
				+ (kills ? "spare, wound, maim or kill you." : "spare or strike you."));
		sendPrompt(killer, victim, character, evil, kills, seconds);
		return true;
	}

	private static void execute(Player victim, PlayerData data, Decision decision, Player killer) {
		RPCharacter character = characterById(data, decision.characterId);
		if (character == null) {
			return;
		}
		if (decision.choice == StrikeChoice.WOUND || decision.choice == StrikeChoice.MAIM) {
			// Chosen while they were offline; if nothing is left to give by now, they get off.
			if (!injure(victim, character, decision.choice == StrikeChoice.MAIM, killer)) {
				RPTexts.send(victim, RPTexts.SUCCESS + "You were spared: there were no injuries left to give.");
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (EvilRpService.applyDecay(character, now)) {
			RPCharacters.getPlayerManager().savePlayer(victim);
		}
		// The window can close while they decide, or while an offline verdict waits to land.
		if (onCooldown(character, decision.killerId, now)) {
			sendCooldown(victim, killer, character, decision.killerId, now);
			return;
		}
		boolean killEntity = !decision.died;
		boolean kills = StrikeOutcome.nextStrikeKills(character.getEvilRpStrikes(), decision.evil);
		if (kills && killEntity) {
			executions.put(victim.getUniqueId(), new Execution(decision.killerId, decision.evil,
					now + EXECUTION_TTL_MS));
		}
		character.setEvilRpSessionEndsAtMs(0L);
		String victimName = character.getName();
		if (decision.evil) {
			if (EvilRpService.killByStrike(victim, character, killer, killEntity)) {
				rememberStrike(character, decision.killerId, now);
			}
			RPCharacters.getPlayerManager().savePlayer(victim);
		} else {
			rememberStrike(character, decision.killerId, now);
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

	private static boolean onCooldown(RPCharacter character, UUID killerId, long nowMs) {
		return StrikeCooldown.blocks(character.getStrikesByKiller(), killerId, nowMs,
				PvpLoader.getSameTargetCooldownMs());
	}

	private static void rememberStrike(RPCharacter character, UUID killerId, long nowMs) {
		character.setStrikesByKiller(StrikeCooldown.record(character.getStrikesByKiller(), killerId, nowMs,
				PvpLoader.getSameTargetCooldownMs()));
	}

	private static void sendCooldown(Player victim, Player killer, RPCharacter character, UUID killerId, long nowMs) {
		String time = StrikeCooldown.formatRemaining(StrikeCooldown.remainingMs(character.getStrikesByKiller(),
				killerId, nowMs, PvpLoader.getSameTargetCooldownMs()));
		String name = character.getName() != null ? character.getName() : "them";
		if (killer != null && killer.isOnline()) {
			RPTexts.send(killer, RPTexts.ERROR + "You already struck " + RPTexts.WARN + name + RPTexts.ERROR
					+ ". " + RPTexts.MUTED + "You can strike them again in " + time + ".");
		}
		if (victim != null && victim.isOnline()) {
			RPTexts.send(victim, RPTexts.SUCCESS + "You were spared. " + RPTexts.MUTED
					+ "They can strike you again in " + time + ".");
		}
		String killerName = killer != null ? killer.getName() : String.valueOf(killerId);
		String victimName = victim != null ? victim.getName() : "offline";
		RPCharacters.plugin.getLogger().info("Strike cooldown: " + killerName + " cannot strike "
				+ victimName + " (" + name + ") for another " + time + ".");
	}

	/**
	 * Wound or Maim: one healing or permanent injury, and no strike. Returns false, changing
	 * nothing, when there's no injury of that kind left to give.
	 */
	private static boolean injure(Player victim, RPCharacter character, boolean maim, Player killer) {
		Trait injury = maim
				? PermadeathService.givePermanentInjury(victim, character)
				: PermadeathService.giveRandomInjury(victim, character);
		if (injury == null) {
			return false;
		}
		character.setEvilRpSessionEndsAtMs(0L);
		RPCharacters.getPlayerManager().savePlayer(victim);
		String verb = maim ? "maimed" : "wounded";
		RPTexts.longTitle(victim, RPTexts.ERROR + (maim ? "Maimed" : "Wounded"),
				TraitChangeService.resolveGainedMessage(injury));
		RPTexts.send(victim, RPTexts.ERROR + "You were " + verb + " instead of killed. " + RPTexts.MUTED
				+ (maim ? "You got a permanent injury." : "You got a healing injury."));
		if (killer != null && killer.isOnline()) {
			RPTexts.send(killer, RPTexts.ERROR + "You " + verb + " " + RPTexts.WARN + character.getName()
					+ RPTexts.ERROR + ".");
		}
		return true;
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
		int next = character.getEvilRpStrikes() + 1;
		Component buttons = button("[Spare]", NamedTextColor.GREEN, String.format(command, "spare"),
				"Let them go with no strike");
		if (kills) {
			buttons = buttons
					.append(Component.space())
					.append(button("[Wound]", NamedTextColor.YELLOW, String.format(command, "wound"),
							"They get a healing injury, no strike"))
					.append(Component.space())
					.append(button("[Maim]", NamedTextColor.GOLD, String.format(command, "maim"),
							"They get a permanent injury, no strike"))
					.append(Component.space())
					.append(button("[Kill]", NamedTextColor.RED, String.format(command, "kill"), "Their character dies"));
		} else {
			buttons = buttons
					.append(Component.space())
					.append(button("[Strike " + next + "/" + StrikeOutcome.MAX_STRIKES + "]", NamedTextColor.RED,
							String.format(command, "strike"),
							next == 2 ? "They get a permanent injury" : "They get a healing injury"));
		}
		String reason = evil ? " was in an evil RP session, so any strike kills them." : " is at your mercy.";
		killer.sendMessage(Component.empty()
				.append(LegacyComponentSerializer.legacySection().deserialize(victimName))
				.append(Component.text(reason + " If you don't choose in " + seconds + " seconds, they're spared. ",
						NamedTextColor.GRAY))
				.append(buttons));
	}

	private static Component button(String label, NamedTextColor color, String command, String hover) {
		return Component.text(label, color)
				.decorate(TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand(command))
				.hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
	}

	private static RPCharacter activeCharacter(Player player) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return null;
		}
		RPCharacter character = pd.getActiveCharacter();
		return character.getStatus() == Status.ALIVE ? character : null;
	}

	private static RPCharacter characterById(PlayerData pd, String characterId) {
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
				StrikeChoice choice = StrikeChoice.fromCommand(section.getString("choice", "strike"));
				verdicts.put(UUID.fromString(key), new Decision(section.getString("character"),
						UUID.fromString(section.getString("killer", "")), section.getBoolean("evil"),
						section.getBoolean("died"), true, 0L, choice != null ? choice : StrikeChoice.STRIKE));
			} catch (IllegalArgumentException | NullPointerException ex) {
				RPCharacters.plugin.getLogger().warning("Skipping bad pending strike for " + key + " in " + VERDICT_FILE);
			}
		}
	}

	private static boolean saveVerdicts() {
		YamlConfiguration config = new YamlConfiguration();
		for (Map.Entry<UUID, Decision> entry : verdicts.entrySet()) {
			String key = entry.getKey().toString();
			config.set(key + ".character", entry.getValue().characterId);
			config.set(key + ".killer", entry.getValue().killerId.toString());
			config.set(key + ".evil", entry.getValue().evil);
			config.set(key + ".died", entry.getValue().died);
			config.set(key + ".choice", entry.getValue().choice.name().toLowerCase(Locale.ROOT));
		}
		Path staged = null;
		try {
			Path target = verdictFile().toPath().toAbsolutePath().normalize();
			Files.createDirectories(target.getParent());
			staged = Files.createTempFile(target.getParent(), ".pending-strikes-", ".tmp");
			Files.writeString(staged, config.saveToString(), StandardCharsets.UTF_8);
			Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException ex) {
			if (staged != null) {
				try {
					Files.deleteIfExists(staged);
				} catch (IOException cleanupFailure) {
					ex.addSuppressed(cleanupFailure);
				}
			}
			RPCharacters.plugin.getLogger().warning("Could not save " + VERDICT_FILE + ": " + ex.getMessage());
			return false;
		}
	}

	/** {@code evilVictim} leaves an unlocked grave; {@code killerId} overrides the death's own killer. */
	public record GraveContext(boolean inPvpStart, boolean evilVictim, UUID killerId) {
	}

	/** {@code strikeKills} is whether Kill (with Wound and Maim) was offered; {@code choice} is set once picked. */
	private record Decision(String characterId, UUID killerId, boolean evil, boolean died, boolean strikeKills,
			long expiresAtMs, StrikeChoice choice) {
		Decision afterDeath() {
			return new Decision(characterId, killerId, evil, true, strikeKills, expiresAtMs, choice);
		}

		Decision withChoice(StrikeChoice picked) {
			return new Decision(characterId, killerId, evil, died, strikeKills, expiresAtMs, picked);
		}
	}

	private record Execution(UUID killerId, boolean evil, long expiresAtMs) {
	}
}
