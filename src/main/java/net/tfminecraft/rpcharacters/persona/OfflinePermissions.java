package net.tfminecraft.rpcharacters.persona;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;

/** Permission checks for a player who is offline, read from LuckPerms. */
public final class OfflinePermissions {

	private OfflinePermissions() {}

	/**
	 * Loads the player's LuckPerms data with this server's contexts. Blocks on LuckPerms
	 * storage, so call it off the main thread. Returns null when LuckPerms is missing or
	 * the load fails.
	 */
	public static Predicate<String> load(UUID playerUuid) {
		RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
		if (provider == null) {
			return null;
		}
		LuckPerms api = provider.getProvider();
		try {
			CachedPermissionData permissions = api.getUserManager().loadUser(playerUuid).join()
					.getCachedData().getPermissionData(api.getContextManager().getStaticQueryOptions());
			return permission -> permissions.checkPermission(permission).asBoolean();
		} catch (CompletionException e) {
			return null;
		}
	}
}
