package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.chat.ChatCommandRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ChatAndPartyConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    MockedStatic<ChatCommandRegistry> registry;
    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, ChatLoader.class, PartyLoader.class);
        registry = mockStatic(ChatCommandRegistry.class); RPCharacters.plugin = mock(RPCharacters.class);
    }
    @AfterEach void restore() { registry.close(); state.close(); MockBukkit.unmock(); }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }

    @Test void channelsResolveCommandsAndReloadReplacesRegistries() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        Cache.chatSwitchableChannels = List.of(); Cache.chatToggleableChannels = List.of();
        new ChatLoader().load(yaml("""
            default: RP
            bypass-cooldown-perm: chat.bypass
            no-character-message: Missing
            channel-switcher: {enabled: false, switchable-channels: [RP, IRIS, RP, '']}
            channel-toggler: {enabled: false, toggleable-channels: [OOC, OOC, '']}
            channels:
              scalar: ignored
              RP: {commands: [rp, speak, '', speak]}
              IRIS: {commands: [iris, speak]}
              OOC: {commands: [ooc]}
            """));
        assertEquals("chat.bypass", Cache.chatBypassCooldownPermission); assertEquals("Missing", Cache.chatNoCharacterMessage);
        assertFalse(Cache.chatChannelSwitcherEnabled); assertFalse(Cache.chatChannelTogglerEnabled);
        assertEquals(List.of("rp", "iris"), Cache.chatSwitchableChannels); assertEquals(List.of("ooc"), Cache.chatToggleableChannels);
        assertSame(ChatLoader.getChannel("rp"), ChatLoader.getDefaultChannel());
        assertNotNull(ChatLoader.getChannel("IRIS")); assertEquals("iris", ChatLoader.resolveChannelFromCommand("IRIS"));
        assertNull(ChatLoader.getChannel(null)); assertNull(ChatLoader.getChannel("missing")); assertNull(ChatLoader.resolveChannelFromCommand(null));
        assertEquals(List.of("rp", "iris", "ooc", "speak"), ChatLoader.getChannelCommands());
        assertThrows(UnsupportedOperationException.class, () -> ChatLoader.getChannelCommands().clear());
        registry.verify(() -> ChatCommandRegistry.sync(RPCharacters.plugin));
        for (String defaultId : List.of("iris", "ooc", "speak", "rp")) {
            Cache.chatDefaultChannel = defaultId; assertEquals(defaultId, ChatLoader.getChannelCommands().getFirst());
        }
        Cache.chatDefaultChannel = null; assertEquals("rp", ChatLoader.getChannelCommands().getFirst());
        new ChatLoader().load(yaml("{}")); assertTrue(ChatLoader.getChannelCommands().isEmpty()); assertNull(ChatLoader.getChannel("rp"));
        assertEquals(List.of("rp", "iris"), Cache.chatSwitchableChannels);
    }

    @Test void defaultChannelSwitchersPopulateAbsentAndEmptySections() throws Exception {
        for (String content : List.of("{}", "channel-switcher: {switchable-channels: []}\nchannel-toggler: {toggleable-channels: []}")) {
            Cache.chatSwitchableChannels = List.of(); Cache.chatToggleableChannels = List.of();
            new ChatLoader().load(yaml(content));
            assertEquals(List.of("rp", "ooc", "looc", "whisper", "shout", "yell", "action"), Cache.chatSwitchableChannels);
            assertEquals(List.of("looc", "ooc", "helper", "admin"), Cache.chatToggleableChannels);
            new ChatLoader().load(yaml(content)); assertEquals(7, Cache.chatSwitchableChannels.size());
        }
        RPCharacters.plugin = null; assertDoesNotThrow(() -> new ChatLoader().load(yaml("{}")));
    }

    @Test void configurableChatMessagesHaveDistinctDestinations() throws Exception {
        new ChatLoader().load(yaml("""
            messages:
              run-as-player: Players
              channel-switch-disabled: NoSwitch
              channel-toggle-disabled: NoToggle
              invalid-use: Usage
              invalid-channel: Unknown
              already-switched: Already
              switched: Switched
              current-channel: Current
              toggled-on: Enabled
              toggled-off: Disabled
              cant-use-when-toggled-off: Muted
            """));
        assertEquals("Players", Cache.chatRunAsPlayerMessage); assertEquals("NoSwitch", Cache.chatChannelSwitchDisabledMessage);
        assertEquals("NoToggle", Cache.chatChannelToggleDisabledMessage); assertEquals("Usage", Cache.chatChannelInvalidUseMessage);
        assertEquals("Unknown", Cache.chatChannelInvalidChannelMessage); assertEquals("Already", Cache.chatChannelAlreadySwitchedMessage);
        assertEquals("Switched", Cache.chatChannelSwitchedMessage); assertEquals("Current", Cache.chatChannelCurrentMessage);
        assertEquals("Enabled", Cache.chatChannelToggledOnMessage); assertEquals("Disabled", Cache.chatChannelToggledOffMessage);
        assertEquals("Muted", Cache.chatChannelCantUseWhenToggledOffMessage);
    }

    @Test void partyMessagesMigrateLegacyCommandsWithoutLosingTheirPlaceholders() throws Exception {
        Map<String, Supplier<String>> messages = new LinkedHashMap<>();
        messages.put("players-only", PartyLoader::getPlayersOnly); messages.put("usage", PartyLoader::getUsage);
        messages.put("created", PartyLoader::getCreated); messages.put("invited-target", PartyLoader::getInvitedTarget);
        messages.put("invited-leader", PartyLoader::getInvitedLeader); messages.put("joined", PartyLoader::getJoined);
        messages.put("joined-notify", PartyLoader::getJoinedNotify); messages.put("no-invite", PartyLoader::getNoInvite);
        messages.put("invite-expired", PartyLoader::getInviteExpired); messages.put("already-in-party", PartyLoader::getAlreadyInParty);
        messages.put("not-in-party", PartyLoader::getNotInParty); messages.put("not-leader", PartyLoader::getNotLeader);
        messages.put("disbanded", PartyLoader::getDisbanded); messages.put("member-left", PartyLoader::getMemberLeft);
        messages.put("member-kicked", PartyLoader::getMemberKicked); messages.put("kicked-notify", PartyLoader::getKickedNotify);
        messages.put("target-not-found", PartyLoader::getTargetNotFound); messages.put("target-in-party", PartyLoader::getTargetInParty);
        messages.put("target-has-invite", PartyLoader::getTargetHasInvite); messages.put("invalid-name", PartyLoader::getInvalidName);
        messages.put("cannot-kick-self", PartyLoader::getCannotKickSelf); messages.put("info-header", PartyLoader::getInfoHeader);
        messages.put("info-line", PartyLoader::getInfoLine);
        StringBuilder config = new StringBuilder("invite-expiry-seconds: 45\nmax-name-length: 24\nmessages:\n");
        messages.keySet().forEach(key -> config.append("  ").append(key).append(": '").append(key).append(" /party {player}'\n"));
        new PartyLoader().load(yaml(config.toString()));
        assertEquals(45, PartyLoader.getInviteExpirySeconds()); assertEquals(24, PartyLoader.getMaxNameLength());
        messages.forEach((key, read) -> assertEquals(key + " /rpcharacter party {player}", read.get(), key));
        new PartyLoader().load(yaml("invite-expiry-seconds: 0\nmax-name-length: -1"));
        assertEquals(1, PartyLoader.getInviteExpirySeconds()); assertEquals(1, PartyLoader.getMaxNameLength());
        assertNull(PartyLoader.migratePartyCommand(null)); assertEquals("", PartyLoader.migratePartyCommand(""));
        assertEquals("/rpcharacter party join", PartyLoader.migratePartyCommand("/rpcharacter party join"));
    }

    @Test void invalidYamlCanRecoverOnTheNextReload() throws Exception {
        File file = yaml("bad: [unterminated"); new ChatLoader().load(file); new PartyLoader().load(file);
        assertTrue(ChatLoader.getChannelCommands().isEmpty()); assertEquals(60, PartyLoader.getInviteExpirySeconds());
        new PartyLoader().load(yaml("invite-expiry-seconds: 8")); assertEquals(8, PartyLoader.getInviteExpirySeconds());
    }
}
