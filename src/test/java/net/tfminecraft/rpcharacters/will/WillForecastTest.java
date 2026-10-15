package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.will.WillForecast.Guild;
import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.identity.Members;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Faction;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

class WillForecastTest {
    private RuntimeTestState state;
    private MockedStatic<Characters> identity;
    private final UUID self = UUID.randomUUID();
    private final CharacterInfo bob = info("Bob", CharacterStatus.ALIVE);
    private final CharacterInfo ghost = info("Ghost", CharacterStatus.DEAD);

    private static CharacterInfo info(String name, CharacterStatus status) {
        return new CharacterInfo(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), name, status, null, Set.of(), 0, null);
    }

    @BeforeEach
    void setUp() {
        state = new RuntimeTestState(WillForecast.class);
        identity = mockStatic(Characters.class);
        identity.when(() -> Characters.info(any(UUID.class))).thenReturn(Optional.empty());
        identity.when(() -> Characters.info(bob.ref().characterId())).thenReturn(Optional.of(bob));
        identity.when(() -> Characters.info(ghost.ref().characterId())).thenReturn(Optional.of(ghost));
    }

    @AfterEach
    void tearDown() {
        identity.close();
        state.close();
    }

    @Test
    void failuresAndMissingAnswersReadAsUnknown() {
        WillForecast.guilds = id -> null;
        assertSame(WillForecast.UNKNOWN, WillForecast.guild(self));
        WillForecast.guilds = id -> { throw new NoClassDefFoundError("SimpleFactions"); };
        assertSame(WillForecast.UNKNOWN, WillForecast.guild(self));
        WillForecast.guilds = id -> WillForecast.NONE;
        assertSame(WillForecast.NONE, WillForecast.guild(self));
    }

    @Test
    void livingSkipsTheCharacterItselfTheDeadAndTheUnknown() {
        assertEquals(Optional.empty(), WillForecast.living(null, self));
        assertEquals(Optional.empty(), WillForecast.living(self, self));
        assertEquals(Optional.empty(), WillForecast.living(ghost.ref().characterId(), self));
        assertEquals(Optional.of(bob), WillForecast.living(bob.ref().characterId(), self));
    }

    @Test
    void simpleFactionsNamesTheGuildItsLeaderAndTheRealmLeader() {
        PluginManager plugins = mock(PluginManager.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<SimpleFactionsApi> api = mockStatic(SimpleFactionsApi.class);
                MockedStatic<FactionManager> factions = mockStatic(FactionManager.class);
                MockedStatic<Members> members = mockStatic(Members.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertSame(WillForecast.UNKNOWN, WillForecast.guild(self));
            when(plugins.isPluginEnabled("SimpleFactions")).thenReturn(true);
            assertSame(WillForecast.UNKNOWN, WillForecast.guild(self));
            api.when(SimpleFactionsApi::ready).thenReturn(true);
            assertSame(WillForecast.NONE, WillForecast.guild(self));
            var guild = mock(net.tfminecraft.simplefactions.guild.Guild.class);
            factions.when(() -> FactionManager.getGuildByMember(self)).thenReturn(guild);
            when(guild.getName()).thenReturn("§6Ironhold");
            when(guild.getLeader()).thenReturn("bob");
            members.when(() -> Members.characterId("bob")).thenReturn(bob.ref().characterId());
            assertEquals(new Guild.Member("Ironhold", Optional.of(bob), false, Optional.empty()), WillForecast.guild(self));
            Faction realm = mock(Faction.class);
            when(guild.getFaction()).thenReturn(realm);
            when(guild.getName()).thenReturn(null);
            when(guild.getLeader()).thenReturn("me");
            when(realm.getLeader()).thenReturn("bob");
            members.when(() -> Members.characterId("me")).thenReturn(self);
            assertEquals(new Guild.Member("your guild", Optional.empty(), true, Optional.of(bob)), WillForecast.guild(self));
        }
    }
}
