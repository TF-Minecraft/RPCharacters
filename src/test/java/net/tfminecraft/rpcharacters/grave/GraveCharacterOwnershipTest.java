package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.body.CharacterBodies;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class GraveCharacterOwnershipTest extends GraveRuntimeFixture {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private CharacterSession session(UUID character) {
        return new CharacterSession(new CharacterRef(player.getUniqueId(), character), 1);
    }

    @Test void boundGraveRequiresTheSameAccountAndReadyCharacter() {
        Grave grave = grave();
        grave.setCharacterOwner(alice);
        CharacterBodies bodies = mock(CharacterBodies.class);
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            assertTrue(grave.isOwner(player.getUniqueId()));
            assertTrue(GraveLootRules.canRecover(player, grave));
            assertFalse(grave.isOwner(UUID.randomUUID()));
            assertFalse(grave.isOwner(null));

            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            assertFalse(grave.isOwner(player.getUniqueId()));
            assertFalse(GraveLootRules.canRecover(player, grave));
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
            assertFalse(grave.isOwner(player.getUniqueId()));

            CharacterSession wrongAccount = new CharacterSession(new CharacterRef(UUID.randomUUID(), alice), 1);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(wrongAccount));
            assertFalse(grave.isOwner(player.getUniqueId()));
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            when(bodies.isSelecting(player)).thenReturn(true);
            assertFalse(grave.isOwner(player.getUniqueId()));
            when(bodies.isSelecting(player)).thenReturn(false);
            when(bodies.isApplying(player)).thenReturn(true);
            assertFalse(grave.isOwner(player.getUniqueId()));
        }
    }

    @Test void legacyGraveBelongsOnlyToTheReviewedMigrationHeirWhileBodiesAreEnabled() {
        Grave grave = grave();
        CharacterBodies bodies = mock(CharacterBodies.class);
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            assertFalse(grave.isOwner(player.getUniqueId()), "An account without a reviewed heir has no implicit grave owner");
            when(bodies.migrationHeir(player.getUniqueId())).thenReturn(alice);
            assertTrue(grave.isOwner(player.getUniqueId()));
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            assertFalse(grave.isOwner(player.getUniqueId()));
            assertNull(grave.getCharacterOwner(), "Legacy ownership is resolved without rewriting the saved grave");

            runtime.when(RPCharacters::getCharacterBodies).thenReturn(null);
            assertTrue(grave.isOwner(player.getUniqueId()), "Feature-off legacy ownership stays account based");
        }
    }

    @Test void explicitBindingSurvivesFeatureDisableButNeverGrantsOfflineOrUnknownOwnership() {
        Grave grave = grave();
        grave.setCharacterOwner(alice);
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            assertTrue(grave.isOwner(player.getUniqueId()));
            grave.setCharacterOwner(Grave.UNKNOWN_CHARACTER);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(Grave.UNKNOWN_CHARACTER)));
            assertFalse(grave.isOwner(player.getUniqueId()));
            grave.setCharacterOwner(alice);
            player.disconnect();
            assertFalse(grave.isOwner(player.getUniqueId()));
        }
    }

    @Test void publicLootAndAdministratorRecoveryRemainAvailableForOtherCharacters() {
        Grave grave = grave();
        grave.setCharacterOwner(alice);
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            assertFalse(GraveLootRules.canRecover(player, grave));
            grave.setLocked(false);
            assertTrue(GraveLootRules.canRecover(player, grave));
            grave.setLocked(true);
            player.addAttachment(plugin, "rpchar.grave.admin", true);
            assertTrue(GraveLootRules.canRecover(player, grave));
            assertFalse(grave.isOwner(player.getUniqueId()));
        }
    }

    @Test void newGraveBindsTheDyingBodyEvenAfterItsReadySessionHasEnded() {
        CharacterBodies bodies = mock(CharacterBodies.class);
        when(bodies.liveCharacter(player)).thenReturn(alice);
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
            Grave grave = manager.spawn(player, world.getBlockAt(1, 64, 1), null, null, 7, true,
                    new ItemStack[]{new ItemStack(Material.DIAMOND, 3)}, null, null, List.of());
            assertNotNull(grave);
            assertEquals(alice, grave.getCharacterOwner());
            assertFalse(grave.isOwner(player.getUniqueId()));
            manager.loadAll();
            Grave loaded = manager.getById(grave.getId());
            assertEquals(alice, loaded.getCharacterOwner());
            assertEquals(3, loaded.getItem(0).getAmount());
            assertEquals(7, loaded.getExperience());
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            assertNull(manager.findNewestByOwner(player.getUniqueId()));
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            assertSame(loaded, manager.findNewestByOwner(player.getUniqueId()));
        }
    }

    @Test void missingDyingBodyCannotCreateAnUnboundLegacyClaim() {
        CharacterBodies bodies = mock(CharacterBodies.class);
        when(bodies.migrationHeir(player.getUniqueId())).thenReturn(alice);
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            Grave grave = manager.spawn(player, world.getBlockAt(1, 64, 1), null, null, 0, false,
                    null, null, null, List.of());
            assertEquals(Grave.UNKNOWN_CHARACTER, grave.getCharacterOwner());
            manager.loadAll();
            Grave loaded = manager.getById(grave.getId());
            assertEquals(Grave.UNKNOWN_CHARACTER, loaded.getCharacterOwner());
            assertFalse(loaded.isOwner(player.getUniqueId()));
        }
    }

    @Test void insuranceCannotBypassCharacterOwnershipAndDeniedTicketRemainsIntact() {
        Grave grave = grave(); grave.setCharacterOwner(alice); grave.setExperience(5); manager.register(grave);
        ItemStack ticket = GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER), grave);
        player.getInventory().setItemInMainHand(ticket);
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            new GraveInsuranceListener().onPlayerInteract(insuranceEvent());
            assertSame(grave, manager.getById(grave.getId()));
            assertEquals(5, grave.getExperience());
            assertEquals(0, player.getTotalExperience());
            assertEquals(ticket, player.getInventory().getItemInMainHand());
            assertEquals("§clocked", player.nextMessage());

            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
            new GraveInsuranceListener().onPlayerInteract(insuranceEvent());
            assertSame(grave, manager.getById(grave.getId()));
            assertEquals("§clocked", player.nextMessage());

            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(alice)));
            new GraveInsuranceListener().onPlayerInteract(insuranceEvent());
            assertNull(manager.getById(grave.getId()));
            assertEquals(5, player.getTotalExperience());
            assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        }
    }

    @Test void insuranceRetainsPublicAndStaffRecoveryForABoundGrave() {
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session(bob)));
            Grave unlocked = grave(); unlocked.setCharacterOwner(alice); unlocked.setLocked(false);
            unlocked.setExperience(5); manager.register(unlocked);
            player.getInventory().setItemInMainHand(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER), unlocked));
            new GraveInsuranceListener().onPlayerInteract(insuranceEvent());
            assertNull(manager.getById(unlocked.getId()));

            Grave locked = grave(); locked.setCharacterOwner(alice); locked.setExperience(7); manager.register(locked);
            player.getInventory().setItemInMainHand(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER), locked));
            player.addAttachment(plugin, "rpchar.grave.admin", true);
            new GraveInsuranceListener().onPlayerInteract(insuranceEvent());
            assertNull(manager.getById(locked.getId()));
            assertEquals(12, player.getTotalExperience());
        }
    }

    @Test void hologramUsesTheSavedCharacterRegardlessOfTheAccountsCurrentCharacter() {
        Grave grave = grave(); grave.setCharacterOwner(alice);
        identities.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn("Bob");
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.info(alice)).thenReturn(Optional.of(info(player.getUniqueId(), "Alice")));
            assertEquals("Alice", GraveHologramTexts.baseLines(grave).getFirst());
            characters.when(() -> Characters.info(alice)).thenReturn(Optional.empty());
            assertEquals("Alex", GraveHologramTexts.baseLines(grave).getFirst());
            characters.when(() -> Characters.info(alice)).thenReturn(Optional.of(info(UUID.randomUUID(), "Wrong account")));
            assertEquals("Alex", GraveHologramTexts.baseLines(grave).getFirst());
            characters.when(() -> Characters.info(alice)).thenReturn(Optional.of(info(player.getUniqueId(), " ")));
            assertEquals("Alex", GraveHologramTexts.baseLines(grave).getFirst());
        }
    }

    @Test void legacyHologramUsesTheMigrationHeirOnlyWhileBodiesAreEnabled() {
        Grave grave = grave(); CharacterBodies bodies = mock(CharacterBodies.class);
        identities.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn("Bob");
        try (var runtime = mockStatic(RPCharacters.class); var characters = mockStatic(Characters.class)) {
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            assertEquals("Alex", GraveHologramTexts.baseLines(grave).getFirst());
            when(bodies.migrationHeir(player.getUniqueId())).thenReturn(alice);
            characters.when(() -> Characters.info(alice)).thenReturn(Optional.of(info(player.getUniqueId(), "Alice")));
            assertEquals("Alice", GraveHologramTexts.baseLines(grave).getFirst());
            assertEquals("Unknown", GraveHologramTexts.baseLines(new Grave(null, null, null)).getFirst());
            runtime.when(RPCharacters::getCharacterBodies).thenReturn(null);
            assertEquals("Bob", GraveHologramTexts.baseLines(grave).getFirst());
        }
    }

    private CharacterInfo info(UUID owner, String name) {
        return new CharacterInfo(new CharacterRef(owner, alice), name, CharacterStatus.DEAD, null,
                java.util.Set.of(), 0, null);
    }

    private PlayerInteractEvent insuranceEvent() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        return event;
    }
}
