package net.tfminecraft.rpcharacters.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.playerlist.PlayerListDialogs;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ProfileManagerTest {
    ServerMock server;
    RuntimeTestState state;
    PlayerMock viewer, target;
    Plugin plugin;
    RPCharacter character, facade;
    PlayerData targetData;
    ProfileManager manager;
    final List<CharacterProfileViewEvent> events = new ArrayList<>();
    MockedStatic<PlayerManager> players;
    MockedStatic<MaskService> masks;
    MockedStatic<PlayerListDialogs> dialogs;
    MockedStatic<DisplayIdentityService> identities;

    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState();
        plugin = MockBukkit.createMockPlugin();
        viewer = server.addPlayer("Viewer"); target = server.addPlayer("Subject");
        manager = new ProfileManager();
        Cache.profilePermission = "test.profile"; grant(true);
        Cache.profileRequireSneak = true; Cache.profileRequireEmptyHand = true;
        Cache.profileViewCooldownSeconds = 0;
        Cache.personaGenderDefault = "Unknown"; Cache.calendarAgeUnsetLabel = "Unknown age";
        Cache.profileFormatLines = List.of("&a{display_tab}", "{gender} {description}");
        Cache.profileSheetFormatLines = List.of("Sheet: {display_tab}");
        character = new RPCharacter(target); character.setName("Aria");
        character.setGender("Female"); character.setPersonaDescription("A traveller");
        facade = new RPCharacter(target); facade.setName("Facade");
        targetData = mock(PlayerData.class); when(targetData.getActiveCharacter()).thenReturn(character);
        players = mockStatic(PlayerManager.class); players.when(() -> PlayerManager.get(target)).thenReturn(targetData);
        masks = mockStatic(MaskService.class);
        dialogs = mockStatic(PlayerListDialogs.class);
        identities = mockStatic(DisplayIdentityService.class, CALLS_REAL_METHODS);
        identities.when(() -> DisplayIdentityService.resolveDisplayTab(character)).thenReturn("Aria");
        identities.when(() -> DisplayIdentityService.resolveDisplayTab(facade)).thenReturn("Facade");
        server.getPluginManager().registerEvents(manager, plugin);
        server.getPluginManager().registerEvent(CharacterProfileViewEvent.class, new Listener() {},
            EventPriority.MONITOR, (listener, event) -> events.add((CharacterProfileViewEvent) event), plugin, false);
    }

    @AfterEach void cleanup() {
        ProfileViewCooldownManager.get().clear(viewer); ProfileViewCooldownManager.get().clear(target);
        identities.close(); dialogs.close(); masks.close(); players.close(); state.close(); MockBukkit.unmock();
    }

    void grant(boolean enabled) { viewer.addAttachment(plugin, Cache.profilePermission, enabled); }
    CharacterProfileViewEvent event(boolean command) { return new CharacterProfileViewEvent(viewer, target, character, false, command); }
    String message() { return Objects.requireNonNull(viewer.nextMessage()); }
    PlayerInteractEntityEvent interaction(Entity clicked, EquipmentSlot hand) { return new PlayerInteractEntityEvent(viewer, clicked, hand); }

    @Test void chatProfileDispatchesRealEventAndDisplaysItsCharacter() {
        ProfileManager.showProfile(viewer, target, true);
        assertEquals(1, events.size());
        var event = events.getFirst(); assertSame(viewer, event.getViewer()); assertSame(target, event.getTarget());
        assertSame(character, event.getTargetCharacter()); assertTrue(event.isFromCommand()); assertFalse(event.isMasked());
        assertEquals(CharacterProfileViewEvent.Presentation.CHAT, event.getPresentation()); assertFalse(event.isCancelled());
        assertSame(CharacterProfileViewEvent.getHandlerList(), event.getHandlers());
        assertEquals("§aAria", message()); assertEquals("Female A traveller", message()); assertNull(viewer.nextMessage());
        dialogs.verifyNoInteractions();
    }

    @Test void sheetUsesSafeFacadeAndAllowsMaskedSubjectsWithoutDisclosingMask() {
        identities.when(() -> DisplayIdentityService.resolveSafeCharacter(target)).thenReturn(facade);
        masks.when(() -> MaskService.isMasked(target)).thenReturn(true);
        ProfileManager.showProfileSheet(viewer, target);
        var event = events.getFirst(); assertSame(facade, event.getTargetCharacter()); assertTrue(event.isMasked());
        assertTrue(event.isFromCommand()); assertEquals(CharacterProfileViewEvent.Presentation.SHEET, event.getPresentation());
        assertFalse(event.isCancelled());
        dialogs.verify(() -> PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Sheet: Facade")));
        assertNull(viewer.nextMessage());
    }

    @Test void nullEndpointsAreIgnoredAndUnloadedCharacterIsDenied() {
        ProfileManager.showProfile(null, target, true); ProfileManager.showProfile(viewer, null, true);
        ProfileManager.showProfileSheet(null, target); ProfileManager.showProfileSheet(viewer, null);
        assertTrue(events.isEmpty());
        players.when(() -> PlayerManager.get(target)).thenReturn(null);
        ProfileManager.showProfile(viewer, target, true);
        assertTrue(events.getFirst().isCancelled()); assertNull(events.getFirst().getTargetCharacter());
        assertTrue(message().contains("no active character")); dialogs.verifyNoInteractions();
    }

    @Test void permissionAndMaskDenialsDoNotDisplayOrStartCooldown() {
        Cache.profileViewCooldownSeconds = 30; grant(false);
        ProfileManager.showProfile(viewer, target, true);
        assertTrue(events.getLast().isCancelled()); assertTrue(message().contains("permission"));
        grant(true); masks.when(() -> MaskService.isMasked(target)).thenReturn(true);
        ProfileManager.showProfile(viewer, target, true);
        assertTrue(events.getLast().isCancelled()); assertTrue(message().contains("concealed"));
        assertFalse(ProfileViewCooldownManager.get().isOnCooldown(viewer, 30)); assertNull(viewer.nextMessage());
    }

    @Test void interactionChecksHandTargetSneakingAndHeldItems() {
        var offhand = interaction(target, EquipmentSlot.OFF_HAND); manager.onPlayerInteract(offhand); assertFalse(offhand.isCancelled());
        var entity = interaction(mock(Entity.class), EquipmentSlot.HAND); manager.onPlayerInteract(entity); assertFalse(entity.isCancelled());
        var upright = interaction(target, EquipmentSlot.HAND); manager.onPlayerInteract(upright); assertFalse(upright.isCancelled());
        viewer.setSneaking(true); viewer.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        var held = interaction(target, EquipmentSlot.HAND); manager.onPlayerInteract(held); assertFalse(held.isCancelled());
        assertTrue(events.isEmpty());
        viewer.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        var allowed = interaction(target, EquipmentSlot.HAND); manager.onPlayerInteract(allowed);
        assertTrue(allowed.isCancelled()); assertEquals(1, events.size()); assertFalse(events.getFirst().isFromCommand());
        assertEquals("§aAria", message()); message();
        Cache.profileRequireSneak = Cache.profileRequireEmptyHand = false;
        viewer.setSneaking(false); viewer.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        allowed = interaction(target, EquipmentSlot.HAND); manager.onPlayerInteract(allowed);
        assertTrue(allowed.isCancelled()); assertEquals(2, events.size());
    }

    @Test void externallyDispatchedNonCommandEventsStillEnforceInteractionRequirements() {
        var noViewer = new CharacterProfileViewEvent(null, target, character, false, true);
        manager.onProfileViewDeny(noViewer); assertTrue(noViewer.isCancelled());
        var upright = event(false); manager.onProfileViewDeny(upright); assertTrue(upright.isCancelled());
        viewer.setSneaking(true); viewer.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        var held = event(false); manager.onProfileViewDeny(held); assertTrue(held.isCancelled());
        viewer.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        var allowed = event(false); manager.onProfileViewDeny(allowed); assertFalse(allowed.isCancelled());
        Cache.profileRequireEmptyHand = false; viewer.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        allowed = event(false); manager.onProfileViewDeny(allowed); assertFalse(allowed.isCancelled());
        assertNull(viewer.nextMessage());
    }

    @Test void successfulViewsConsumeCooldownAndSubsequentViewsAreDenied() {
        Cache.profileViewCooldownSeconds = 30;
        ProfileManager.showProfile(viewer, target, true); message(); message();
        assertTrue(ProfileViewCooldownManager.get().isOnCooldown(viewer, 30));
        ProfileManager.showProfile(viewer, target, true);
        assertTrue(events.getLast().isCancelled()); assertTrue(message().contains("Wait")); assertNull(viewer.nextMessage());
    }

    @Test void displayIgnoresMissingEndpointsAndCancelledEventsAreNotRendered() {
        manager.onProfileViewDisplay(new CharacterProfileViewEvent(null, target, character, false, true));
        manager.onProfileViewDisplay(new CharacterProfileViewEvent(viewer, null, character, false, true));
        var cancelled = event(true); cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        assertTrue(cancelled.isCancelled()); assertNull(viewer.nextMessage()); dialogs.verifyNoInteractions();
        assertFalse(ProfileViewCooldownManager.get().isOnCooldown(viewer, 30));
        cancelled.setCancelled(false); assertFalse(cancelled.isCancelled());
    }

    @Test void formattersExpandPersonaFieldsAndPreserveBlankTemplateRows() {
        Cache.profileFormatLines = Arrays.asList(null, "&a{display_tab}", "{gender}|{age}|{birthday}|{race}|{description}");
        assertEquals(List.of("", "§aAria", "Female|Unknown age|Unknown age||A traveller"), ProfileFormatter.format(character));
        Cache.profileSheetFormatLines = List.of("#abcdef{display_tab}");
        assertEquals(List.of("§x§a§b§c§d§e§fAria"), ProfileFormatter.formatSheet(character));
        Cache.profileFormatLines = List.of("{gender}|{age}|{birthday}|{race}|{description}");
        assertEquals(List.of("Unknown|Unknown age|Unknown age||"), ProfileFormatter.format(null));
        assertTrue(ProfileFormatter.DEFAULT_SHEET_FORMAT.contains("&7{description}"));
    }

    @Test void cooldownSupportsDisabledClearQuitAndNaturalExpiry() throws Exception {
        var cooldown = ProfileViewCooldownManager.get();
        assertSame(cooldown, ProfileViewCooldownManager.get());
        assertFalse(cooldown.isOnCooldown(null, 1)); assertFalse(cooldown.isOnCooldown(viewer, 0));
        assertFalse(cooldown.isOnCooldown(viewer, 1)); assertEquals(0, cooldown.getRemainingSeconds(viewer));
        cooldown.applyCooldown(null, 10); cooldown.applyCooldown(viewer, 0); cooldown.applyCooldown(viewer, -1);
        assertFalse(cooldown.isOnCooldown(viewer, 10)); cooldown.clear(null);
        cooldown.applyCooldown(viewer, 30); assertTrue(cooldown.isOnCooldown(viewer, 30));
        assertTrue(cooldown.getRemainingSeconds(viewer) > 0 && cooldown.getRemainingSeconds(viewer) <= 30);
        cooldown.onQuit(new PlayerQuitEvent(viewer, "Bye")); assertEquals(0, cooldown.getRemainingSeconds(viewer));
        cooldown.applyCooldown(viewer, 1);
        Thread.sleep(1100);
        assertFalse(cooldown.isOnCooldown(viewer, 1)); assertEquals(0, cooldown.getRemainingSeconds(viewer));
        cooldown.clear(viewer); assertEquals(0, cooldown.getRemainingSeconds(viewer));
    }
}
