package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;

class WillServiceTest {
    /** Characters for the rules, kept in maps. */
    static final class Characters implements WillRules.Lookup {
        final Map<UUID, CharacterInfo> infos = new HashMap<>();
        final Set<UUID> unreadable = new HashSet<>();

        CharacterInfo add(String name, CharacterStatus status) {
            CharacterInfo info = new CharacterInfo(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), name, status,
                    "elf", Set.of(), 0, null);
            infos.put(info.ref().characterId(), info);
            return info;
        }

        @Override
        public Optional<CharacterInfo> info(UUID characterId) {
            return Optional.ofNullable(infos.get(characterId));
        }

        @Override
        public boolean unreadable(UUID characterId) {
            return unreadable.contains(characterId);
        }
    }

    private RuntimeTestState state;
    private MockedStatic<RosterSyncService> roster;
    private Database database;
    private final Characters characters = new Characters();
    private PlayerData data;
    private RPCharacter aria;

    @BeforeEach
    void setUp() {
        state = new RuntimeTestState(WillService.class);
        roster = mockStatic(RosterSyncService.class);
        database = mock(Database.class);
        WillService.database = database;
        WillService.lookup = characters;
        WillService.clock = () -> 1234L;
        data = new PlayerData(UUID.randomUUID());
        aria = new RPCharacter(owner());
        data.addCharacter(aria);
    }

    static org.bukkit.entity.Player owner() {
        org.bukkit.entity.Player owner = mock(org.bukkit.entity.Player.class);
        when(owner.getName()).thenReturn("Owner");
        return owner;
    }

    @AfterEach
    void tearDown() {
        roster.close();
        state.close();
    }

    @Test
    void theRulesAllowOnlyTheOwnerOfALivingTestatorToNameAnotherLivingCharacter() {
        CharacterInfo kira = characters.add("Kira", CharacterStatus.ALIVE);
        CharacterInfo dead = characters.add("Gone", CharacterStatus.DEAD);
        UUID owner = data.getUniqueId();
        UUID self = UUID.fromString(aria.getId());
        assertEquals(WillRules.Verdict.OK, WillRules.check(owner, owner, aria, kira.ref().characterId(), characters));
        assertEquals(WillRules.Verdict.OK, WillRules.check(owner, owner, aria, null, characters));
        assertEquals(WillRules.Verdict.NOT_OWNER, WillRules.check(UUID.randomUUID(), owner, aria, null, characters));
        assertEquals(WillRules.Verdict.HEIR_SELF, WillRules.check(owner, owner, aria, self, characters));
        assertEquals(WillRules.Verdict.HEIR_UNKNOWN, WillRules.check(owner, owner, aria, UUID.randomUUID(), characters));
        assertEquals(WillRules.Verdict.HEIR_NOT_ALIVE, WillRules.check(owner, owner, aria, dead.ref().characterId(), characters));
        characters.unreadable.add(kira.ref().characterId());
        assertEquals(WillRules.Verdict.HEIR_UNREADABLE, WillRules.check(owner, owner, aria, kira.ref().characterId(), characters));
        aria.setStatus(Status.DEAD);
        assertEquals(WillRules.Verdict.TESTATOR_DEAD, WillRules.check(owner, owner, aria, null, characters));
    }

    @Test
    void aChangeIsSavedDurablyBeforeItCountsAndPushedToTheSite() {
        CharacterInfo kira = characters.add("Kira", CharacterStatus.ALIVE);
        when(database.trySaveCharacterDurably(data, aria)).thenReturn(true);
        WillService.Change named = WillService.change(data, aria, data.getUniqueId(), kira.ref().characterId(), "req-1");
        assertTrue(named.applied());
        assertEquals(new CharacterWill(kira.ref().characterId(), 1, 1234L, "req-1"), aria.getWill());
        assertEquals("Kira", WillService.heirName(aria.getWill()));
        roster.verify(() -> RosterSyncService.pushRosterAsync(data.getUniqueId()));
        WillService.Change revoked = WillService.change(data, aria, data.getUniqueId(), null, null);
        assertEquals(new CharacterWill(null, 2, 1234L, null), revoked.will());
        assertNull(WillService.heirName(revoked.will()));
    }

    @Test
    void aRefusedOrUnsavedChangeLeavesTheWillAsItWas() {
        CharacterInfo kira = characters.add("Kira", CharacterStatus.ALIVE);
        WillService.Change refused = WillService.change(data, aria, UUID.randomUUID(), kira.ref().characterId(), null);
        assertEquals(WillRules.Verdict.NOT_OWNER, refused.verdict());
        assertFalse(refused.applied());
        when(database.trySaveCharacterDurably(data, aria)).thenReturn(false);
        WillService.Change unsaved = WillService.change(data, aria, data.getUniqueId(), kira.ref().characterId(), null);
        assertFalse(unsaved.saved());
        assertFalse(unsaved.applied());
        assertSame(CharacterWill.NONE, aria.getWill());
        roster.verifyNoInteractions();
        assertNull(WillService.heirName(new CharacterWill(UUID.randomUUID(), 1, null, null)));
        assertTrue(CharacterWill.NONE.change(UUID.randomUUID(), 5, null).named());
        assertThrows(IllegalArgumentException.class, () -> new CharacterWill(null, -1, null, null));
    }

    @Test
    void theServerLookupAsksTheIdentityServiceAndTreatsItsAbsenceAsUnreadable() {
        state.close();
        state = new RuntimeTestState(WillService.class);
        WillRules.Lookup server = WillService.lookup();
        try (var statics = mockStatic(RPCharacters.class)) {
            UUID id = UUID.randomUUID();
            assertTrue(server.info(id).isEmpty());
            assertTrue(server.unreadable(id));
            CharacterIdentityService identity = mock(CharacterIdentityService.class);
            statics.when(RPCharacters::getCharacterIdentity).thenReturn(identity);
            CharacterInfo kira = characters.add("Kira", CharacterStatus.ALIVE);
            when(identity.info(id)).thenReturn(Optional.of(kira));
            assertEquals(Optional.of(kira), server.info(id));
            assertFalse(server.unreadable(id));
        }
    }
}
