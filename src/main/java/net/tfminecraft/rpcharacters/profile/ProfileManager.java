package net.tfminecraft.rpcharacters.profile;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.playerlist.PlayerListDialogs;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfileManager implements Listener {

	public static void showProfile(Player viewer, Player target, boolean fromCommand) {
		if (viewer == null || target == null) {
			return;
		}

		PlayerData targetData = PlayerManager.get(target);
		RPCharacter targetCharacter = targetData != null ? targetData.getActiveCharacter() : null;
		boolean masked = MaskService.isMasked(target);

		CharacterProfileViewEvent event = new CharacterProfileViewEvent(
				viewer, target, targetCharacter, masked, fromCommand);
		Bukkit.getPluginManager().callEvent(event);
	}

	/**
	 * Opens the character sheet from the player list. Uses the same view rules as
	 * {@code /rpcharacter profile}, except masks, and shows the character others may see.
	 */
	public static void showProfileSheet(Player viewer, Player target) {
		if (viewer == null || target == null) {
			return;
		}
		RPCharacter shown = DisplayIdentityService.resolveSafeCharacter(target);
		CharacterProfileViewEvent event = new CharacterProfileViewEvent(viewer, target, shown,
				MaskService.isMasked(target), true, CharacterProfileViewEvent.Presentation.SHEET);
		Bukkit.getPluginManager().callEvent(event);
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEntityEvent event) {
		if (event.getHand() == EquipmentSlot.OFF_HAND) {
			return;
		}
		if (!(event.getRightClicked() instanceof Player target)) {
			return;
		}

		Player viewer = event.getPlayer();
		if (Cache.profileRequireSneak && !viewer.isSneaking()) {
			return;
		}
		if (Cache.profileRequireEmptyHand && !isHandEmpty(viewer.getInventory().getItem(event.getHand()))) {
			return;
		}

		event.setCancelled(true);
		showProfile(viewer, target, false);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onProfileViewDeny(CharacterProfileViewEvent event) {
		Player viewer = event.getViewer();
		if (viewer == null) {
			event.setCancelled(true);
			return;
		}

		if (!viewer.hasPermission(Cache.profilePermission)) {
			RPTexts.send(viewer, RPTexts.ERROR + "You do not have permission to view character profiles.");
			event.setCancelled(true);
			return;
		}

		if (event.getTargetCharacter() == null) {
			RPTexts.send(viewer, RPTexts.ERROR + "That player has no active character.");
			event.setCancelled(true);
			return;
		}

		// The player list is out of character: refusing masked players there would reveal who is masked.
		if (event.isMasked() && event.getPresentation() != CharacterProfileViewEvent.Presentation.SHEET) {
			RPTexts.send(viewer, RPTexts.ERROR + "That player's identity is concealed.");
			event.setCancelled(true);
			return;
		}

		if (!event.isFromCommand()) {
			if (Cache.profileRequireSneak && !viewer.isSneaking()) {
				event.setCancelled(true);
				return;
			}
			if (Cache.profileRequireEmptyHand) {
				ItemStack mainHand = viewer.getInventory().getItemInMainHand();
				if (!isHandEmpty(mainHand)) {
					event.setCancelled(true);
					return;
				}
			}
		}

		if (ProfileViewCooldownManager.get().isOnCooldown(viewer, Cache.profileViewCooldownSeconds)) {
			int remaining = ProfileViewCooldownManager.get().getRemainingSeconds(viewer);
			RPTexts.send(viewer, RPTexts.ERROR + "Wait " + RPTexts.WARN + remaining + RPTexts.ERROR + "s.");
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onProfileViewDisplay(CharacterProfileViewEvent event) {
		Player viewer = event.getViewer();
		Player target = event.getTarget();
		if (viewer == null || target == null) {
			return;
		}

		List<String> lines = ProfileFormatter.format(event.getTargetCharacter());
		if (event.getPresentation() == CharacterProfileViewEvent.Presentation.SHEET) {
			PlayerListDialogs.openCharacterSheet(viewer, target, lines);
		} else {
			for (String line : lines) {
				RPTexts.send(viewer, line);
			}
		}

		ProfileViewCooldownManager.get().applyCooldown(viewer, Cache.profileViewCooldownSeconds);
	}

	private static boolean isHandEmpty(ItemStack item) {
		return item == null || item.getType().isAir();
	}
}
