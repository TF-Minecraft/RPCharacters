package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent.EquipMethod;
import net.tfminecraft.tlibs.armour.ArmorType;

class ArmourDonningTest {
    @TempDir Path folder;
    ServerMock server; PlayerMock player; RuntimeTestState state; ArmourDonning listener;
    BukkitScheduler scheduler; Runnable timer; BukkitTask timerTask; final Deque<Runnable> nextTick = new ArrayDeque<>();
    final List<ArmorEquipEvent> completions = new ArrayList<>(); Consumer<ArmorEquipEvent> otherPlugins = event -> {};
    final List<MockedStatic<?>> mocks = new ArrayList<>(); MockedStatic<MaskService> masks;
    ItemStack chestplate, boots;
    long now = 1_000_000L;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        chestplate = new ItemStack(Material.IRON_CHESTPLATE); boots = new ItemStack(Material.IRON_BOOTS);
        state = new RuntimeTestState(PvpLoader.class, ArmourDonning.class, PvpSituations.class);
        ArmourDonning.clear(); PvpSituations.clear();
        RPCharacters.plugin = mock(RPCharacters.class);
        loadConfig("");
        masks = mockStatic(MaskService.class); mocks.add(masks);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); mocks.add(bukkit);
        scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(RPCharacters.plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timer = call.getArgument(1); timerTask = mock(BukkitTask.class); return timerTask; });
        when(scheduler.runTask(eq(RPCharacters.plugin), any(Runnable.class))).thenAnswer(call -> {
            nextTick.add(call.getArgument(1)); return mock(BukkitTask.class); });
        PluginManager plugins = mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        doAnswer(call -> {
            ArmorEquipEvent event = call.getArgument(0);
            listener.onArmorEquip(event); otherPlugins.accept(event);
            if (!event.isCancelled()) { completions.add(event); listener.onArmorChanged(event); }
            return null;
        }).when(plugins).callEvent(any(ArmorEquipEvent.class));
        listener = new ArmourDonning();
        player = server.addPlayer("Knight");
    }

    @AfterEach void cleanup() {
        ArmourDonning.clear(); PvpSituations.clear();
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MockBukkit.unmock(); state.close();
    }

    void loadConfig(String yaml) throws Exception {
        Path file = folder.resolve("pvp.yml"); Files.writeString(file, yaml); new PvpLoader().load(file.toFile());
    }

    ArmorEquipEvent equip(ArmorType type, ItemStack piece, EquipMethod method) {
        ArmorEquipEvent event = new ArmorEquipEvent(player, method, type, null, piece);
        listener.onArmorEquip(event); return event;
    }

    void tickAt(long ms) { now = ms; ArmourDonning.tick(ms); }

    List<String> messages() { List<String> out = new ArrayList<>(); String line; while ((line = player.nextMessage()) != null) out.add(line); return out; }

    boolean said(String text) { return messages().stream().anyMatch(line -> line.contains(text)); }

    void lockInFight() { PvpSituations.track(new PvpSituation(player.getUniqueId(), List.of(player.getUniqueId()))); }

    @Test void onlyRealArmourIsFastenedSlowly() {
        assertFalse(ArmourDonning.isArmourPiece(null));
        assertFalse(ArmourDonning.isArmourPiece(new ItemStack(Material.AIR)));
        assertFalse(ArmourDonning.isArmourPiece(new ItemStack(Material.PLAYER_HEAD)));
        assertFalse(ArmourDonning.isArmourPiece(new ItemStack(Material.ELYTRA)));
        ItemStack mask = new ItemStack(Material.LEATHER_HELMET);
        masks.when(() -> MaskService.isMaskItem(mask)).thenReturn(true);
        assertFalse(ArmourDonning.isArmourPiece(mask));
        for (Material material : List.of(Material.DIAMOND_HELMET, Material.IRON_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.GOLDEN_BOOTS)) {
            assertTrue(ArmourDonning.isArmourPiece(new ItemStack(material)), material.name());
        }
    }

    @Test void configuredTimesSlowAndBarDefaultsApply() throws Exception {
        assertEquals(1_000L, PvpLoader.getDonMs(ArmorType.HELMET));
        assertEquals(20_000L, PvpLoader.getDonMs(ArmorType.CHESTPLATE));
        assertEquals(12_000L, PvpLoader.getDonMs(ArmorType.LEGGINGS));
        assertEquals(8_000L, PvpLoader.getDonMs(ArmorType.BOOTS));
        assertEquals(1, PvpLoader.getDonSlownessAmplifier());
        assertEquals(180_000L, PvpLoader.getRecentArmourMs());
        loadConfig("armour:\n  don-seconds:\n    helmet: -5\n    boots: 3\n  slowness-amplifier: -4\n  recent-seconds: 60\nmessages:\n  don-done: done {piece}\n");
        assertEquals(0L, PvpLoader.getDonMs(ArmorType.HELMET));
        assertEquals(3_000L, PvpLoader.getDonMs(ArmorType.BOOTS));
        assertEquals(-1, PvpLoader.getDonSlownessAmplifier());
        assertEquals(60_000L, PvpLoader.getRecentArmourMs());
        assertEquals("done {piece}", PvpLoader.getDonDone());
    }

    @Test void piecesAreFastenedInTurnWhileSlowed() {
        ArmourDonning.start(); BukkitTask firstTimer = timerTask; ArmourDonning.start();
        verify(firstTimer).cancel();
        player.getInventory().setItem(3, chestplate.clone()); player.getInventory().setItem(4, boots.clone());
        ArmorEquipEvent first = equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        assertTrue(first.isCancelled());
        assertNull(player.getInventory().getChestplate());
        PotionEffect slowed = player.getPotionEffect(PotionEffectType.SLOWNESS);
        assertNotNull(slowed); assertEquals(1, slowed.getAmplifier());
        assertTrue(equip(ArmorType.BOOTS, boots, EquipMethod.HOTBAR).isCancelled());
        equip(ArmorType.BOOTS, boots, EquipMethod.HOTBAR);
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        timer.run();
        tickAt(System.currentTimeMillis() + 5_000L);
        assertNull(player.getInventory().getChestplate(), "Twenty seconds have not passed yet");
        tickAt(System.currentTimeMillis() + 21_000L);
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertNull(player.getInventory().getItem(3));
        assertEquals(1, completions.size()); assertEquals(EquipMethod.SHIFT_CLICK, completions.getFirst().getMethod());
        assertNull(player.getInventory().getBoots());
        tickAt(now + 9_000L);
        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType());
        assertNull(player.getPotionEffect(PotionEffectType.SLOWNESS), "Slowness ends with the last piece");
        tickAt(now + 1_000L);
        ArmourDonning.shutdown();
        verify(timerTask).cancel();
        ArmourDonning.shutdown();
    }

    @Test void swappingFromTheCursorKeepsTheRestOfTheStackAndReturnsTheOldPiece() {
        ItemStack worn = new ItemStack(Material.LEATHER_CHESTPLATE); player.getInventory().setChestplate(worn);
        ItemStack stack = chestplate.clone(); stack.setAmount(2); player.setItemOnCursor(stack);
        ArmorEquipEvent swap = new ArmorEquipEvent(player, EquipMethod.PICK_DROP, ArmorType.CHESTPLATE, worn, stack);
        listener.onArmorEquip(swap);
        assertTrue(swap.isCancelled());
        tickAt(System.currentTimeMillis() + 30_000L);
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertEquals(1, player.getItemOnCursor().getAmount());
        assertTrue(player.getInventory().contains(Material.LEATHER_CHESTPLATE));
        assertEquals(Material.LEATHER_CHESTPLATE, completions.getFirst().getOldArmorPiece().getType());
    }

    @Test void theLastPieceOnTheCursorAndOffHandAreUsedUp() {
        player.setItemOnCursor(chestplate.clone());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.PICK_DROP);
        player.getInventory().setItemInOffHand(boots.clone());
        equip(ArmorType.BOOTS, boots, EquipMethod.HOTBAR_SWAP);
        tickAt(System.currentTimeMillis() + 21_000L); tickAt(now + 9_000L);
        assertTrue(player.getItemOnCursor().getType().isAir());
        assertTrue(player.getInventory().getItemInOffHand().getType().isAir());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType());
    }

    @Test void aPieceThatWasPutAwayOrRefusedIsNotFastened() {
        player.getInventory().setItem(0, chestplate.clone());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        player.getInventory().setItem(0, null);
        tickAt(System.currentTimeMillis() + 21_000L);
        assertTrue(said("no longer have the chestplate"));
        assertNull(player.getInventory().getChestplate());
        player.getInventory().setItem(0, boots.clone());
        otherPlugins = event -> event.setCancelled(true);
        equip(ArmorType.BOOTS, boots, EquipMethod.SHIFT_CLICK);
        tickAt(now + 9_000L);
        assertTrue(said("stopped fastening your boots"));
        assertNull(player.getInventory().getBoots());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getItem(0).getType());
    }

    @Test void creativeHelmetsZeroTimesAndDispensersAreNotQueued() throws Exception {
        assertFalse(equip(ArmorType.HELMET, new ItemStack(Material.PLAYER_HEAD), EquipMethod.HOTBAR).isCancelled());
        player.setGameMode(GameMode.CREATIVE);
        assertFalse(equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK).isCancelled());
        player.setGameMode(GameMode.SPECTATOR);
        assertFalse(equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK).isCancelled());
        player.setGameMode(GameMode.SURVIVAL);
        assertTrue(equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.DISPENSER).isCancelled());
        tickAt(System.currentTimeMillis() + 60_000L);
        assertTrue(completions.isEmpty(), "A dispenser can't fasten armour onto someone");
        loadConfig("armour:\n  don-seconds:\n    leggings: 0\n");
        assertFalse(equip(ArmorType.LEGGINGS, new ItemStack(Material.IRON_LEGGINGS), EquipMethod.SHIFT_CLICK).isCancelled());
        assertFalse(equip(ArmorType.CHESTPLATE, null, EquipMethod.SHIFT_CLICK).isCancelled());
        ArmorEquipEvent nobody = mock(ArmorEquipEvent.class);
        listener.onArmorEquip(nobody); listener.onArmorChanged(nobody);
        verify(nobody, never()).setCancelled(anyBoolean());
        ArmorEquipEvent untyped = new ArmorEquipEvent(player, EquipMethod.PICK_DROP, null, null, new ItemStack(Material.IRON_HELMET));
        listener.onArmorEquip(untyped); listener.onArmorChanged(untyped);
        assertFalse(untyped.isCancelled(), "Items tlibs can't place are left to vanilla");
    }

    @Test void slownessCanBeTurnedOffAndOtherSlownessIsLeftAlone() throws Exception {
        loadConfig("armour:\n  slowness-amplifier: -1\n");
        player.getInventory().setItem(0, boots.clone());
        equip(ArmorType.BOOTS, boots, EquipMethod.SHIFT_CLICK);
        assertNull(player.getPotionEffect(PotionEffectType.SLOWNESS));
        tickAt(System.currentTimeMillis() + 9_000L);
        loadConfig("");
        player.getInventory().setItem(0, chestplate.clone());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 400, 3, false, false));
        ArmourDonning.stop(player, false);
        assertEquals(3, player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "A stronger slowness came from something else");
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 400, 1, false, true));
        ArmourDonning.stop(player, false);
        assertNotNull(player.getPotionEffect(PotionEffectType.SLOWNESS), "Visible slowness came from something else");
    }

    @Test void damageSprintingDeathAndQuittingStopFastening() {
        ArmourDonning.stop(player, true);
        assertTrue(messages().isEmpty());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        EntityDamageEvent fallen = mock(EntityDamageEvent.class); when(fallen.getEntity()).thenReturn(player);
        listener.onDamage(fallen);
        assertTrue(said("stopped fastening your chestplate"));
        assertNull(player.getPotionEffect(PotionEffectType.SLOWNESS));

        Zombie zombie = mock(Zombie.class);
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        listener.onDamage(hit(zombie, player));
        assertTrue(said("stopped fastening"), "Being hit stops it");
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        listener.onDamage(hit(player, zombie));
        assertTrue(said("stopped fastening"), "Attacking stops it too");
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        listener.onDamage(hit(arrow, zombie));
        assertTrue(said("stopped fastening"), "So does shooting someone");
        when(arrow.getShooter()).thenReturn(zombie);
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        listener.onDamage(hit(arrow, zombie));
        listener.onDamage(hit(zombie, zombie));
        EntityDamageEvent fall = mock(EntityDamageEvent.class); when(fall.getEntity()).thenReturn(zombie);
        listener.onDamage(fall);
        assertFalse(said("stopped fastening"), "Fights between others don't");
        ArmourDonning.stop(player, false);

        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        listener.onSprint(new PlayerToggleSprintEvent(player, false));
        tickAt(System.currentTimeMillis() + 1_000L);
        assertFalse(said("stopped fastening"));
        listener.onSprint(new PlayerToggleSprintEvent(player, true));
        assertTrue(said("stopped fastening"));

        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class); when(death.getEntity()).thenReturn(player);
        listener.onDeath(death);
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player);
        listener.onQuit(quit);
        assertFalse(said("stopped fastening"), "Dying or leaving stops it quietly");
        tickAt(System.currentTimeMillis() + 60_000L);
        assertTrue(completions.isEmpty());
    }

    @Test void offlinePlayersAreDroppedAndShutdownSkipsThem() {
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        player.disconnect();
        ArmourDonning.shutdown();
        PlayerMock squire = server.addPlayer("Squire");
        listener.onArmorEquip(new ArmorEquipEvent(squire, EquipMethod.SHIFT_CLICK, ArmorType.BOOTS, null, boots));
        squire.disconnect();
        tickAt(System.currentTimeMillis() + 60_000L);
        assertTrue(completions.isEmpty());
    }

    @Test void leggingsGoOnAndShutdownStopsFastening() {
        assertFalse(PvpSituations.locksArmour(null));
        ItemStack leggings = new ItemStack(Material.IRON_LEGGINGS);
        player.getInventory().setItem(0, leggings.clone());
        equip(ArmorType.LEGGINGS, leggings, EquipMethod.SHIFT_CLICK);
        tickAt(System.currentTimeMillis() + 13_000L);
        assertEquals(Material.IRON_LEGGINGS, player.getInventory().getLeggings().getType());
        player.getInventory().setItem(0, chestplate.clone());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        ArmourDonning.shutdown();
        assertNull(player.getPotionEffect(PotionEffectType.SLOWNESS));
        tickAt(System.currentTimeMillis() + 60_000L);
        assertNull(player.getInventory().getChestplate());
    }

    @Test void aFightCalledWhileFasteningStopsIt() {
        player.getInventory().setItem(0, chestplate.clone());
        equip(ArmorType.CHESTPLATE, chestplate, EquipMethod.SHIFT_CLICK);
        lockInFight();
        tickAt(System.currentTimeMillis() + 21_000L);
        assertTrue(said("can't put armour on"));
        assertNull(player.getInventory().getChestplate());
        assertNull(player.getPotionEffect(PotionEffectType.SLOWNESS));
    }

    @Test void pvpStartTakesOffOnlyArmourPutOnRecently() throws Exception {
        assertFalse(ArmourDonning.takeOffRecent(player, now));
        ItemStack helmet = new ItemStack(Material.IRON_HELMET);
        listener.onArmorChanged(new ArmorEquipEvent(player, EquipMethod.SHIFT_CLICK, ArmorType.HELMET, null, helmet));
        listener.onArmorChanged(new ArmorEquipEvent(player, EquipMethod.SHIFT_CLICK, ArmorType.HELMET, helmet, null));
        long put = System.currentTimeMillis();
        player.getInventory().setHelmet(new ItemStack(Material.PLAYER_HEAD));
        listener.onArmorChanged(new ArmorEquipEvent(player, EquipMethod.HOTBAR, ArmorType.HELMET, null, new ItemStack(Material.PLAYER_HEAD)));
        player.getInventory().setChestplate(chestplate.clone());
        listener.onArmorChanged(new ArmorEquipEvent(player, EquipMethod.SHIFT_CLICK, ArmorType.CHESTPLATE, null, chestplate));
        player.getInventory().setBoots(boots.clone());
        listener.onArmorChanged(new ArmorEquipEvent(player, EquipMethod.SHIFT_CLICK, ArmorType.BOOTS, null, boots));
        loadConfig("armour:\n  recent-seconds: 0\n");
        assertFalse(ArmourDonning.takeOffRecent(player, put));
        loadConfig("");
        assertFalse(ArmourDonning.takeOffRecent(player, put + 181_000L), "Armour on for longer than three minutes stays");
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, null);
        player.getInventory().setItem(8, boots.clone());
        equip(ArmorType.BOOTS, boots, EquipMethod.SHIFT_CLICK);
        assertTrue(ArmourDonning.takeOffRecent(player, put + 60_000L));
        List<String> lines = messages();
        assertTrue(lines.stream().anyMatch(line -> line.contains("chestplate, boots")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("stopped fastening your boots")));
        assertNull(player.getInventory().getChestplate());
        assertNull(player.getInventory().getBoots());
        assertEquals(Material.PLAYER_HEAD, player.getInventory().getHelmet().getType(), "Heads aren't armour");
        assertTrue(player.getInventory().contains(Material.IRON_CHESTPLATE));
        assertEquals(1, player.getWorld().getEntities().stream().filter(org.bukkit.entity.Item.class::isInstance).count(), "What doesn't fit is dropped at their feet");
        assertFalse(ArmourDonning.takeOffRecent(player, put + 60_000L), "Each piece comes off once");
    }

    static EntityDamageByEntityEvent hit(org.bukkit.entity.Entity damager, org.bukkit.entity.Entity victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager); when(event.getEntity()).thenReturn(victim); return event;
    }

    PlayerInteractEvent rightClick(Action action, ItemStack held, EquipmentSlot hand) {
        return new PlayerInteractEvent(player, action, held, action == Action.RIGHT_CLICK_BLOCK ? mock(Block.class) : null,
            org.bukkit.block.BlockFace.UP, hand);
    }

    @Test void rightClickSwapsAreUndoneAndFastenedInstead() {
        ItemStack worn = new ItemStack(Material.LEATHER_CHESTPLATE); player.getInventory().setChestplate(worn.clone());
        player.getInventory().setHeldItemSlot(2); player.getInventory().setItem(2, chestplate.clone());
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_AIR, chestplate, EquipmentSlot.HAND));
        assertEquals(1, nextTick.size());
        // The vanilla swap happens before the next tick.
        player.getInventory().setChestplate(chestplate.clone()); player.getInventory().setItem(2, worn.clone());
        nextTick.poll().run();
        assertEquals(Material.LEATHER_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getItem(2).getType());
        tickAt(System.currentTimeMillis() + 21_000L);
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertEquals(EquipMethod.HOTBAR, completions.getFirst().getMethod());
        assertTrue(player.getInventory().contains(Material.LEATHER_CHESTPLATE));
    }

    @Test void rightClicksThatSwapNothingAreIgnored() {
        PlayerInteractEvent denied = rightClick(Action.RIGHT_CLICK_AIR, chestplate, EquipmentSlot.HAND);
        denied.setUseItemInHand(Result.DENY);
        listener.onRightClickArmour(denied);
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_AIR, chestplate, null));
        listener.onRightClickArmour(rightClick(Action.LEFT_CLICK_AIR, chestplate, EquipmentSlot.HAND));
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK), EquipmentSlot.HAND));
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_BLOCK, chestplate, EquipmentSlot.HAND));
        player.getInventory().setChestplate(chestplate.clone());
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_AIR, chestplate, EquipmentSlot.HAND));
        assertTrue(nextTick.isEmpty());

        ItemStack worn = new ItemStack(Material.LEATHER_CHESTPLATE); player.getInventory().setChestplate(worn.clone());
        player.getInventory().setItemInOffHand(chestplate.clone());
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_BLOCK, chestplate, EquipmentSlot.OFF_HAND));
        nextTick.poll().run();
        assertEquals(Material.LEATHER_CHESTPLATE, player.getInventory().getChestplate().getType(), "No swap happened, e.g. a chest opened");

        ArmourDonning.undoSwap(player, ArmorType.CHESTPLATE, 40, worn, new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setChestplate(chestplate.clone()); player.getInventory().setItemInOffHand(worn.clone());
        player.setGameMode(GameMode.CREATIVE);
        ArmourDonning.undoSwap(player, ArmorType.CHESTPLATE, 40, chestplate, worn);
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType(), "Creative swaps stay");
        player.setGameMode(GameMode.SURVIVAL);
        player.disconnect();
        ArmourDonning.undoSwap(player, ArmorType.CHESTPLATE, 40, chestplate, worn);
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertTrue(completions.isEmpty());
    }

    @Test void swapsDuringAFightAreUndoneWithoutFastening() {
        lockInFight();
        ItemStack mask = new ItemStack(Material.CARVED_PUMPKIN), head = new ItemStack(Material.PLAYER_HEAD);
        player.getInventory().setHelmet(mask.clone()); player.getInventory().setItemInOffHand(head.clone());
        listener.onRightClickArmour(rightClick(Action.RIGHT_CLICK_AIR, head, EquipmentSlot.OFF_HAND));
        player.getInventory().setHelmet(head.clone()); player.getInventory().setItemInOffHand(mask.clone());
        nextTick.poll().run();
        assertEquals(Material.CARVED_PUMPKIN, player.getInventory().getHelmet().getType());
        assertTrue(said("can't put armour on"));
        tickAt(System.currentTimeMillis() + 5_000L);
        assertTrue(completions.isEmpty());
    }
}
