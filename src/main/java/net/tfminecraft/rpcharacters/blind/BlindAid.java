package net.tfminecraft.rpcharacters.blind;

import java.io.File;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.ProtocolLibrary;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.tlibs.TLibs;

/** Wires {@link BlindAidService} into the plugin: blind.yml, the Blind trait and cane items. */
public final class BlindAid {

	private static volatile BlindAidSettings settings = BlindAidSettings.defaults();
	private static BlindAidService service;

	private BlindAid() {}

	public static void load(File file) {
		settings = BlindAidSettings.from(YamlConfiguration.loadConfiguration(file));
	}

	public static BlindAidSettings settings() {
		return settings;
	}

	/** Builds the service and registers its listener; call once on enable. */
	public static BlindAidService create(Plugin plugin) {
		GlowPackets glow = null;
		Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
		if (protocolLib != null && protocolLib.isEnabled()) {
			glow = new GlowPackets(ProtocolLibrary.getProtocolManager());
		} else {
			plugin.getLogger().warning("ProtocolLib not found: blind characters won't hear creatures as outlines.");
		}
		service = new BlindAidService(plugin, BlindAid::settings, BlindAid::isBlind, BlindAid::isCane, glow,
				WorldProbe::new, System::currentTimeMillis, Bukkit::getOnlinePlayers, Bukkit::getEntity);
		Bukkit.getPluginManager().registerEvents(service, plugin);
		return service;
	}

	public static void start() {
		if (service != null) service.start();
	}

	public static void shutdown() {
		if (service != null) service.stop();
	}

	static boolean isBlind(Player p) {
		PlayerData pd = PlayerManager.get(p);
		if (pd == null || !pd.hasActiveCharacter()) return false;
		for (Trait trait : pd.getActiveCharacter().getTraits()) {
			if (settings.traits().contains(trait.getId().toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}

	static boolean isCane(ItemStack item, List<String> paths) {
		for (String path : paths) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) return true;
		}
		return false;
	}
}
