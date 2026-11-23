package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class MmoInventorySlotsTest {
    public interface InventoryPlugin { Object getInventoryManager(); }
    public interface InventoryManager { Object getVanilla(); }
    public interface VanillaInventory { void replacePlaceholderItems(Player player); }
    public interface VanillaSlots { Object getSlot(int index); }

    @Test void accessorySlotsComeFromTheVanillaInventoryAndUnknownLayoutsRefuseEverySlot() {
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class, withSettings().extraInterfaces(InventoryPlugin.class));
        InventoryManager manager = mock(InventoryManager.class);
        VanillaSlots vanilla = mock(VanillaSlots.class);
        when(vanilla.getSlot(9)).thenReturn(new Object());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertFalse(MmoInventorySlots.ACCESSORIES.slot(9)); // Not installed.
            when(plugins.getPlugin("MMOInventory")).thenReturn(plugin);
            assertFalse(MmoInventorySlots.ACCESSORIES.slot(9)); // Disabled.
            when(plugin.isEnabled()).thenReturn(true);
            assertTrue(MmoInventorySlots.ACCESSORIES.slot(9)); // No manager: layout unknown.
            when(((InventoryPlugin) plugin).getInventoryManager()).thenReturn(manager);
            assertFalse(MmoInventorySlots.ACCESSORIES.slot(9)); // No vanilla inventory configured.
            when(manager.getVanilla()).thenReturn(vanilla);
            assertTrue(MmoInventorySlots.ACCESSORIES.slot(9));
            assertFalse(MmoInventorySlots.ACCESSORIES.slot(0));
        }
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            assertTrue(MmoInventorySlots.ACCESSORIES.placeholder(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_HOE).serializeAsBytes()));
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
        }
    }

    @Test void absentOrDisabledPluginLeavesTheBodyUntouched() throws Exception {
        Player player = mock(Player.class);
        PluginManager plugins = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            MmoInventorySlots.refresh(player);
            Plugin disabled = mock(Plugin.class);
            when(plugins.getPlugin("MMOInventory")).thenReturn(disabled);
            MmoInventorySlots.refresh(player);
            verifyNoInteractions(player);
        }
    }

    @Test void delegatesOnlyToTheConfiguredVanillaPlaceholderApi() throws Exception {
        Player player = mock(Player.class);
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class, withSettings().extraInterfaces(InventoryPlugin.class));
        InventoryManager manager = mock(InventoryManager.class);
        VanillaInventory vanilla = mock(VanillaInventory.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(((InventoryPlugin) plugin).getInventoryManager()).thenReturn(manager);
        when(plugins.getPlugin("MMOInventory")).thenReturn(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            MmoInventorySlots.refresh(player); // No vanilla inventory is configured.
            verifyNoInteractions(player, vanilla);
            when(manager.getVanilla()).thenReturn(vanilla);
            MmoInventorySlots.refresh(player);
            verify(vanilla).replacePlaceholderItems(player);
            verifyNoMoreInteractions(vanilla);
            verifyNoInteractions(player); // The bridge never reads, clears, or copies account inventory.
        }
    }

    @Test void incompatibleOrFailingPresentApiStopsActivationWithAnIoFailure() throws Exception {
        Player player = mock(Player.class);
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class, withSettings().extraInterfaces(InventoryPlugin.class));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("MMOInventory")).thenReturn(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertThrows(IOException.class, () -> MmoInventorySlots.refresh(player)); // Missing manager.
            when(((InventoryPlugin) plugin).getInventoryManager()).thenReturn(new Object());
            assertInstanceOf(NoSuchMethodException.class,
                    assertThrows(IOException.class, () -> MmoInventorySlots.refresh(player)).getCause());
            InventoryManager manager = mock(InventoryManager.class);
            VanillaInventory vanilla = mock(VanillaInventory.class);
            when(((InventoryPlugin) plugin).getInventoryManager()).thenReturn(manager);
            when(manager.getVanilla()).thenReturn(vanilla);
            doThrow(new IllegalStateException("refresh failed")).when(vanilla).replacePlaceholderItems(player);
            IOException failure = assertThrows(IOException.class, () -> MmoInventorySlots.refresh(player));
            assertEquals("MMOInventory accessory slots could not be restored", failure.getMessage());
            assertInstanceOf(IllegalStateException.class, failure.getCause().getCause());
        }
    }
}
