package net.tfminecraft.rpcharacters.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;

class ChatCommandRegistryTest {
    ServerMock server;
    RuntimeTestState state;
    Plugin plugin;
    MockedStatic<ChatLoader> channels;
    List<String> labels = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(ChatCommandRegistry.class);
        // Every test has a fresh server and command map.
        var registered = ChatCommandRegistry.class.getDeclaredField("registered");
        registered.setAccessible(true); ((Set<?>) registered.get(null)).clear();
        plugin = MockBukkit.createMockPlugin("ChatTest");
        channels = mockStatic(ChatLoader.class);
        channels.when(ChatLoader::getChannelCommands).thenAnswer(call -> labels);
    }
    @AfterEach void cleanup() { channels.close(); state.close(); MockBukkit.unmock(); }

    @Test void synchronizationRegistersNormalizedCommandsAndRemovesEveryObsoleteAlias() {
        labels.addAll(List.of("LOOC", "looc"));
        ChatCommandRegistry.sync(plugin);
        Command command = server.getCommandMap().getCommand("looc");
        assertNotNull(command);
        assertSame(command, server.getCommandMap().getCommand("chattest:looc"));
        assertEquals("RP chat channel", command.getDescription());
        assertInstanceOf(ChatChannelExecutor.class, ((PluginCommand) command).getExecutor());
        labels.clear(); labels.add("rooc"); ChatCommandRegistry.sync(plugin);
        assertNull(server.getCommandMap().getCommand("looc"), "Reload must remove the old bare command");
        assertNull(server.getCommandMap().getCommand("chattest:looc"), "Reload must remove the old namespaced command");
        assertNotNull(server.getCommandMap().getCommand("rooc"));
        labels.clear(); ChatCommandRegistry.sync(plugin);
        assertNull(server.getCommandMap().getCommand("chattest:rooc"));
    }

    @Test void removingACollidingChannelPreservesTheOtherPluginsCommand() {
        Command existing = new Command("looc") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
        };
        server.getCommandMap().register("other", existing);
        labels.add("looc"); ChatCommandRegistry.sync(plugin);
        assertSame(existing, server.getCommandMap().getCommand("looc"));
        assertNotSame(existing, server.getCommandMap().getCommand("chattest:looc"));
        labels.clear(); ChatCommandRegistry.sync(plugin);
        assertSame(existing, server.getCommandMap().getCommand("looc"));
        assertSame(existing, server.getCommandMap().getCommand("other:looc"));
        assertNull(server.getCommandMap().getCommand("chattest:looc"));
    }

    @Test void failedRegistrationDoesNotPreventALaterRetry() {
        labels.add("looc");
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(org.bukkit.Bukkit::getCommandMap).thenThrow(new IllegalStateException("command map unavailable"));
            assertDoesNotThrow(() -> ChatCommandRegistry.sync(plugin));
        }
        ChatCommandRegistry.sync(plugin);
        assertNotNull(server.getCommandMap().getCommand("looc"));
    }

    @Test void failedRemovalKeepsOwnershipForTheNextReload() {
        labels.add("looc"); ChatCommandRegistry.sync(plugin);
        labels.clear(); labels.add("rooc");
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(org.bukkit.Bukkit::getCommandMap).thenThrow(new IllegalStateException("command map unavailable"));
            assertDoesNotThrow(() -> ChatCommandRegistry.sync(plugin));
        }
        assertNotNull(server.getCommandMap().getCommand("looc"));
        assertNull(server.getCommandMap().getCommand("rooc"));
        ChatCommandRegistry.sync(plugin);
        assertNull(server.getCommandMap().getCommand("chattest:looc"));
        assertNotNull(server.getCommandMap().getCommand("rooc"));
    }
}
