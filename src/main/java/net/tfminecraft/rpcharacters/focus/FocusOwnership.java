package net.tfminecraft.rpcharacters.focus;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

final class FocusOwnership {
    private FocusOwnership() {}

    static boolean canStart(Plugin core, Logger logger) {
        if (core == null) return true;
        // Core loads after RPCharacters; isPluginEnabled would miss an installed old owner.
        try (var resource = core.getResource("plugin.yml")) {
            if (resource != null) {
                var descriptor = new YamlConfiguration();
                descriptor.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
                if ("RPCharacters".equals(descriptor.getString("feature-owners.focus"))) return true;
            }
        } catch (Exception ex) {
            logger.warning("Cannot read TFMCCore focus ownership: " + ex.getMessage());
        }
        logger.severe("RPCharacters focus is disabled: installed TFMCCore still owns focus or lacks "
                + "feature-owners.focus: RPCharacters. Install the matching migrated TFMCCore build "
                + "(or remove Core) and restart before using focus consumers.");
        return false;
    }
}
