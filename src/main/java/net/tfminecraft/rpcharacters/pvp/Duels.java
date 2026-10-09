package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.DuelLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Friendly duels. Only damage between the two duellists belongs to the duel: it is given
 * back when the duel ends, and a blow that would kill ends the duel instead. Anything else
 * that happens to them stays real, so a duel can't heal or shield anyone.
 */
public final class Duels {

	/** How a duel ended, and whether hunger, mana and effects go back too. */
	enum Ending {
		WON(true, null),
		YIELDED(true, null),
		TIMEOUT(true, "reason-timeout"),
		APART(true, "reason-apart"),
		LEFT(true, "reason-left"),
		FIGHT(true, "reason-fight"),
		BATTLE(true, "reason-battle"),
		GAMEMODE(true, "reason-gamemode"),
		DIED(true, "reason-died"),
		STOPPED(true, "reason-stopped"),
		/** Someone else joined in, so only the duel's own damage is given back. */
		INTERRUPTED(false, null);

		private final boolean fullRestore;
		private final String reasonKey;

		Ending(boolean fullRestore, String reasonKey) {
			this.fullRestore = fullRestore;
			this.reasonKey = reasonKey;
		}
	}

	private record Challenge(UUID challenger, UUID target, long expiresAtMs) {
	}

	/** Burning, poison and wither with no attacker can be what the opponent's hits left behind. */
	private static final Set<EntityDamageEvent.DamageCause> LINGERING = Set.of(
			EntityDamageEvent.DamageCause.FIRE_TICK,
			EntityDamageEvent.DamageCause.POISON,
			EntityDamageEvent.DamageCause.WITHER);
	/** Fire the duellist walked into themselves, after which burning is theirs. */
	private static final Set<EntityDamageEvent.DamageCause> BURNS = Set.of(
			EntityDamageEvent.DamageCause.FIRE,
			EntityDamageEvent.DamageCause.LAVA,
			EntityDamageEvent.DamageCause.HOT_FLOOR,
			EntityDamageEvent.DamageCause.CAMPFIRE);
	/** How long after the opponent's last hit its burning, poison or wither still counts. */
	static final long LINGER_MS = 30_000L;

	private static final Map<UUID, Challenge> challengesByChallenger = new LinkedHashMap<>();
	private static final Map<UUID, Duel> duelsByPlayer = new HashMap<>();
	private static Predicate<Player> downed = player -> false;
	private static BukkitTask ticker;

	private Duels() {
	}

