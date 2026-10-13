package net.tfminecraft.rpcharacters.estate;

import java.util.logging.Level;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Starts estates only when TLibs 2.6.0's estate classes are present (6a rule 11): on an older TLibs the
 * plugin enables without them, deaths still save, and reconcile creates their records once the set is complete.
 */
public final class EstateLink {
    static final String[] REQUIRED = {"net.tfminecraft.tlibs.character.estate.Estates",
            "net.tfminecraft.tlibs.character.estate.EstateService",
            "net.tfminecraft.tlibs.character.CharacterRevivedEvent"};
    private static volatile EstateHooks hooks;

    private EstateLink() {}

    public static EstateHooks current() {
        return hooks;
    }

    static void install(EstateHooks next) {
        hooks = next;
    }

    static boolean present(String... classes) {
        try {
            for (String name : classes) Class.forName(name, false, EstateLink.class.getClassLoader());
            return true;
        } catch (ReflectiveOperationException | LinkageError missing) {
            return false;
        }
    }

    /** Called from {@code onEnable} once characters are loaded. */
    public static void start(RPCharacters plugin) {
        if (!present(REQUIRED)) {
            plugin.getLogger().warning("Estates are off: TLibs 2.6.0 is required. Deaths still save; "
                    + "their estates are created once TLibs is updated.");
            return;
        }
        try {
            install(EstateModule.start(plugin));
        } catch (java.io.IOException | RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.SEVERE, "Estates could not start; deaths still save and reconcile later", error);
        }
    }

    /** Called from {@code onDisable}. */
    public static void stop() {
        EstateHooks running = hooks;
        hooks = null;
        if (running != null) running.stop();
    }
}
