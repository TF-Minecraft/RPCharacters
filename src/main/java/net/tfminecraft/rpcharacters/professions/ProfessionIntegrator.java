package net.tfminecraft.rpcharacters.professions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

public final class ProfessionIntegrator {
	private ProfessionIntegrator() {}

	public static void apply(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		for (String upgradeId : character.getProfessionUpgrades()) {
			ProfessionUpgradeDefinition upgrade = ProfessionRegistry.getUpgrade(upgradeId);
			if (upgrade == null || !"permission".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			for (String perm : upgrade.getUnlocks()) {
				givePermission(player, perm);
			}
		}
	}

	public static void remove(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		for (String upgradeId : character.getProfessionUpgrades()) {
			ProfessionUpgradeDefinition upgrade = ProfessionRegistry.getUpgrade(upgradeId);
			if (upgrade == null || !"permission".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			for (String perm : upgrade.getUnlocks()) {
				removePermission(player, perm);
			}
		}
	}

	public static void applyUpgrade(Player player, ProfessionUpgradeDefinition upgrade) {
		if (player == null || upgrade == null || !"permission".equalsIgnoreCase(upgrade.getType())) {
			return;
		}
		for (String perm : upgrade.getUnlocks()) {
			givePermission(player, perm);
		}
	}

	public static void removeUpgrade(Player player, ProfessionUpgradeDefinition upgrade) {
		if (player == null || upgrade == null || !"permission".equalsIgnoreCase(upgrade.getType())) {
			return;
		}
		for (String perm : upgrade.getUnlocks()) {
			removePermission(player, perm);
		}
	}

	public static void givePermission(Player player, String perm) {
		RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
		if (provider == null) {
			return;
		}
		LuckPerms api = provider.getProvider();
		User user = api.getPlayerAdapter(Player.class).getUser(player);
		user.data().add(Node.builder(perm)
				.withContext(DefaultContextKeys.SERVER_KEY, Cache.professionPermContext)
				.build());
		api.getUserManager().saveUser(user);
	}

	public static void removePermission(Player player, String perm) {
		RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
		if (provider == null) {
			return;
		}
		LuckPerms api = provider.getProvider();
		User user = api.getPlayerAdapter(Player.class).getUser(player);
		if (player.hasPermission(perm)) {
			user.data().remove(Node.builder(perm)
					.withContext(DefaultContextKeys.SERVER_KEY, Cache.professionPermContext)
					.build());
		}
		api.getUserManager().saveUser(user);
	}
}
