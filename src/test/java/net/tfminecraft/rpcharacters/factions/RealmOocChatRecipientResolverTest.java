package net.tfminecraft.rpcharacters.factions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Faction;

class RealmOocChatRecipientResolverTest {
    ServerMock server; PlayerMock sender, member, outsider; RuntimeTestState state;
    MockedStatic<FactionManager> factions; ChatChannel channel;
    RealmOocChatRecipientResolver resolver = new RealmOocChatRecipientResolver();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState();
        sender = server.addPlayer("Sender"); member = server.addPlayer("Member"); outsider = server.addPlayer("Outsider");
        var config = new YamlConfiguration(); config.set("require-character", false); config.set("read-perm", "realm.read");
        channel = new ChatChannel("realm", config); sender.addAttachment(MockBukkit.createMockPlugin(), "realm.read", true); member.setOp(true);
        factions = mockStatic(FactionManager.class);
    }
    @AfterEach void cleanup() { factions.close(); state.close(); MockBukkit.unmock(); }
    Faction faction(String id, List<String> names) { Faction faction = mock(Faction.class); when(faction.getId()).thenReturn(id); when(faction.getCompleteMemberList()).thenReturn(names); return faction; }

    @Test void unknownSenderChannelOrFactionHasNoRealmRecipients() {
        assertTrue(resolver.resolve(null, channel).isEmpty()); assertTrue(resolver.resolve(sender, null).isEmpty()); assertTrue(resolver.resolve(sender, channel).isEmpty());
    }
    @Test void topOverlordMembershipAndChannelPermissionsFilterOnlineRecipients() {
        Faction child = faction("child", List.of("Sender")); Faction top = faction("top", Arrays.asList("SENDER", "MEMBER", "Offline", "Outsider", null));
        when(child.getOverlord()).thenReturn(top); factions.when(() -> FactionManager.getByMember("Sender")).thenReturn(child);
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals(Set.of(sender, member), resolver.resolve(sender, channel));
        member.disconnect(); assertEquals(Set.of(sender), resolver.resolve(sender, channel));
    }
    @Test void selfOverlordAndCyclesTerminateAndUseTheReachedRealm() {
        Faction first = faction("first", List.of("Sender")); Faction second = faction("second", List.of("Member"));
        factions.when(() -> FactionManager.getByMember("Sender")).thenReturn(first);
        when(first.getOverlord()).thenReturn(first); assertEquals(Set.of(sender), resolver.resolve(sender, channel));
        when(first.getOverlord()).thenReturn(second); when(second.getOverlord()).thenReturn(first);
        assertEquals(Set.of(sender), resolver.resolve(sender, channel));
    }
}
