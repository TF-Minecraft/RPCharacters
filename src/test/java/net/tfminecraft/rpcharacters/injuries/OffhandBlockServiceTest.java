package net.tfminecraft.rpcharacters.injuries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import io.lumine.mythic.lib.MythicLib;
import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.BooleanData;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.*;

class OffhandBlockServiceTest extends HealingRuntimeFixture {
    World world; final List<Item> drops = new ArrayList<>();
    MockedStatic<NBTItem> nbt;
    MockedConstruction<LiveMMOItem> models;
    boolean typed, twoHanded;
    BooleanData twoHandedStat;
    Runnable afterDrop = () -> {};

    @BeforeEach void setupOffhand() {
        MMOItems.plugin = mock(MMOItems.class, RETURNS_DEEP_STUBS); MythicLib.plugin = mock(MythicLib.class, RETURNS_DEEP_STUBS);
        when(MMOItems.plugin.getName()).thenReturn("MMOItems"); when(MMOItems.plugin.namespace()).thenReturn("mmoitems");
        when(MythicLib.plugin.getName()).thenReturn("MythicLib"); when(MythicLib.plugin.namespace()).thenReturn("mythiclib");
        world = spy(player.getWorld()); doReturn(world).when(player).getWorld();
        doAnswer(call -> { Item item = (Item) call.callRealMethod(); drops.add(item); afterDrop.run(); return item; })
            .when(world).dropItemNaturally(any(Location.class), any(ItemStack.class));
        nbt = boundary(NBTItem.class); nbt.when(() -> NBTItem.get(any(ItemStack.class))).thenAnswer(call -> {
            var wrapper = mock(NBTItem.class); when(wrapper.hasType()).thenReturn(typed); return wrapper;
        });
        models = mockConstruction(LiveMMOItem.class, (instance, context) ->
            when(instance.getData(ItemStats.TWO_HANDED)).thenAnswer(call -> twoHandedStat));
        boundaries.add(models);
    }

    void blocked() { character.addTrait(trait("missing-hand", "injury", null, "block-offhand", true)); }
    void offhand(Material material) { player.getInventory().setItemInOffHand(new ItemStack(material)); }
    void mainhand(Material material) { player.getInventory().setItemInMainHand(new ItemStack(material)); }

    @Test void timerStartsImmediatelyAndDropsACloneWithMetadataAndPickupDelay() {
        blocked(); var held = new ItemStack(Material.SHIELD, 2); var meta = held.getItemMeta();
        meta.setDisplayName("Bound shield"); meta.getPersistentDataContainer().set(new NamespacedKey("test", "owner"), PersistentDataType.STRING, "patient");
        held.setItemMeta(meta); player.getInventory().setItemInOffHand(held);
        OffhandBlockService.start(); assertEquals(0, delay); assertEquals(20, period); timer.run();
        assertEquals(1, drops.size()); var dropped = drops.getFirst(); assertEquals(held, dropped.getItemStack());
        assertNotSame(held, dropped.getItemStack()); assertEquals(40, dropped.getPickupDelay());
        assertTrue(player.getInventory().getItemInOffHand().getType().isAir()); assertEquals("Bound shield", dropped.getItemStack().getItemMeta().getDisplayName());
        assertEquals("patient", dropped.getItemStack().getItemMeta().getPersistentDataContainer().get(new NamespacedKey("test", "owner"), PersistentDataType.STRING));
        assertTrue(message().contains("drop your item")); assertNull(player.nextMessage());
    }

    @Test void ordinaryAndEmptyHandsArePreservedWithoutBlockingTraits() {
        character.getTraits().add(null); character.addTrait(trait("ordinary", "background", null));
        offhand(Material.SHIELD); mainhand(Material.DIAMOND_SWORD); OffhandBlockService.tick();
        assertTrue(drops.isEmpty()); assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertNull(player.nextMessage()); nbt.verifyNoInteractions();
        character.getTraits().remove(null); blocked(); offhand(Material.AIR); mainhand(Material.AIR);
        OffhandBlockService.tick(); assertTrue(drops.isEmpty()); assertNull(player.nextMessage());
    }

