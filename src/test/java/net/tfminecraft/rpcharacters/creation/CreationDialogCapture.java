package net.tfminecraft.rpcharacters.creation;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.mockito.MockedStatic;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.*;
import io.papermc.paper.registry.data.dialog.body.*;
import io.papermc.paper.registry.data.dialog.input.*;
import io.papermc.paper.registry.data.dialog.type.*;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class CreationDialogCapture implements AutoCloseable {
		final List<CapturedDialog> opened = new ArrayList<>();
		final Map<DialogAction, DialogActionCallback> callbacks = new IdentityHashMap<>();
		final Map<DialogAction, ClickCallback.Options> options = new IdentityHashMap<>();
		private final List<MockedStatic<?>> statics = new ArrayList<>();

		CreationDialogCapture() {
			var buttons = mockStatic(ActionButton.class);
			statics.add(buttons);
			buttons.when(() -> ActionButton.builder(any(Component.class))).thenAnswer(call -> {
				var button = mock(ActionButton.class);
				when(button.label()).thenReturn(call.getArgument(0));
				var builder = mock(ActionButton.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(button.tooltip()).thenReturn(c.getArgument(0)); return builder; }).when(builder).tooltip(any());
				doAnswer(c -> { when(button.width()).thenReturn(c.getArgument(0)); return builder; }).when(builder).width(anyInt());
				doAnswer(c -> { when(button.action()).thenReturn(c.getArgument(0)); return builder; }).when(builder).action(any());
				when(builder.build()).thenReturn(button);
				return builder;
			});
			var bases = mockStatic(DialogBase.class);
			statics.add(bases);
			bases.when(() -> DialogBase.builder(any(Component.class))).thenAnswer(call -> {
				var base = mock(DialogBase.class);
				when(base.title()).thenReturn(call.getArgument(0));
				when(base.pause()).thenReturn(true);
				when(base.afterAction()).thenReturn(DialogBase.DialogAfterAction.CLOSE);
				var builder = mock(DialogBase.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(base.pause()).thenReturn(c.getArgument(0)); return builder; }).when(builder).pause(anyBoolean());
				doAnswer(c -> { when(base.afterAction()).thenReturn(c.getArgument(0)); return builder; }).when(builder).afterAction(any());
				doAnswer(c -> { when(base.canCloseWithEscape()).thenReturn(c.getArgument(0)); return builder; }).when(builder).canCloseWithEscape(anyBoolean());
				doAnswer(c -> { when(base.body()).thenReturn(List.copyOf(c.getArgument(0))); return builder; }).when(builder).body(anyList());
				doAnswer(c -> { when(base.inputs()).thenReturn(List.copyOf(c.getArgument(0))); return builder; }).when(builder).inputs(anyList());
			when(builder.build()).thenReturn(base);
				return builder;
			});
			var bodies = mockStatic(DialogBody.class);
			statics.add(bodies);
			bodies.when(() -> DialogBody.plainMessage(any(Component.class), anyInt())).thenAnswer(call -> {
				var body = mock(PlainMessageDialogBody.class);
				when(body.contents()).thenReturn(call.getArgument(0));
				when(body.width()).thenReturn(call.getArgument(1));
				return body;
			});
			var actions = mockStatic(DialogAction.class);
			statics.add(actions);
			actions.when(() -> DialogAction.customClick(any(DialogActionCallback.class), any(ClickCallback.Options.class))).thenAnswer(call -> {
				var action = mock(DialogAction.CustomClickAction.class);
				callbacks.put(action, call.getArgument(0));
				options.put(action, call.getArgument(1));
				return action;
			});
			var inputs = mockStatic(DialogInput.class);
			statics.add(inputs);
			inputs.when(() -> DialogInput.text(anyString(), anyInt(), any(), anyBoolean(), anyString(), anyInt(), any()))
					.thenAnswer(call -> {
						var input = mock(TextDialogInput.class);
						when(input.key()).thenReturn(call.getArgument(0));
						when(input.label()).thenReturn(call.getArgument(2));
						when(input.initial()).thenReturn(call.getArgument(4));
						when(input.maxLength()).thenReturn(call.getArgument(5));
						when(input.multiline()).thenReturn(call.getArgument(6));
						return input;
					});
			var multiline = mockStatic(TextDialogInput.MultilineOptions.class);
			statics.add(multiline);
			multiline.when(() -> TextDialogInput.MultilineOptions.create(any(), any())).thenReturn(mock(TextDialogInput.MultilineOptions.class));
			var entries = mockStatic(SingleOptionDialogInput.OptionEntry.class);
			statics.add(entries);
			entries.when(() -> SingleOptionDialogInput.OptionEntry.create(anyString(), any(), anyBoolean())).thenAnswer(call -> {
				var entry = mock(SingleOptionDialogInput.OptionEntry.class);
				when(entry.id()).thenReturn(call.getArgument(0));
				when(entry.display()).thenReturn(call.getArgument(1));
				when(entry.initial()).thenReturn(call.getArgument(2));
				return entry;
			});
			inputs.when(() -> DialogInput.singleOption(anyString(), anyInt(), anyList(), any(), anyBoolean())).thenAnswer(call -> {
				var input = mock(SingleOptionDialogInput.class);
				when(input.key()).thenReturn(call.getArgument(0));
				when(input.entries()).thenReturn(List.copyOf(call.getArgument(2)));
				when(input.label()).thenReturn(call.getArgument(3));
				return input;
			});
			inputs.when(() -> DialogInput.bool(anyString(), any(), anyBoolean(), anyString(), anyString())).thenAnswer(call -> {
				var input = mock(BooleanDialogInput.class);
				when(input.key()).thenReturn(call.getArgument(0));
				when(input.label()).thenReturn(call.getArgument(1));
				when(input.initial()).thenReturn(call.getArgument(2));
				return input;
			});
			inputs.when(() -> DialogInput.numberRange(anyString(), anyInt(), any(), anyString(), anyFloat(), anyFloat(), any(), any())).thenAnswer(call -> {
				var input = mock(NumberRangeDialogInput.class);
				when(input.key()).thenReturn(call.getArgument(0));
				when(input.label()).thenReturn(call.getArgument(2));
				when(input.initial()).thenReturn(call.getArgument(6));
				return input;
			});
			var types = mockStatic(DialogType.class);
			statics.add(types);
			types.when(() -> DialogType.multiAction(anyList(), any(), anyInt())).thenAnswer(call -> {
				var type = mock(MultiActionType.class);
				when(type.actions()).thenReturn(List.copyOf(call.getArgument(0)));
				when(type.exitAction()).thenReturn(call.getArgument(1));
				when(type.columns()).thenReturn(call.getArgument(2));
				return type;
			});
			var dialogs = mockStatic(Dialog.class);
			statics.add(dialogs);
			dialogs.when(() -> Dialog.create(any())).thenAnswer(call -> {
				var entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(entry.base()).thenReturn(c.getArgument(0)); return entry; }).when(entry).base(any());
				doAnswer(c -> { when(entry.type()).thenReturn(c.getArgument(0)); return entry; }).when(entry).type(any());
				@SuppressWarnings("unchecked")
				RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
				when(factory.empty()).thenReturn(entry);
				java.util.function.Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>> config = call.getArgument(0);
				config.accept(factory);
				var dialog = mock(Dialog.class);
				opened.add(new CapturedDialog(dialog, entry.base(), entry.type()));
				return dialog;
			});
		}

		CapturedDialog last() {
			return opened.getLast();
		}

		void click(ActionButton button, Audience viewer, Map<String, String> values) {
			var response = mock(io.papermc.paper.dialog.DialogResponseView.class);
			when(response.getText(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
			when(response.getBoolean(anyString())).thenAnswer(call -> values.containsKey(call.getArgument(0)) ? Boolean.valueOf(values.get(call.getArgument(0))) : null);
			when(response.getFloat(anyString())).thenAnswer(call -> values.containsKey(call.getArgument(0)) ? Float.valueOf(values.get(call.getArgument(0))) : null);
			callbacks.get(button.action()).accept(response, viewer);
		}

		@Override
		public void close() {
			for (var s : statics.reversed()) s.close();
		}
		record CapturedDialog(Dialog dialog, DialogBase base, DialogType type) {
			List<ActionButton> buttons() { return ((MultiActionType) type).actions(); }
			String bodyText() { return base.body().stream().map(b -> text(((PlainMessageDialogBody) b).contents())).collect(Collectors.joining("\n")); }
			TextDialogInput field(String key) { return (TextDialogInput) base.inputs().stream().filter(input -> input.key().equals(key)).findFirst().orElseThrow(); }
			ActionButton button(String label) { return buttons().stream().filter(button -> text(button.label()).equals(label)).findFirst().orElseThrow(() -> new AssertionError(label + " not in " + buttons().stream().map(b -> text(b.label())).toList())); }
		}
		static String text(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

	}