	public static void start(Predicate<Player> downedCheck) {
		downed = downedCheck;
		if (ticker != null) {
			ticker.cancel();
		}
		ticker = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, Duels::tick, 10L, 10L);
	}

	/** Everyone duelling gets back what their duel took before the server stops. */
	public static void shutdown() {
		if (ticker != null) {
			ticker.cancel();
			ticker = null;
		}
		for (Duel duel : activeDuels()) {
			end(duel, Ending.STOPPED, null);
		}
		challengesByChallenger.clear();
	}

	public static boolean isDuelling(UUID playerId) {
		return duelsByPlayer.containsKey(playerId);
	}

	static void challenge(Player challenger, Player target) {
		if (challenger.getUniqueId().equals(target.getUniqueId())) {
			send(challenger, "self");
			return;
		}
		Challenge theirs = challengesByChallenger.get(target.getUniqueId());
		if (theirs != null && theirs.target().equals(challenger.getUniqueId())) {
			accept(challenger, target.getUniqueId().toString());
			return;
		}
		if (!canDuel(challenger, target)) {
			return;
		}
		Challenge earlier = challengesByChallenger.remove(challenger.getUniqueId());
		if (earlier != null && !earlier.target().equals(target.getUniqueId())) {
			Player old = Bukkit.getPlayer(earlier.target());
			if (old != null) {
				send(old, "withdrawn-target", "{name}", name(challenger));
			}
		}
		challengesByChallenger.put(challenger.getUniqueId(), new Challenge(challenger.getUniqueId(),
				target.getUniqueId(), System.currentTimeMillis() + DuelLoader.getChallengeMs()));
		send(challenger, "challenge-sent", "{name}", name(target),
				"{seconds}", String.valueOf(DuelLoader.getChallengeSeconds()));
		String id = challenger.getUniqueId().toString();
		target.sendMessage(LegacyComponentSerializer.legacySection()
				.deserialize(RPTexts.formatDisplay(text("challenge-received", "{name}", name(challenger))))
				.append(Component.text(" "))
				.append(button("[Accept]", NamedTextColor.GREEN, "/duel accept " + id, "Start the duel"))
				.append(Component.text(" "))
				.append(button("[Decline]", NamedTextColor.RED, "/duel decline " + id, "Turn it down")));
	}

	static void accept(Player target, String from) {
		Challenge challenge = challengeTo(target, from);
		Player challenger = challenge == null ? null : Bukkit.getPlayer(challenge.challenger());
		if (challenger == null) {
			send(target, "no-challenge");
			return;
		}
		if (!canDuel(target, challenger)) {
			return;
		}
		// No challenge back the other way can exist: challenging your challenger accepts theirs.
		challengesByChallenger.remove(challenger.getUniqueId());
		begin(challenger, target);
	}

	static void decline(Player target, String from) {
		Challenge challenge = challengeTo(target, from);
		if (challenge == null) {
			send(target, "no-challenge");
			return;
		}
		challengesByChallenger.remove(challenge.challenger());
		Player challenger = Bukkit.getPlayer(challenge.challenger());
		if (challenger != null) {
			send(challenger, "declined-challenger", "{name}", name(target));
			send(target, "declined-target", "{name}", name(challenger));
		}
	}

	static void cancel(Player challenger) {
		Challenge challenge = challengesByChallenger.remove(challenger.getUniqueId());
		if (challenge == null) {
			send(challenger, "nothing-to-cancel");
			return;
		}
		Player target = Bukkit.getPlayer(challenge.target());
		if (target != null) {
			send(target, "withdrawn-target", "{name}", name(challenger));
			send(challenger, "withdrawn-challenger", "{name}", name(target));
		}
	}

	static void yield(Player player) {
		Duel duel = duelsByPlayer.get(player.getUniqueId());
		if (duel == null) {
			send(player, "not-duelling");
			return;
		}
		end(duel, Ending.YIELDED, player);
	}

	/**
	 * Before other plugins settle the hit: duellists can't hurt other players, or each other
	 * during the countdown. Anything else hurting a duellist, or a duellist fighting a mob,
	 * interrupts the duel, so mana or hunger spent outside it is never handed back.
	 */
	static void screenDamage(EntityDamageEvent event) {
		Entity source = causingEntity(event);
		Player attacker = source instanceof Player player ? player : null;
		Duel own = attacker == null ? null : duelsByPlayer.get(attacker.getUniqueId());
		if (!(event.getEntity() instanceof Player victim)) {
			// Armour stands are often furniture; knocking one is no fight.
			if (own != null && event.getEntity() instanceof LivingEntity && !(event.getEntity() instanceof ArmorStand)) {
				end(own, Ending.INTERRUPTED, attacker);
			}
			return;
		}
		if (own != null && !attacker.getUniqueId().equals(victim.getUniqueId())
				&& (!own.includes(victim.getUniqueId()) || !own.isFighting())) {
			event.setCancelled(true);
			return;
		}
		Duel duel = duelsByPlayer.get(victim.getUniqueId());
		if (duel != null && source != null && !source.getUniqueId().equals(victim.getUniqueId())
				&& !source.getUniqueId().equals(duel.opponentOf(victim.getUniqueId()))) {
			end(duel, Ending.INTERRUPTED, victim);
		}
	}

	/** After the hit lands: remember what the duel cost so it can be given back. */
	static void recordDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player victim)) {
			return;
		}
		Duel duel = duelsByPlayer.get(victim.getUniqueId());
		if (duel == null || !duel.isFighting()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (BURNS.contains(event.getCause())) {
			duel.burned(victim.getUniqueId(), now);
		}
		if (!countsForDuel(event, victim, duel)) {
			return;
		}
		if (causingEntity(event) != null) {
			duel.hitByOpponent(victim.getUniqueId(), now);
		}
		duel.addDamage(victim.getUniqueId(), Math.max(0.0, Math.min(event.getFinalDamage(), victim.getHealth())));
	}

	/**
	 * Called with a blow that would kill. If it is the duel's, the victim loses the duel
	 * instead of dying, so no knockout, death, grave or strike follows.
	 */
	public static boolean settleLethalBlow(EntityDamageEvent event, Player victim) {
		Duel duel = duelsByPlayer.get(victim.getUniqueId());
		if (duel == null || !duel.isFighting() || !countsForDuel(event, victim, duel)) {
			return false;
		}
		event.setCancelled(true);
		end(duel, Ending.WON, victim);
		return true;
	}

	/** A real fight takes over: its players' duels end, mended, before it starts. */
	static void endFor(Collection<UUID> players, Ending ending) {
		for (UUID id : players) {
			Duel duel = duelsByPlayer.get(id);
			if (duel != null) {
				end(duel, ending, Bukkit.getPlayer(id));
			}
		}
	}

	static void handleQuit(Player player) {
		challengesByChallenger.values().removeIf(c -> c.challenger().equals(player.getUniqueId())
				|| c.target().equals(player.getUniqueId()));
		Duel duel = duelsByPlayer.get(player.getUniqueId());
		if (duel != null) {
			end(duel, Ending.LEFT, player);
		}
	}

	static void handleDeath(Player player) {
		Duel duel = duelsByPlayer.get(player.getUniqueId());
		if (duel != null) {
			end(duel, Ending.DIED, player);
		}
	}

	static void tick() {
		long now = System.currentTimeMillis();
		Iterator<Challenge> challenges = challengesByChallenger.values().iterator();
		while (challenges.hasNext()) {
			Challenge challenge = challenges.next();
			if (now < challenge.expiresAtMs()) {
				continue;
			}
			challenges.remove();
			Player challenger = Bukkit.getPlayer(challenge.challenger());
			Player target = Bukkit.getPlayer(challenge.target());
			if (challenger != null && target != null) {
				send(challenger, "challenge-expired-challenger", "{name}", name(target));
				send(target, "challenge-expired-target", "{name}", name(challenger));
			}
		}
		for (Duel duel : activeDuels()) {
			checkStillDuelling(duel, now);
		}
	}

	private static void checkStillDuelling(Duel duel, long now) {
		Player first = Bukkit.getPlayer(duel.first());
		Player second = Bukkit.getPlayer(duel.second());
		if (first == null || second == null) {
			end(duel, Ending.LEFT, first == null ? second : first);
			return;
		}
		for (Player player : List.of(first, second)) {
			if (!fightsInGameMode(player)) {
				end(duel, Ending.GAMEMODE, player);
				return;
			}
			if (PermadeathBattleExemption.isInStartedBattle(player)) {
				end(duel, Ending.BATTLE, player);
				return;
			}
		}
		Location a = first.getLocation();
		Location b = second.getLocation();
		double leash = DuelLoader.getLeashRange();
		if (a.getWorld() != b.getWorld() || a.distanceSquared(b) > leash * leash) {
			end(duel, Ending.APART, first);
			return;
		}
		long maxMs = DuelLoader.getMaxMs();
		if (duel.isFighting() && maxMs > 0L && now - duel.fightStartedMs() >= maxMs) {
			end(duel, Ending.TIMEOUT, first);
		}
	}

	private static void begin(Player first, Player second) {
		Duel duel = new Duel(first.getUniqueId(), second.getUniqueId());
		long now = System.currentTimeMillis();
		duel.snapshot(first.getUniqueId(), DuelSnapshot.take(first, now));
		duel.snapshot(second.getUniqueId(), DuelSnapshot.take(second, now));
		duel.name(first.getUniqueId(), name(first));
		duel.name(second.getUniqueId(), name(second));
		duelsByPlayer.put(first.getUniqueId(), duel);
		duelsByPlayer.put(second.getUniqueId(), duel);
		int countdown = DuelLoader.getCountdownSeconds();
		for (int count = countdown; count >= 1; count--) {
			String shown = text("countdown", "{count}", String.valueOf(count));
			duel.addTask(Bukkit.getScheduler().runTaskLater(RPCharacters.plugin,
					() -> titleBoth(duel, shown), (countdown - count) * 20L));
		}
		if (countdown == 0) {
			startFight(duel);
		} else {
			duel.addTask(Bukkit.getScheduler().runTaskLater(RPCharacters.plugin,
					() -> startFight(duel), countdown * 20L));
		}
	}

	private static void startFight(Duel duel) {
		Player first = Bukkit.getPlayer(duel.first());
		Player second = Bukkit.getPlayer(duel.second());
		if (duelsByPlayer.get(duel.first()) != duel || first == null || second == null) {
			return;
		}
		duel.startFight(System.currentTimeMillis());
		titleBoth(duel, text("fight-title"));
		send(first, "started", "{name}", name(second));
		send(second, "started", "{name}", name(first));
		announce(first, second, text("announce-start", "{a}", name(first), "{b}", name(second)));
	}

	/** {@code subject} is the player the ending is about: the loser, the one who left, and so on. */
	private static void end(Duel duel, Ending ending, Player subject) {
		duelsByPlayer.remove(duel.first());
		duelsByPlayer.remove(duel.second());
		duel.cancelTasks();
		long now = System.currentTimeMillis();
		Player first = participant(duel.first(), subject);
		Player second = participant(duel.second(), subject);
		for (Player player : new Player[] { first, second }) {
			if (player == null || player.isDead() || player.getHealth() <= 0) {
				continue;
			}
			mend(player, duel.damageTaken(player.getUniqueId()));
			if (ending.fullRestore) {
				duel.snapshotOf(player.getUniqueId()).restore(player, now);
			}
		}
		tell(duel, ending, subject, first, second);
	}

	/** Names come from the start of the duel: someone logging out no longer has a character to name. */
	private static void tell(Duel duel, Ending ending, Player subject, Player first, Player second) {
		String subjectName = subject == null ? "" : duel.nameOf(subject.getUniqueId());
		if (ending == Ending.WON || ending == Ending.YIELDED) {
			Player winner = subject.getUniqueId().equals(duel.first()) ? second : first;
			if (winner == null) {
				return;
			}
			String winnerName = duel.nameOf(winner.getUniqueId());
			boolean yielded = ending == Ending.YIELDED;
			send(winner, yielded ? "yield-winner" : "won", "{name}", subjectName);
			send(subject, yielded ? "yield-loser" : "lost", "{name}", winnerName);
			announce(winner, subject, text(yielded ? "announce-yield" : "announce-win",
					"{winner}", winnerName, "{loser}", subjectName));
			return;
		}
		if (ending == Ending.INTERRUPTED) {
			sendAbout(duel, first, "interrupted", "{victim}", subjectName);
			sendAbout(duel, second, "interrupted", "{victim}", subjectName);
			return;
		}
		String reason = text(ending.reasonKey, "{name}", subjectName);
		// Whoever left or died doesn't need telling.
		boolean subjectGone = ending == Ending.LEFT || ending == Ending.DIED;
		for (Player player : new Player[] { first, second }) {
			if (!(subjectGone && player == subject)) {
				sendAbout(duel, player, "draw", "{reason}", reason);
			}
		}
	}

	private static void sendAbout(Duel duel, Player to, String key, String placeholder, String value) {
		if (to != null) {
			send(to, key, "{name}", duel.nameOf(duel.opponentOf(to.getUniqueId())), placeholder, value);
		}
	}

	/** The health the opponent took, given back up to full. */
	private static void mend(Player player, double amount) {
		if (amount <= 0.0) {
			return;
		}
		double max = 20.0;
		var attribute = player.getAttribute(Attribute.MAX_HEALTH);
		if (attribute != null) {
			max = attribute.getValue();
		}
		player.setHealth(Math.min(max, player.getHealth() + amount));
	}

	private static boolean canDuel(Player self, Player other) {
		String mine = whyNot(self);
		if (mine != null) {
			send(self, "you-busy", "{why}", text(mine));
			return false;
		}
		String theirs = whyNot(other);
		if (theirs != null) {
			send(self, "other-busy", "{name}", name(other), "{why}", text(theirs));
			return false;
		}
		int range = DuelLoader.getChallengeRange();
		Location a = self.getLocation();
		Location b = other.getLocation();
		if (a.getWorld() != b.getWorld() || a.distanceSquared(b) > (double) range * range) {
			send(self, "too-far", "{name}", name(other), "{range}", String.valueOf(range));
			return false;
		}
		return true;
	}

	/** The message key saying why this player can't duel, or null if they can. */
	private static String whyNot(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null || !data.hasActiveCharacter()) {
			return "why-character";
		}
		if (duelsByPlayer.containsKey(player.getUniqueId())) {
			return "why-duelling";
		}
		if (!fightsInGameMode(player)) {
			return "why-gamemode";
		}
		if (player.isDead() || downed.test(player)) {
			return "why-down";
		}
		if (PvpSituations.involves(player.getUniqueId())
				|| PvpStartSessions.isActive(player.getUniqueId(), System.currentTimeMillis())) {
			return "why-fight";
		}
		if (PermadeathBattleExemption.isInStartedBattle(player)) {
			return "why-battle";
		}
		return null;
	}

	private static boolean fightsInGameMode(Player player) {
		GameMode mode = player.getGameMode();
		return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
	}

	/**
	 * Damage from the opponent, or burning, poison and wither with no attacker that their
	 * recent hit can account for. Burning stops counting once the duellist steps in fire or
	 * lava themselves, so a lava burn can still kill.
	 */
	private static boolean countsForDuel(EntityDamageEvent event, Player victim, Duel duel) {
		Entity source = causingEntity(event);
		if (source != null) {
			return source.getUniqueId().equals(duel.opponentOf(victim.getUniqueId()));
		}
		if (!LINGERING.contains(event.getCause())) {
			return false;
		}
		long hit = duel.lastOpponentHitMs(victim.getUniqueId());
		if (hit <= 0L || System.currentTimeMillis() - hit > LINGER_MS) {
			return false;
		}
		return event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK
				|| duel.lastBurnMs(victim.getUniqueId()) < hit;
	}

	static Entity causingEntity(EntityDamageEvent event) {
		if (event.getDamageSource() != null && event.getDamageSource().getCausingEntity() != null) {
			return event.getDamageSource().getCausingEntity();
		}
		if (event instanceof EntityDamageByEntityEvent byEntity) {
			if (byEntity.getDamager() instanceof Projectile projectile) {
				return projectile.getShooter() instanceof Entity shooter ? shooter : null;
			}
			return byEntity.getDamager();
		}
		return null;
	}

	/** The newest open challenge to this player, from the named challenger if one is given. */
	private static Challenge challengeTo(Player target, String from) {
		Challenge found = null;
		long now = System.currentTimeMillis();
		// A new challenge is always put last, so the last match is the newest.
		for (Challenge challenge : challengesByChallenger.values()) {
			if (challenge.target().equals(target.getUniqueId()) && now < challenge.expiresAtMs()
					&& (from == null || from(challenge, from))) {
				found = challenge;
			}
		}
		return found;
	}

	private static boolean from(Challenge challenge, String from) {
		if (challenge.challenger().toString().equalsIgnoreCase(from)) {
			return true;
		}
		Player challenger = Bukkit.getPlayer(challenge.challenger());
		return challenger != null && challenger.getName().equalsIgnoreCase(from);
	}

	private static List<Duel> activeDuels() {
		return new ArrayList<>(new LinkedHashSet<>(duelsByPlayer.values()));
	}

	private static Player participant(UUID id, Player subject) {
		if (subject != null && subject.getUniqueId().equals(id)) {
			return subject;
		}
		return Bukkit.getPlayer(id);
	}

	private static void titleBoth(Duel duel, String title) {
		for (UUID id : new UUID[] { duel.first(), duel.second() }) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				RPTexts.title(player, title, " ", 0, 25, 5);
			}
		}
	}

	private static void announce(Player first, Player second, String raw) {
		int radius = DuelLoader.getAnnounceRadius();
		if (radius <= 0) {
			return;
		}
		Location at = second.getLocation();
		double radiusSq = (double) radius * radius;
		for (Player nearby : at.getWorld().getPlayers()) {
			UUID id = nearby.getUniqueId();
			if (!id.equals(first.getUniqueId()) && !id.equals(second.getUniqueId())
					&& nearby.getLocation().distanceSquared(at) <= radiusSq) {
				RPTexts.send(nearby, raw);
			}
		}
	}

	private static String name(Player player) {
		return DisplayIdentityService.resolveDisplay(player);
	}

	private static String text(String key, String... replacements) {
		String raw = DuelLoader.message(key);
		for (int i = 0; i + 1 < replacements.length; i += 2) {
			raw = raw.replace(replacements[i], replacements[i + 1]);
		}
		return raw;
	}

	private static void send(Player player, String key, String... replacements) {
		RPTexts.send(player, text(key, replacements));
	}

	private static Component button(String label, NamedTextColor color, String command, String hover) {
		return Component.text(label, color)
				.decorate(TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand(command))
				.hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
	}

	static void clear() {
		challengesByChallenger.clear();
		duelsByPlayer.clear();
		downed = player -> false;
		ticker = null;
	}
}
