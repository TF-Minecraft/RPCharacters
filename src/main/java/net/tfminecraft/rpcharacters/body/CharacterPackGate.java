package net.tfminecraft.rpcharacters.body;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/** Holds selection until Core confirms this connection's entire required pack request. */
final class CharacterPackGate {
    // Plain Bukkit metadata avoids a shared classloader dependency on the pack provider.
    static final String KEY = "tfmccore:required-pack";
    static final long TIMEOUT = Duration.ofSeconds(400).toNanos();
    private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();
    private final Plugin plugin;
    private final boolean enabled;
    private final LongSupplier clock;
    private final Map<UUID, Waiting> waiting = new HashMap<>();

    private static final class Waiting {
        final Player player;
        final BooleanSupplier current;
        final Runnable resume;
        long deadline;
        int revision;
        int visibleRevision;

        Waiting(Player player, BooleanSupplier current, Runnable resume) {
            this.player = player;
            this.current = current;
            this.resume = resume;
        }
    }

    CharacterPackGate(Plugin plugin, boolean enabled) { this(plugin, enabled, System::nanoTime); }

    CharacterPackGate(Plugin plugin, boolean enabled, LongSupplier clock) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.clock = clock;
    }

    boolean ready(Player player) { return !enabled || state(player).equals("ready"); }

    private String state(Player player) {
        Plugin core = Bukkit.getPluginManager().getPlugin("TFMCCore");
        if (core == null || !core.isEnabled()) return "unavailable";
        return player.getMetadata(KEY).stream().filter(value -> value.getOwningPlugin() == core)
                .map(value -> value.value() instanceof String status ? status : "unavailable")
                .findFirst().orElse("unavailable");
    }

    void await(Player player, BooleanSupplier current, Runnable resume) {
        Waiting previous = waiting.get(player.getUniqueId());
        if (previous != null && previous.player == player) return;
        Waiting request = new Waiting(player, current, resume);
        request.deadline = clock.getAsLong() + TIMEOUT;
        waiting.put(player.getUniqueId(), request);
        show(request, false, false);
        poll(request);
    }

    private boolean current(Waiting request) {
        return waiting.get(request.player.getUniqueId()) == request && request.player.isOnline()
                && request.current.getAsBoolean();
    }

    private void poll(Waiting request) {
        if (!current(request)) {
            waiting.remove(request.player.getUniqueId(), request);
            return;
        }
        String status = state(request.player);
        if (status.equals("ready")) {
            waiting.remove(request.player.getUniqueId(), request);
            request.player.resetTitle();
            request.resume.run();
        } else if (!status.equals("loading") || clock.getAsLong() >= request.deadline) {
            show(request, true, status.equals("failed"));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> poll(request), 10L);
        }
    }

    private void retry(Waiting request) {
        String status = state(request.player);
        if (status.equals("failed")) {
            // Minecraft must receive our screen before its pack confirmation prompt.
            show(request, false, false);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tfmccore:tcore packretry " + request.player.getName());
            status = state(request.player);
        } else {
            // An external retry may already have opened that prompt. Never cover it.
            ++request.revision;
        }
        if (!status.equals("loading") && !status.equals("ready")) {
            show(request, true, status.equals("failed"));
            return;
        }
        request.deadline = clock.getAsLong() + TIMEOUT;
        poll(request);
    }

    private void show(Waiting request, boolean failed, boolean retry) {
        int revision = ++request.revision;
        request.visibleRevision = revision;
        Component title = Component.text(failed ? "Could not load TFMC" : "Loading TFMC",
                failed ? CharacterSelectView.WARN : CharacterSelectView.TEAL);
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(failed ? "The resource pack did not finish loading."
                        : "Preparing textures, models and sounds…", CharacterSelectView.PALE), 300),
                DialogBody.plainMessage(Component.text(failed
                        ? "Enable server resource packs in your server settings, then try again."
                        : "Accept the resource pack when Minecraft asks.\nCharacter selection will open automatically.",
                        CharacterSelectView.MUTED), 300));
        ActionButton leave = button(request, () -> revision == request.visibleRevision,
                "Leave", () -> request.player.kickPlayer("See you next time."));
        List<ActionButton> buttons = retry
                ? List.of(button(request, () -> revision == request.revision, "Retry", () -> retry(request)), leave) : List.of(leave);
        request.player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).canCloseWithEscape(false).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE).body(body).build())
                .type(DialogType.multiAction(buttons, null, buttons.size()))));
        if (failed) {
            watchFailure(request, revision, retry, clock.getAsLong() + Duration.ofMinutes(5).toNanos());
        }
    }

    private void watchFailure(Waiting request, int revision, boolean retry, long renewAt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!current(request) || revision != request.revision) return;
            String status = state(request.player);
            if (status.equals("ready") || status.equals("loading") && retry) {
                // Staff may retry from console without using this dialog.
                retry(request);
            } else if (retry != status.equals("failed") || clock.getAsLong() >= renewAt) {
                show(request, true, status.equals("failed"));
            } else {
                watchFailure(request, revision, retry, renewAt);
            }
        }, 20L);
    }

    private ActionButton button(Waiting request, BooleanSupplier valid, String label, Runnable action) {
        return ActionButton.builder(Component.text(label, CharacterSelectView.PALE)).width(110)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience == request.player && valid.getAsBoolean() && current(request)) action.run();
                }, CALLBACKS)).build();
    }

    void quit(Player player) {
        Waiting request = waiting.get(player.getUniqueId());
        if (request != null && request.player == player) waiting.remove(player.getUniqueId());
    }

    void close() {
        for (Waiting request : waiting.values()) request.player.closeDialog();
        waiting.clear();
    }
}
