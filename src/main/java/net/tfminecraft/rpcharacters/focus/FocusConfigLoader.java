package net.tfminecraft.rpcharacters.focus;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class FocusConfigLoader {

    private FocusConfigLoader() {}

    public static boolean load(File file) {
        return load(file, RPCharacters.plugin.getLogger());
    }

    static boolean load(File file, java.util.logging.Logger logger) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            logger.severe("[RPCharacters] Failed to load focus.yml: " + ex.getMessage());
            return false;
        }
        FocusConfig.max = Math.max(1, config.getInt("max", FocusConfig.max));
        FocusConfig.basePerHour = config.getInt("base_per_hour", FocusConfig.basePerHour);
        FocusConfig.regenIntervalTicks = Math.max(1L, config.getLong("regen_interval_ticks", FocusConfig.regenIntervalTicks));
        FocusConfig.offlineRegen = config.getBoolean("offline_regen", FocusConfig.offlineRegen);
        FocusConfig.regenBonuses.clear();
        for (Map<?, ?> map : config.getMapList("regen_bonuses")) {
            String id = map.get("mmocore_id") != null ? String.valueOf(map.get("mmocore_id")) : "";
            double extra = 0;
            if (map.get("extra_per_hour_per_point") instanceof Number number) {
                extra = number.doubleValue();
            }
            if (!id.isBlank()) {
                FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus(id, extra));
            }
        }
        return true;
    }
}
