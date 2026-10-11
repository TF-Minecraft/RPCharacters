package net.tfminecraft.rpcharacters.persona;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class OfflinePermissionsTest {
    ServerMock server; RuntimeTestState state; Plugin plugin; final UUID owner = UUID.randomUUID();

    @BeforeEach void setup() { server = MockBukkit.mock(); state = new RuntimeTestState(); plugin = MockBukkit.createMockPlugin(); }
    @AfterEach void cleanup() { state.close(); MockBukkit.unmock(); }

    @Test void readsTheOfflinePlayersPermissionsWithThisServersContexts() {
        assertNull(OfflinePermissions.load(owner), "No LuckPerms, no answer");
        LuckPerms api = mock(LuckPerms.class, RETURNS_DEEP_STUBS); User user = mock(User.class, RETURNS_DEEP_STUBS);
        QueryOptions serverContexts = mock(QueryOptions.class); CachedPermissionData permissions = mock(CachedPermissionData.class);
        when(api.getContextManager().getStaticQueryOptions()).thenReturn(serverContexts);
        when(user.getCachedData().getPermissionData(serverContexts)).thenReturn(permissions);
        when(permissions.checkPermission(anyString())).thenReturn(Tristate.UNDEFINED);
        when(permissions.checkPermission("group.gilded")).thenReturn(Tristate.TRUE);
        when(api.getUserManager().loadUser(owner)).thenReturn(CompletableFuture.completedFuture(user));
        server.getServicesManager().register(LuckPerms.class, api, plugin, ServicePriority.Normal);
        var loaded = OfflinePermissions.load(owner);
        assertTrue(loaded.test("group.gilded")); assertFalse(loaded.test("group.legacy"));

        Cache.maxCharacterSlots = 10;
        Cache.permissionGroupDefaults = Map.of(PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, 3);
        Cache.permissionGroups = new java.util.ArrayList<>(java.util.List.of(
            new PermissionGroupDefinition("gilded", "group.gilded", "Gilded", 2, true, Map.of(PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, 4)),
            new PermissionGroupDefinition("legacy", "group.legacy", "Legacy", 4, true, Map.of(PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, 5))));
        assertEquals(4, CharacterSlotService.getMaxAliveCharactersOffline(loaded));
        assertEquals(3, CharacterSlotService.getMaxAliveCharactersOffline(null));
        Cache.maxCharacterSlots = 2;
        assertEquals(2, CharacterSlotService.getMaxAliveCharactersOffline(loaded), "The hard cap still applies");

        when(api.getUserManager().loadUser(owner)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage down")));
        assertNull(OfflinePermissions.load(owner));
    }
}