    @Test void onlyBlockedSurvivalPlayersWithLivingLoadedActiveCharactersAreProcessed() {
        blocked(); offhand(Material.SHIELD);
        doReturn(true).when(player).isDead(); OffhandBlockService.tick(); doReturn(false).when(player).isDead();
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(true);
        OffhandBlockService.tick(); deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(false);
        CreationManager.activeCreators.put(player, mock(CharacterCreation.class)); OffhandBlockService.tick(); CreationManager.activeCreators.clear();
        player.setGameMode(GameMode.CREATIVE); OffhandBlockService.tick(); player.setGameMode(GameMode.SURVIVAL);
        loaded.clear(); OffhandBlockService.tick(); loaded.put(player, new PlayerData(player)); OffhandBlockService.tick();
        assertTrue(drops.isEmpty()); assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType()); assertNull(player.nextMessage());
    }

    @Test void mmoTwoHandedWeaponAndOffhandDropTogetherWithOneMessage() {
        blocked(); typed = true; twoHandedStat = new BooleanData(true);
        mainhand(Material.DIAMOND_SWORD); offhand(Material.SHIELD); OffhandBlockService.tick();
        assertEquals(List.of(Material.SHIELD, Material.DIAMOND_SWORD), drops.stream().map(item -> item.getItemStack().getType()).toList());
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir()); assertTrue(player.getInventory().getItemInOffHand().getType().isAir());
        assertTrue(message().contains("drop your item")); assertNull(player.nextMessage());
    }

    @Test void missingFalseAndUnreadableMmoTwoHandedFlagsPreserveTheMainHand() {
        blocked(); mainhand(Material.DIAMOND_SWORD); OffhandBlockService.tick(); assertTrue(models.constructed().isEmpty());
        typed = true; OffhandBlockService.tick(); assertEquals(1, models.constructed().size());
        twoHandedStat = new BooleanData(false); OffhandBlockService.tick();
        nbt.when(() -> NBTItem.get(any(ItemStack.class))).thenThrow(new IllegalArgumentException("Unreadable metadata"));
        assertDoesNotThrow(OffhandBlockService::tick); assertTrue(drops.isEmpty());
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().getItemInMainHand().getType()); assertNull(player.nextMessage());
    }

    @Test void spawnCallbacksCannotDestroyANewOffhandReplacement() {
        blocked(); offhand(Material.SHIELD); var replacement = new ItemStack(Material.DIAMOND, 3);
        afterDrop = () -> player.getInventory().setItemInOffHand(replacement);
        OffhandBlockService.tick(); assertEquals(1, drops.size());
        assertEquals(Material.SHIELD, drops.getFirst().getItemStack().getType());
        assertEquals(replacement, player.getInventory().getItemInOffHand(), "A spawn callback may replace the slot before dropItemNaturally returns");
    }

    @Test void spawnCallbacksCannotDestroyANewMainHandReplacement() {
        blocked(); typed = true; twoHandedStat = new BooleanData(true); mainhand(Material.DIAMOND_SWORD);
        var replacement = new ItemStack(Material.DIAMOND, 3); afterDrop = () -> player.getInventory().setItemInMainHand(replacement);
        OffhandBlockService.tick(); assertEquals(1, drops.size());
        assertEquals(Material.DIAMOND_SWORD, drops.getFirst().getItemStack().getType());
        assertEquals(replacement, player.getInventory().getItemInMainHand());
    }

    @Test void failedSpawnPreservesTheHeldStack() {
        blocked(); var held = new ItemStack(Material.SHIELD, 2); player.getInventory().setItemInOffHand(held);
        doThrow(new IllegalStateException("World could not spawn item"))
            .when(world).dropItemNaturally(any(Location.class), any(ItemStack.class));
        assertThrows(IllegalStateException.class, OffhandBlockService::tick);
        assertEquals(held, player.getInventory().getItemInOffHand()); assertTrue(drops.isEmpty());
    }
}
