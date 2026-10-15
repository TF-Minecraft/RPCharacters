package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.tfminecraft.rpcharacters.will.WillScreens.Button;
import net.tfminecraft.rpcharacters.will.WillScreens.Input;
import net.tfminecraft.rpcharacters.will.WillScreens.Screen;

class WillScreensTest {
    private final List<AutoCloseable> statics = new ArrayList<>();
    private final List<DialogActionCallback> callbacks = new ArrayList<>();
    private final List<ClickCallback.Options> options = new ArrayList<>();
    private final List<Object> inputs = new ArrayList<>();
    private final List<String> optionIds = new ArrayList<>();
    private final List<Boolean> optionInitial = new ArrayList<>();
    private DialogBase.Builder base;

    private <T> MockedStatic<T> stub(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @SuppressWarnings("unchecked")
    private void capture() {
        stub(DialogBody.class).when(() -> DialogBody.plainMessage(any(Component.class), anyInt())).thenReturn(null);
        var dialogInputs = stub(DialogInput.class);
        dialogInputs.when(() -> DialogInput.text(anyString(), any(Component.class))).thenAnswer(call -> {
            TextDialogInput.Builder builder = mock(TextDialogInput.Builder.class, RETURNS_SELF);
            TextDialogInput built = mock(TextDialogInput.class);
            when(builder.build()).thenReturn(built);
            inputs.add(built);
            return builder;
        });
        dialogInputs.when(() -> DialogInput.singleOption(anyString(), any(Component.class), anyList())).thenAnswer(call -> {
            SingleOptionDialogInput.Builder builder = mock(SingleOptionDialogInput.Builder.class, RETURNS_SELF);
            SingleOptionDialogInput built = mock(SingleOptionDialogInput.class);
            when(builder.build()).thenReturn(built);
            inputs.add(built);
            return builder;
        });
        stub(SingleOptionDialogInput.OptionEntry.class).when(() -> SingleOptionDialogInput.OptionEntry.create(anyString(),
                any(Component.class), anyBoolean())).thenAnswer(call -> {
                    optionIds.add(call.getArgument(0));
                    optionInitial.add(call.getArgument(2));
                    return mock(SingleOptionDialogInput.OptionEntry.class);
                });
        stub(ActionButton.class).when(() -> ActionButton.builder(any(Component.class)))
                .thenAnswer(call -> mock(ActionButton.Builder.class, RETURNS_SELF));
        stub(DialogAction.class).when(() -> DialogAction.customClick(any(DialogActionCallback.class), any(ClickCallback.Options.class)))
                .thenAnswer(call -> {
                    callbacks.add(call.getArgument(0));
                    options.add(call.getArgument(1));
                    return mock(DialogAction.CustomClickAction.class);
                });
        base = mock(DialogBase.Builder.class, RETURNS_SELF);
        stub(DialogBase.class).when(() -> DialogBase.builder(any(Component.class))).thenReturn(base);
        stub(DialogType.class).when(() -> DialogType.multiAction(anyList(), isNull(), eq(2))).thenReturn(null);
        stub(Dialog.class).when(() -> Dialog.create(any())).thenAnswer(call -> {
            RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
            when(factory.empty()).thenReturn(mock(DialogRegistryEntry.Builder.class, RETURNS_SELF));
            ((Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>>) call.getArgument(0)).accept(factory);
            return mock(Dialog.class);
        });
    }

    private void close() throws Exception {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
    }

    @Test
    void screensRenderTextAndChoiceInputsAndButtonsAnswerOnlyTheirViewerOnce() throws Exception {
        capture();
        try {
            Player player = mock(Player.class);
            List<String> heard = new ArrayList<>();
            WillScreens.show(player, new Screen("Name an heir", Component.text("portrait"), List.of(Component.text("Type a name.")),
                    new Input("heir", "Name", List.of()),
                    List.of(new Button("Find", values -> heard.add(values.apply("heir"))))));
            WillScreens.show(player, new Screen("Several", null, List.of(), new Input("pick", "Character",
                    List.<String[]>of(new String[] {"a", "Kira"}, new String[] {"b", "Kira"})), List.of()));
            WillScreens.show(player, new Screen("Plain", null, List.of(), null, List.of()));
            verify(player, times(3)).showDialog(any(Dialog.class));
            assertEquals(2, inputs.size());
            assertInstanceOf(TextDialogInput.class, inputs.get(0));
            assertInstanceOf(SingleOptionDialogInput.class, inputs.get(1));
            assertEquals(List.of("a", "b"), optionIds);
            assertEquals(List.of(true, false), optionInitial);
            DialogResponseView response = mock(DialogResponseView.class);
            when(response.getText("heir")).thenReturn("Kira");
            callbacks.getFirst().accept(response, mock(Player.class));
            assertTrue(heard.isEmpty());
            callbacks.getFirst().accept(response, player);
            assertEquals(List.of("Kira"), heard);
            assertSame(WillScreens.ONCE, options.getFirst());
        } finally {
            close();
        }
    }
}
