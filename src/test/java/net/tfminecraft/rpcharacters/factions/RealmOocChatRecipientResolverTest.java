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
    MockedStatic<net.tfminecraft.simplefactions.identity.Members> members; MockedStatic<net.tfminecraft.simplefactions.api.SimpleFactionsApi> api; MockedStatic<FactionManager> factions; ChatChannel channel;
    RealmOocChatRecipientResolver resolver = new RealmOocChatRecipientResolver();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState();
        sender = server.addPlayer("Sender"); member = server.addPlayer("Member"); outsider = server.addPlayer("Outsider");
        var config = new YamlConfiguration(); config.set("require-character", false); config.set("read-perm", "realm.read");
        channel = new ChatChannel("realm", config); sender.addAttachment(MockBukkit.createMockPlugin(), "realm.read", true); member.setOp(true);
        factions = mockStatic(FactionManager.class);
        members=mockStatic(net.tfminecraft.simplefactions.identity.Members.class);
        members.when(() -> net.tfminecraft.simplefactions.identity.Members.key(any(org.bukkit.entity.Player.class)))
            .thenAnswer(i -> i.getArgument(0,org.bukkit.entity.Player.class).getName());
        api=mockStatic(net.tfminecraft.simplefactions.api.SimpleFactionsApi.class);
        api.when(net.tfminecraft.simplefactions.api.SimpleFactionsApi::ready).thenReturn(true);
    }
    @AfterEach void cleanup() { members.close(); api.close(); factions.close(); state.close(); MockBukkit.unmock(); }
    Faction faction(String id, List<String> names) { Faction faction = mock(Faction.class); when(faction.getId()).thenReturn(id); when(faction.getCompleteMemberList()).thenReturn(names); return faction; }

    @Test void unknownSenderChannelOrFactionHasNoRealmRecipients() {
        assertTrue(resolver.resolve(null, channel).isEmpty()); assertTrue(resolver.resolve(sender, null).isEmpty()); assertTrue(resolver.resolve(sender, channel).isEmpty());
    }
    @Test void topOverlordMembershipAndChannelPermissionsFilterOnlineRecipients() {
        Faction child = faction("child", List.of("Sender")); Faction top = faction("top", Arrays.asList("Sender", "Member", "Offline", "Outsider", null));
        when(child.getOverlord()).thenReturn(top); factions.when(() -> FactionManager.getByMember(sender)).thenReturn(child);
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals(Set.of(sender, member), resolver.resolve(sender, channel));
        member.disconnect(); assertEquals(Set.of(sender), resolver.resolve(sender, channel));
    }
    @Test void selfOverlordAndCyclesTerminateAndUseTheReachedRealm() {
        Faction first = faction("first", List.of("Sender")); Faction second = faction("second", List.of("Member"));
        factions.when(() -> FactionManager.getByMember(sender)).thenReturn(first);
        when(first.getOverlord()).thenReturn(first); assertEquals(Set.of(sender), resolver.resolve(sender, channel));
        when(first.getOverlord()).thenReturn(second); when(second.getOverlord()).thenReturn(first);
        assertEquals(Set.of(sender), resolver.resolve(sender, channel));
    }

    @Test void changedCharacterAndUnreadyFactionProviderHaveNoRealmAccess() {
        Faction realm=faction("realm",List.of("Sender","Member"));
        factions.when(() -> FactionManager.getByMember(sender)).thenReturn(realm);
        members.when(() -> net.tfminecraft.simplefactions.identity.Members.key(member)).thenReturn("different-character");
        assertEquals(Set.of(sender),resolver.resolve(sender,channel));
        api.when(net.tfminecraft.simplefactions.api.SimpleFactionsApi::ready).thenReturn(false);
        assertTrue(resolver.resolve(sender,channel).isEmpty());
    }
}
