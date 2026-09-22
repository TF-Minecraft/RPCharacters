package net.tfminecraft.rpcharacters.professions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.ProfessionLoader;
import net.tfminecraft.rpcharacters.loaders.ProfessionsGlobalLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public class ProfessionCommandHandler implements CommandExecutor, TabCompleter {
	public static final String COMMAND = "profession";

	private final ProfessionInventoryManager inventoryManager = new ProfessionInventoryManager();

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!COMMAND.equalsIgnoreCase(command.getName())) {
			return false;
		}
		if (args.length == 0) {
			if (!(sender instanceof Player player)) {
				RPTexts.send(sender, RPTexts.ERROR + "Players only.");
				return true;
			}
			inventoryManager.openMainMenu(player);
			return true;
		}
		String sub = args[0].toLowerCase();
		switch (sub) {
			case "reload" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				reloadProfessions();
				reapplyActiveCharacterPerms();
				RPTexts.send(sender, RPTexts.SUCCESS + "Profession configs reloaded.");
				return true;
			}
			case "top" -> {
				if (args.length < 2) {
					RPTexts.send(sender, RPTexts.ERROR + "Usage: /profession top <profession>");
					return true;
				}
				ProfessionTopService.showTop(sender, args[1]);
				return true;
			}
			case "givepoints" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				if (args.length < 4) {
					RPTexts.send(sender, RPTexts.ERROR + "Usage: /profession givepoints <profession> <player> <amount>");
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[2]);
				ProfessionDefinition profession = ProfessionRegistry.getProfession(args[1]);
				if (target == null || profession == null) {
					RPTexts.send(sender, RPTexts.ERROR + "Invalid player or profession.");
					return true;
				}
				int amount = Integer.parseInt(args[3]);
				ProfessionPointService.grantPoints(target, profession.getId(), amount);
				RPTexts.send(sender, RPTexts.SUCCESS + "Gave " + target.getName() + " " + RPTexts.WARN + amount
						+ RPTexts.SUCCESS + " lifetime points in " + RPTexts.INFO + profession.getName());
				return true;
			}
			case "removeupgrade" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				if (args.length < 3) {
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[1]);
				ProfessionUpgradeDefinition upgrade = ProfessionRegistry.getUpgrade(args[2]);
				if (target == null || upgrade == null) {
					RPTexts.send(sender, RPTexts.ERROR + "Invalid player or upgrade.");
					return true;
				}
				removeUpgradeFromActiveCharacter(target, upgrade);
				RPTexts.send(sender, RPTexts.ERROR + "Removed upgrade " + upgrade.getId() + " from " + target.getName());
				return true;
			}
			case "reset" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				if (args.length < 2) {
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[1]);
				if (target == null) {
					return true;
				}
				resetActiveCharacterUpgrades(target, false);
				RPTexts.send(sender, RPTexts.ERROR + "Reset profession upgrades for " + target.getName());
				return true;
			}
			case "restoreall" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				for (Player online : Bukkit.getOnlinePlayers()) {
					PlayerData pd = PlayerManager.get(online);
					if (pd == null) {
						continue;
					}
					RPCharacter character = pd.getActiveCharacter();
					if (character != null) {
						ProfessionIntegrator.apply(online, character);
					}
				}
				RPTexts.send(sender, RPTexts.SUCCESS + "Restoring perms");
				return true;
			}
			case "refund" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				if (args.length < 2) {
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[1]);
				if (target == null) {
					RPTexts.send(sender, RPTexts.ERROR + "No player found");
					return true;
				}
				refundActiveCharacter(target);
				RPTexts.send(sender, RPTexts.SUCCESS + "Refunding all upgrades for " + target.getName());
				return true;
			}
			case "fixperms" -> {
				if (!ProfessionPermissions.isAdmin(sender)) {
					RPTexts.send(sender, RPTexts.ERROR + "You do not have access to this command!");
					return true;
				}
				fixPerms(sender);
				return true;
			}
			case "confirm" -> {
				if (!(sender instanceof Player player)) {
					return true;
				}
				ProfessionUpgradeDefinition upgrade = ProfessionListener.pendingRemoval.remove(player);
				if (upgrade == null) {
					RPTexts.send(player, RPTexts.ERROR + "Nothing to confirm.");
					return true;
				}
				removeUpgradeFromActiveCharacter(player, upgrade);
				RPTexts.send(player, RPTexts.ERROR + "Lost the " + RPTexts.WARN
						+ upgrade.getMenuItem().getItemMeta().getDisplayName() + RPTexts.ERROR + " upgrade!");
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	public static void reloadProfessions() {
		File dataFolder = RPCharacters.plugin.getDataFolder();
		new ProfessionsGlobalLoader().load(new File(dataFolder, "professions.yml"));
		ProfessionLoader.reload(new File(dataFolder, "professions"));
	}

	public static void reapplyActiveCharacterPerms() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			PlayerData pd = PlayerManager.get(player);
			if (pd == null) {
				continue;
			}
			RPCharacter character = pd.getActiveCharacter();
			if (character != null) {
				ProfessionIntegrator.remove(player, character);
				ProfessionIntegrator.apply(player, character);
			}
		}
	}

	public static void removeUpgradeFromActiveCharacter(Player player, ProfessionUpgradeDefinition upgrade) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null || !character.hasProfessionUpgrade(upgrade.getId())) {
			return;
		}
		ProfessionIntegrator.removeUpgrade(player, upgrade);
		character.removeProfessionUpgrade(upgrade.getId());
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void resetActiveCharacterUpgrades(Player player, boolean message) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null) {
			return;
		}
		for (String upgradeId : new java.util.ArrayList<>(character.getProfessionUpgrades())) {
			ProfessionUpgradeDefinition upgrade = ProfessionRegistry.getUpgrade(upgradeId);
			if (upgrade == null) {
				continue;
			}
			if (message) {
				RPTexts.send(player, RPTexts.ERROR + "You lost the upgrade " + upgrade.getId());
			}
			ProfessionIntegrator.removeUpgrade(player, upgrade);
		}
		character.clearProfessionUpgrades();
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void refundActiveCharacter(Player player) {
		resetActiveCharacterUpgrades(player, true);
		PlayerData pd = PlayerManager.get(player);
		if (pd != null) {
			pd.clearAccountProfessionPoints();
			pd.setProfessionPointsInitialized(false);
		}
		ProfessionPointService.bootstrapLifetimeFromMmoCore(player);
	}

	private static void fixPerms(CommandSender sender) {
		LuckPerms luckPerms = LuckPermsProvider.get();
		for (Player player : Bukkit.getOnlinePlayers()) {
			PlayerData pd = PlayerManager.get(player);
			RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
			luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
				boolean changed = false;
				for (Node node : user.data().toCollection()) {
					if (node instanceof PermissionNode permissionNode) {
						String permission = permissionNode.getPermission();
						if (permission.toLowerCase().contains("professions")
								&& node.getContexts().contains(DefaultContextKeys.SERVER_KEY, Cache.professionPermContext)) {
							user.data().remove(node);
							changed = true;
						}
					}
				}
				if (changed) {
					luckPerms.getUserManager().saveUser(user);
				}
				new BukkitRunnable() {
					@Override
					public void run() {
						if (character != null) {
							ProfessionIntegrator.apply(player, character);
						}
					}
				}.runTaskLater(RPCharacters.plugin, 2L);
			});
		}
		RPTexts.send(sender, RPTexts.SUCCESS + "Fixing profession permissions for online players...");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> subs = new ArrayList<>();
			subs.add("top");
			subs.add("confirm");
			if (ProfessionPermissions.isAdmin(sender)) {
				subs.add("reload");
				subs.add("givepoints");
				subs.add("removeupgrade");
				subs.add("reset");
				subs.add("restoreall");
				subs.add("refund");
				subs.add("fixperms");
			}
			return filter(subs, args[0]);
		}

		String sub = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2) {
			if (sub.equals("top") || (sub.equals("givepoints") && ProfessionPermissions.isAdmin(sender))) {
				return filter(professionIds(), args[1]);
			}
			if ((sub.equals("removeupgrade") || sub.equals("reset") || sub.equals("refund"))
					&& ProfessionPermissions.isAdmin(sender)) {
				return filter(onlinePlayerNames(), args[1]);
			}
			return Collections.emptyList();
		}

		if (args.length == 3 && ProfessionPermissions.isAdmin(sender)) {
			if (sub.equals("givepoints")) {
				return filter(onlinePlayerNames(), args[2]);
			}
			if (sub.equals("removeupgrade")) {
				return filter(upgradeIds(), args[2]);
			}
		}

		return Collections.emptyList();
	}

	private static List<String> professionIds() {
		List<String> ids = new ArrayList<>();
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			ids.add(profession.getId());
		}
		return ids;
	}

	private static List<String> upgradeIds() {
		List<String> ids = new ArrayList<>();
		for (ProfessionUpgradeDefinition upgrade : ProfessionRegistry.getUpgrades()) {
			ids.add(upgrade.getId());
		}
		return ids;
	}

	private static List<String> onlinePlayerNames() {
		List<String> names = new ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			names.add(online.getName());
		}
		return names;
	}

	private static List<String> filter(List<String> options, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return new ArrayList<>(options);
		}
		String lower = prefix.toLowerCase(Locale.ROOT);
		return options.stream()
				.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
				.collect(Collectors.toList());
	}
}
