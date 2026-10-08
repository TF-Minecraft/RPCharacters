package net.tfminecraft.rpcharacters.factions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatRecipientResolverRegistry;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Faction;

class RealmChatDependencyListenerTest {
    ServerMock server; PlayerMock sender, member; RuntimeTestState state;
    MockedStatic<FactionManager> factions; ChatChannel channel;
    RealmChatDependencyListener listener = new RealmChatDependencyListener();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(ChatRecipientResolverRegistry.class);
        ChatRecipientResolverRegistry.unregister(RealmChatDependencyListener.RESOLVER_ID);
        sender = server.addPlayer("Sender"); member = server.addPlayer("Member"); sender.setOp(true); member.setOp(true);
        var config = new YamlConfiguration(); config.set("require-character", false); config.set("read-perm", "realm.read");
        channel = new ChatChannel("rooc", config);
        Faction realm = mock(Faction.class); when(realm.getId()).thenReturn("realm"); when(realm.getCompleteMemberList()).thenReturn(List.of("Sender", "Member"));
        factions = mockStatic(FactionManager.class); factions.when(() -> FactionManager.getByMember("Sender")).thenReturn(realm);
        server.getPluginManager().registerEvents(listener, MockBukkit.createMockPlugin());
    }
    @AfterEach void cleanup() { factions.close(); state.close(); MockBukkit.unmock(); }
    Set<Player> realmRecipients() { return ChatRecipientResolverRegistry.resolve(RealmChatDependencyListener.RESOLVER_ID, sender, channel); }

    @Test void startupCheckWithoutSimpleFactionsLeavesRealmChatUnresolved() {
        listener.registerIfPresent(); assertTrue(realmRecipients().isEmpty());
    }
    @Test void simpleFactionsEnablingAfterRpCharactersRegistersRealmChat() {
        MockBukkit.createMockPlugin("SimpleFactions"); assertEquals(Set.of(sender, member), realmRecipients());
    }
    @Test void startupCheckRegistersRealmChatWhenSimpleFactionsIsAlreadyEnabled() {
        var simpleFactions = MockBukkit.createMockPlugin("SimpleFactions"); ChatRecipientResolverRegistry.unregister(RealmChatDependencyListener.RESOLVER_ID);
        assertTrue(simpleFactions.isEnabled()); listener.registerIfPresent(); assertEquals(Set.of(sender, member), realmRecipients());
    }
    @Test void simpleFactionsDisablingUnregistersRealmChat() {
        var simpleFactions = MockBukkit.createMockPlugin("SimpleFactions"); server.getPluginManager().disablePlugin(simpleFactions);
        assertTrue(realmRecipients().isEmpty());
    }
    @Test void otherPluginsDoNotRegisterRealmChat() {
        MockBukkit.createMockPlugin("Towny"); assertTrue(realmRecipients().isEmpty());
    }
}
