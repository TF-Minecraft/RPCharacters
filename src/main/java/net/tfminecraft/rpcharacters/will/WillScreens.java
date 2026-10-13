package net.tfminecraft.rpcharacters.will;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/** The will screens as plain data, and their Paper dialog rendering. Callbacks are one-use for ten minutes. */
public final class WillScreens {
    static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1)
            .lifetime(Duration.ofMinutes(10)).build();
    static final int WIDTH = 300;

    /** A button; its action reads input values by key. */
    record Button(String label, Consumer<Function<String, String>> action) {}

    /** A text field ({@code options} empty) or a single-option list of id and label pairs. */
    record Input(String key, String label, List<String[]> options) {}

    record Screen(String title, List<String> lines, Input input, List<Button> buttons) {}

    private WillScreens() {}

    static void show(Player player, Screen screen) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : screen.lines()) body.add(DialogBody.plainMessage(Component.text(line), WIDTH));
        List<DialogInput> inputs = new ArrayList<>();
        Input input = screen.input();
        if (input != null && input.options().isEmpty()) {
            inputs.add(DialogInput.text(input.key(), Component.text(input.label())).maxLength(32).build());
        } else if (input != null) {
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            for (String[] option : input.options()) {
                entries.add(SingleOptionDialogInput.OptionEntry.create(option[0], Component.text(option[1]), entries.isEmpty()));
            }
            inputs.add(DialogInput.singleOption(input.key(), Component.text(input.label()), entries).build());
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Button button : screen.buttons()) {
            buttons.add(ActionButton.builder(Component.text(button.label())).width(150)
                    .action(DialogAction.customClick((response, audience) -> {
                        if (audience == player) button.action().accept(response::getText);
                    }, ONCE)).build());
        }
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(screen.title())).body(body).inputs(inputs).build())
                .type(DialogType.multiAction(buttons, null, 2))));
    }
}
