package net.tfminecraft.rpcharacters.playerlist;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.bukkit.plugin.Plugin;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

/**
 * Keeps a player who is playing a hidden character out of other players' TAB
 * list and {@code /players}, while their body and nametag stay visible in the
 * world. Holders of the hidden-character permission still see them.
 * <p>
 * Paper only lists or unlists a player the viewer can currently see, and
 * remembers an unlisted player across later hide/show calls. The selection
 * room hides players from each other until they press Play, so a change that
 * cannot be applied yet waits for the {@link PlayerShowEntityEvent} that makes
 * them visible, which fires after Paper sent the TAB entry and in the same tick.
 * <p>
 * Work runs only on joins, quits, character sessions, the staff toggle,
 * LuckPerms permission recalculations and player show events. Nothing runs per tick, and while nobody plays a hidden
 * character every handler returns after one empty-set check.
 */
public final class HiddenCharacterListing implements Listener {

	/** Players currently on a hidden character. */
	private static final Set<UUID> CONCEALED = new HashSet<>();
	/** Target to the viewers this class unlisted them for, so only those are listed again. */
	private static final Map<UUID, Set<UUID>> UNLISTED = new HashMap<>();

	/** Whether {@code target} must be left out of {@code viewer}'s lists. */
	public static boolean isConcealedFrom(Player viewer, Player target) {
		return !viewer.equals(target) && isConcealed(target) && !canSeeHidden(viewer);
	}

	public static boolean isConcealed(Player target) {
		return CONCEALED.contains(target.getUniqueId());
	}

	/**
	 * Re-checks one player after their character session or hidden flag changed,
	 * and updates every other player's TAB list to match.
	 */
	public static void refresh(Player target) {
		if (target == null) {
			// Session events for account data loaded without a player.
			return;
		}
		UUID id = target.getUniqueId();
		boolean conceal = target.isOnline() && playsHidden(target);
		boolean changed = conceal ? CONCEALED.add(id) : CONCEALED.remove(id);
		if (!changed && !conceal) {
			return;
		}
		for (Player viewer : List.copyOf(Bukkit.getOnlinePlayers())) {
			apply(viewer, target);
		}
	}

	/** Brings one viewer's TAB entry for {@code target} in line, if Paper allows it now. */
	private static void apply(Player viewer, Player target) {
		if (viewer.equals(target) || !viewer.canSee(target)) {
			return;
		}
		Set<UUID> viewers = UNLISTED.get(target.getUniqueId());
		boolean unlistedByUs = viewers != null && viewers.contains(viewer.getUniqueId());
		if (isConcealedFrom(viewer, target)) {
			if (viewer.unlistPlayer(target) || !viewer.isListed(target)) {
				UNLISTED.computeIfAbsent(target.getUniqueId(), key -> new HashSet<>()).add(viewer.getUniqueId());
			}
		} else if (unlistedByUs) {
			viewer.listPlayer(target);
			forget(viewers, target.getUniqueId(), viewer.getUniqueId());
		}
	}

	private static void forget(Set<UUID> viewers, UUID target, UUID viewer) {
		viewers.remove(viewer);
		if (viewers.isEmpty()) {
			UNLISTED.remove(target);
		}
	}

	/** Hidden characters count only while a character session is running, not in the selection room. */
	static boolean playsHidden(Player player) {
		CharacterTransitions transitions = RPCharacters.getCharacterTransitions();
		if (transitions == null || transitions.currentSession(player).isEmpty()) {
			return false;
		}
		PlayerData data = PlayerManager.get(player);
		RPCharacter active = data == null ? null : data.getActiveCharacter();
		return active != null && active.isHidden();
	}

	private static boolean canSeeHidden(Player viewer) {
		return viewer.hasPermission(Cache.personaCharacterHiddenPermission);
	}

	private static boolean idle() {
		return CONCEALED.isEmpty() && UNLISTED.isEmpty();
	}

	/**
	 * A viewer who gains or loses the hidden-character permission while online
	 * sees the change at once instead of on their next join. LuckPerms may fire
	 * this off the main thread, so the work runs on the next tick.
	 */
	public static void hookPermissionChanges(Plugin plugin) {
		try {
			LuckPermsProvider.get().getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
					event -> {
						UUID viewer = event.getUser().getUniqueId();
						Bukkit.getScheduler().runTask(plugin, () -> reconcileViewer(viewer));
					});
		} catch (IllegalStateException | NoClassDefFoundError unavailable) {
			// Without LuckPerms, a permission change applies on the viewer's next join.
		}
	}

	static void reconcileViewer(UUID viewerId) {
		Player viewer = Bukkit.getPlayer(viewerId);
		if (viewer == null || idle()) {
			return;
		}
		for (UUID id : List.copyOf(CONCEALED)) {
			Player target = Bukkit.getPlayer(id);
			if (target != null) {
				apply(viewer, target);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onSessionStart(CharacterSessionStartEvent event) {
		refresh(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onSessionEnd(CharacterSessionEndEvent event) {
		refresh(event.getPlayer());
	}

	/** Finishes a change that had to wait until the viewer could see the target. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onShow(PlayerShowEntityEvent event) {
		if (idle() || !(event.getEntity() instanceof Player target)) {
			return;
		}
		apply(event.getPlayer(), target);
	}

	/**
	 * Paper sends a joining player the TAB list after this event, honouring
	 * unlisted entries, so hidden characters never reach them.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		if (CONCEALED.isEmpty()) {
			return;
		}
		Player viewer = event.getPlayer();
		for (UUID id : List.copyOf(CONCEALED)) {
			Player target = Bukkit.getPlayer(id);
			if (target != null) {
				apply(viewer, target);
			}
		}
	}

	/**
	 * A leaving hidden player is listed again first: Paper keeps unlisted ids
	 * per viewer, which would otherwise hide their next login. A leaving viewer's
	 * entries go with them.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		if (idle()) {
			return;
		}
		Player leaving = event.getPlayer();
		UUID id = leaving.getUniqueId();
		if (CONCEALED.remove(id)) {
			for (Player viewer : List.copyOf(Bukkit.getOnlinePlayers())) {
				apply(viewer, leaving);
			}
		}
		for (UUID target : List.copyOf(UNLISTED.keySet())) {
			forget(UNLISTED.get(target), target, id);
		}
	}

	/** For tests. */
	static void reset() {
		CONCEALED.clear();
		UNLISTED.clear();
	}
}
