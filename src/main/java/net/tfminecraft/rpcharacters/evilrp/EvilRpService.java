package net.tfminecraft.rpcharacters.evilrp;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
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
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

/**
 * Evil RP sessions and the three-strike rule. Any evil play (lockpicking, robbing,
 * pickpocketing, grave looting) starts or resets a timed session on the active character.
 * Dying, or being knocked out and not spared, before it runs out costs a strike.
 */
public final class EvilRpService {

	/** Only players online when their session ran out are told; stale timestamps clear quietly. */
	private static final long END_NOTICE_WINDOW_MS = 5_000L;

	private static final Map<UUID, PendingSpare> pendingSpares = new ConcurrentHashMap<>();
	private static BukkitTask tickTask;

	private EvilRpService() {
	}

	public static void start() {
		shutdown();
		tickTask = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, EvilRpService::tick, 20L, 20L);
	}

	public static void shutdown() {
		if (tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		// Nobody can spare them once the server stops, so the strikes land now.
		for (Map.Entry<UUID, PendingSpare> entry : pendingSpares.entrySet()) {
			Player victim = Bukkit.getPlayer(entry.getKey());
			RPCharacter character = victim != null ? characterById(victim, entry.getValue().characterId) : null;
			if (character != null) {
				applyStrike(victim, character);
			}
		}
		pendingSpares.clear();
	}

	/**
	 * Records an evil play by the player's active character, starting a session or resetting
	 * it to full length. Returns false when there is no living active character.
	 */
	public static boolean recordPlay(Player player) {
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		boolean continuing = isInSession(character.getEvilRpSessionEndsAtMs(), now);
		character.setEvilRpSessionEndsAtMs(now + EvilRpLoader.getSessionMs());
		RPCharacters.getPlayerManager().savePlayer(player);

		int minutes = EvilRpLoader.getSessionMinutes();
		if (continuing) {
			actionBar(player, RPTexts.MUTED + "Evil RP session reset to " + RPTexts.WARN + minutes + " minutes");
			return true;
		}
		actionBar(player, RPTexts.ERROR + "Evil RP session started: " + RPTexts.WARN + minutes + " minutes");
		TutorialService.show(player, TutorialService.EVIL_RP, Map.of(
				"minutes", String.valueOf(minutes),
				"strikes", String.valueOf(character.getEvilRpStrikes())));
		return true;
	}

	public static boolean isInSession(RPCharacter character) {
		return character != null && isInSession(character.getEvilRpSessionEndsAtMs(), System.currentTimeMillis());
	}

	public static boolean isInSession(Player player) {
		return isInSession(activeCharacter(player));
	}

	static boolean isInSession(long endsAtMs, long nowMs) {
		return endsAtMs > nowMs;
	}

	public static long remainingMs(RPCharacter character) {
		if (character == null) {
			return 0L;
		}
		return Math.max(0L, character.getEvilRpSessionEndsAtMs() - System.currentTimeMillis());
	}

	/** Switching characters mid-session, or while waiting to be spared, would dodge the strike. */
	public static boolean blocksCharacterSwitch(Player player) {
		return isInSession(player) || (player != null && pendingSpares.containsKey(player.getUniqueId()));
	}

	public static void sendSwitchBlocked(Player player) {
		RPTexts.send(player, RPTexts.ERROR + "You can't switch characters during an evil RP session"
				+ (isInSession(player)
						? " (" + formatRemaining(remainingMs(activeCharacter(player))) + " left)."
						: "."));
	}

	/**
	 * Called from the death event. Ends the session and applies a strike when one was running.
	 * Returns true when a strike was taken, so permadeath-zone consequences are skipped.
	 */
	public static boolean handleDeath(Player player) {
		PendingSpare pending = pendingSpares.remove(player.getUniqueId());
		if (pending != null) {
			// Dying while knocked out settles the strike; there is nobody left to spare.
			RPCharacter knockedOut = characterById(player, pending.characterId);
			if (knockedOut != null) {
				applyStrike(player, knockedOut);
				return true;
			}
		}
		RPCharacter character = activeCharacter(player);
		if (!isInSession(character)) {
			return false;
		}
		character.setEvilRpSessionEndsAtMs(0L);
		applyStrike(player, character);
		return true;
	}

	/**
	 * A nonlethal knockout during a session counts as a death, but the player who knocked
	 * them out gets a short window to spare them first.
	 */
	public static void handleKnockout(Player victim, Player attacker) {
		RPCharacter character = activeCharacter(victim);
		if (!isInSession(character)) {
			return;
		}
		character.setEvilRpSessionEndsAtMs(0L);
		if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
			applyStrike(victim, character);
			return;
		}

		int seconds = EvilRpLoader.getSpareSeconds();
		pendingSpares.put(victim.getUniqueId(), new PendingSpare(
				character.getId(), attacker.getUniqueId(), System.currentTimeMillis() + EvilRpLoader.getSpareMs()));
		RPCharacters.getPlayerManager().savePlayer(victim);

		RPTexts.send(victim, RPTexts.ERROR + "You were knocked out during your evil RP session. "
				+ RPTexts.MUTED + "Unless you're spared in the next " + seconds + " seconds, you get a strike.");
		sendSpareOffer(attacker, victim, seconds);
	}

	/** The attacker's choice to let a knocked-out player off without a strike. */
	public static boolean spare(Player attacker, UUID victimId) {
		PendingSpare pending = victimId != null ? pendingSpares.get(victimId) : null;
		if (pending == null || !pending.attackerId.equals(attacker.getUniqueId())) {
			RPTexts.send(attacker, RPTexts.ERROR + "There's no one for you to spare right now.");
			return false;
		}
		pendingSpares.remove(victimId);
		Player victim = Bukkit.getPlayer(victimId);
		RPTexts.send(attacker, RPTexts.SUCCESS + "You spared them. They won't get a strike for this.");
		if (victim != null) {
			RPTexts.send(victim, RPTexts.SUCCESS + "You were spared. No strike this time.");
		}
		return true;
	}

	/** Victims who log out while waiting on a spare get the strike straight away. */
	public static void handleQuit(Player player) {
		PendingSpare pending = pendingSpares.remove(player.getUniqueId());
		if (pending == null) {
			return;
		}
		RPCharacter character = characterById(player, pending.characterId);
		if (character != null) {
			applyStrike(player, character);
		}
	}

	public static void sendJoinReminder(Player player) {
		RPCharacter character = activeCharacter(player);
		if (!isInSession(character)) {
			return;
		}
		RPTexts.send(player, RPTexts.WARN + "Your evil RP session is still running "
				+ RPTexts.MUTED + "(" + formatRemaining(remainingMs(character)) + " left).");
	}

	/** Adds a strike and applies what it costs: a healing injury, a permanent one, then death. */
	public static StrikeOutcome applyStrike(Player player, RPCharacter character) {
		int strike = character.getEvilRpStrikes() + 1;
		character.setEvilRpStrikes(strike);
		StrikeOutcome outcome = StrikeOutcome.forStrike(strike);
		RPCharacters.plugin.getLogger().info("Evil RP strike " + strike + " for " + player.getName()
				+ " (" + character.getName() + "): " + outcome);

		switch (outcome) {
			case HEALING_INJURY -> {
				Trait injury = PermadeathService.giveRandomInjury(player, character);
				showStrike(player, strike, injury, "You got a healing injury.");
			}
			case PERMANENT_INJURY -> {
				Trait injury = PermadeathService.givePermanentInjury(player, character);
				showStrike(player, strike, injury, "You got a permanent injury. One more strike and "
						+ character.getName() + " dies.");
			}
			case DEATH -> {
				if (!PermadeathService.killCharacter(player, character, PermakillCause.EVIL_RP_STRIKES)) {
					RPCharacters.plugin.getLogger().warning("Third evil RP strike for " + player.getName()
							+ " (" + character.getName() + ") did not kill the character; the permakill was cancelled.");
				}
			}
		}
		RPCharacters.getPlayerManager().savePlayer(player);
		return outcome;
	}

	public static String formatRemaining(long remainingMs) {
		long totalSeconds = (remainingMs + 999L) / 1000L;
		return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, PendingSpare>> it = pendingSpares.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, PendingSpare> entry = it.next();
			if (now < entry.getValue().expiresAtMs) {
				continue;
			}
			it.remove();
			Player victim = Bukkit.getPlayer(entry.getKey());
			RPCharacter character = victim != null ? characterById(victim, entry.getValue().characterId) : null;
			if (character != null) {
				applyStrike(victim, character);
			}
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			RPCharacter character = activeCharacter(player);
			if (character == null) {
				continue;
			}
			long endsAt = character.getEvilRpSessionEndsAtMs();
			if (endsAt <= 0L || isInSession(endsAt, now)) {
				continue;
			}
			character.setEvilRpSessionEndsAtMs(0L);
			if (now - endsAt < END_NOTICE_WINDOW_MS) {
				RPTexts.send(player, RPTexts.SUCCESS + "Your evil RP session has ended.");
			}
		}
	}

	private static void showStrike(Player player, int strike, Trait injury, String detail) {
		String subtitle = injury != null ? TraitChangeService.resolveGainedMessage(injury) : " ";
		RPTexts.longTitle(player, RPTexts.ERROR + "Strike " + strike, subtitle);
		String outcome = injury != null ? detail : "There were no injuries left to give.";
		RPTexts.send(player, RPTexts.ERROR + "Strike " + strike + " of " + StrikeOutcome.MAX_STRIKES + ". "
				+ RPTexts.MUTED + outcome);
	}

	private static void sendSpareOffer(Player attacker, Player victim, int seconds) {
		String victimName = RPTexts.formatGui(DisplayIdentityService.resolveDisplay(victim));
		Component spare = Component.text("[Spare]", NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand("/rpcharacter spare " + victim.getUniqueId()))
				.hoverEvent(HoverEvent.showText(Component.text("Let them off without a strike", NamedTextColor.GRAY)));
		attacker.sendMessage(Component.empty()
				.append(LegacyComponentSerializer.legacySection().deserialize(victimName))
				.append(Component.text(" was in the middle of evil RP. If you don't spare them in the next "
						+ seconds + " seconds, they get a strike. ", NamedTextColor.GRAY))
				.append(spare));
	}

	private static void actionBar(Player player, String legacyText) {
		player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacyText));
	}

	private static RPCharacter activeCharacter(Player player) {
		if (player == null) {
			return null;
		}
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

	private record PendingSpare(String characterId, UUID attackerId, long expiresAtMs) {
	}
}
