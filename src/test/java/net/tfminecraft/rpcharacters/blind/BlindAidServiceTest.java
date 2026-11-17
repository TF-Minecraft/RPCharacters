package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import net.tfminecraft.rpcharacters.blind.Surroundings.Kind;

class BlindAidServiceTest {
    ServerMock server; Plugin plugin; World world; World otherWorld; GlowPackets glow;
    final AtomicReference<BlindAidSettings> settings = new AtomicReference<>(BlindAidSettings.defaults());
    final Set<Player> blindPlayers = new HashSet<>();
    final List<Player> online = new ArrayList<>();
    final Map<UUID, Entity> entities = new HashMap<>();
    final Map<String, Kind> blocks = new HashMap<>();
    final AtomicLong now = new AtomicLong(1_000_000);
    final List<BlockDisplay> spawned = new ArrayList<>();
    BlindAidService service;

    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.createMockPlugin();
        world = world(); otherWorld = world(); glow = mock(GlowPackets.class);
        service = service(glow);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    BlindAidService service(GlowPackets glow) {
        return new BlindAidService(plugin, settings::get, blindPlayers::contains,
                (item, paths) -> item.getType() == Material.STICK && paths.contains("m.props.walking_cane"), glow,
                w -> (x, y, z) -> blocks.getOrDefault(x + "," + y + "," + z, y <= 63 ? Kind.SOLID : Kind.OPEN),
                now::get, () -> online, entities::get);
    }

    @SuppressWarnings("unchecked")
    World world() {
        World w = mock(World.class);
        Block block = mock(Block.class); when(block.getBlockData()).thenReturn(mock(BlockData.class));
        when(w.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(w.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(w.spawn(any(Location.class), eq(BlockDisplay.class), any(Consumer.class))).thenAnswer(call -> {
            BlockDisplay display = mock(BlockDisplay.class);
            ((Consumer<BlockDisplay>) call.getArgument(2)).accept(display);
            spawned.add(display);
            return display;
        });
        return w;
    }

    /** A player mock whose position, input, food and held items the test can change. */
    final class Body {
        final Player p = mock(Player.class);
        final UUID id = UUID.randomUUID();
        final AttributeInstance speed = mock(AttributeInstance.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        AttributeModifier modifier;
        Location at = new Location(world, 0.5, 64, 0.5, -90, 0); // facing +x
        boolean forward = true, sprint = true, sneak;
        float exhaustion;

        Body(boolean blind) {
            when(p.getUniqueId()).thenReturn(id);
            when(p.getLocation()).thenAnswer(call -> at.clone());
            when(p.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(speed);
            when(speed.getModifier(BlindAidService.STRIDE_KEY)).thenAnswer(call -> modifier);
            doAnswer(call -> { modifier = call.getArgument(0); return null; }).when(speed).addTransientModifier(any());
            doAnswer(call -> { modifier = null; return null; }).when(speed).removeModifier(BlindAidService.STRIDE_KEY);
            when(p.getCurrentInput()).thenAnswer(call -> input(forward, sprint, sneak));
            when(p.getFoodLevel()).thenReturn(20);
            when(p.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.STICK));
            when(inventory.getItemInOffHand()).thenReturn(new ItemStack(Material.AIR));
            when(p.getExhaustion()).thenAnswer(call -> exhaustion);
            doAnswer(call -> { exhaustion = call.getArgument(0); return null; }).when(p).setExhaustion(anyFloat());
            when(p.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
            when(p.canSee(any(Player.class))).thenReturn(true);
            when(p.hasLineOfSight(any(Entity.class))).thenReturn(true);
            online.add(p);
            if (blind) blindPlayers.add(p);
        }
    }

    static Input input(boolean forward, boolean sprint, boolean sneak) {
        Input input = mock(Input.class);
        when(input.isForward()).thenReturn(forward); when(input.isSprint()).thenReturn(sprint); when(input.isSneak()).thenReturn(sneak);
        return input;
    }

    LivingEntity creature(Location at) {
        LivingEntity e = mock(LivingEntity.class); UUID id = UUID.randomUUID();
        when(e.getUniqueId()).thenReturn(id); when(e.getLocation()).thenAnswer(call -> at.clone());
        entities.put(id, e);
        return e;
    }

    BlindAidSettings with(String yaml) {
        var c = new org.bukkit.configuration.file.YamlConfiguration();
        try { c.loadFromString(yaml); } catch (Exception e) { throw new IllegalStateException(e); }
        return BlindAidSettings.from(c);
    }

    // ------------------------------------------------------------------ lifecycle

    @Test void startRunsEveryCheckAndStopClearsEveryone() {
        Body b = new Body(true);
        service.start(); service.start();
        server.getScheduler().performTicks(5);
        assertNotNull(b.modifier, "striding after the first check");
        assertNotNull(service.viewer(b.p));
        service.stop();
        assertNull(b.modifier); assertNull(service.viewer(b.p));
        server.getScheduler().performTicks(10);
        assertNull(b.modifier, "no checks after stop");
        service.stop();
    }

    @Test void sightedPlayersAndDisabledSettingsGetNothing() {
        Body sighted = new Body(false); Body b = new Body(true);
        service.tick();
        assertNull(sighted.modifier); assertNull(service.viewer(sighted.p)); assertNotNull(b.modifier);
        settings.set(with("enabled: false"));
        service.tick();
        assertNull(b.modifier); assertNull(service.viewer(b.p));
    }

    // ------------------------------------------------------------------ stride

    @Test void strideNeedsSprintForwardFoodAndACane() {
        Body b = new Body(true);
        service.tick(); assertNotNull(b.modifier);
        assertEquals(0.25, b.modifier.getAmount()); assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, b.modifier.getOperation());
        b.sprint = false; service.tick(); assertNull(b.modifier);
        b.sprint = true; b.sneak = true; service.tick(); assertNull(b.modifier);
        b.sneak = false; b.forward = false; service.tick(); assertNull(b.modifier);
        b.forward = true; when(b.p.getFoodLevel()).thenReturn(6); service.tick(); assertNull(b.modifier);
        when(b.p.getFoodLevel()).thenReturn(20); when(b.p.isFlying()).thenReturn(true); service.tick(); assertNull(b.modifier);
        when(b.p.isFlying()).thenReturn(false); when(b.p.isGliding()).thenReturn(true); service.tick(); assertNull(b.modifier);
        when(b.p.isGliding()).thenReturn(false); when(b.p.isSwimming()).thenReturn(true); service.tick(); assertNull(b.modifier);
        when(b.p.isSwimming()).thenReturn(false); when(b.p.isInsideVehicle()).thenReturn(true); service.tick(); assertNull(b.modifier);
        when(b.p.isInsideVehicle()).thenReturn(false);
        when(b.inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.BREAD)); service.tick(); assertNull(b.modifier);
        when(b.inventory.getItemInOffHand()).thenReturn(new ItemStack(Material.STICK)); service.tick(); assertNotNull(b.modifier, "a cane in the off hand works");
        when(b.inventory.getItemInOffHand()).thenReturn(null); service.tick(); assertNull(b.modifier);
        when(b.inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.STICK));
        settings.set(with("stride: {enabled: false}")); service.tick(); assertNull(b.modifier);
        settings.set(BlindAidSettings.defaults()); service.tick(); service.tick();
        verify(b.speed, times(3)).addTransientModifier(any());
    }

    @Test void stridingDrainsHungerByDistanceWalked() {
        Body b = new Body(true);
        service.tick();
        b.at = new Location(world, 2.5, 64, 2.0, -90, 0);       // 2.5 blocks
        service.tick();
        assertEquals(0.25f, b.exhaustion, 1e-5);
        b.sprint = false; b.at = new Location(world, 4.5, 64, 2.0, -90, 0);
        service.tick();
        assertEquals(0.45f, b.exhaustion, 1e-5, "stride was on when this stretch began");
        b.at = new Location(world, 6.5, 64, 2.0, -90, 0);
        service.tick();
        assertEquals(0.45f, b.exhaustion, 1e-5, "no drain after letting go");
        b.at = new Location(otherWorld, 0, 64, 0, -90, 0); b.sprint = true;
        service.tick();
        assertEquals(0.45f, b.exhaustion, 1e-5, "a world change is not walking");
        b.at = new Location(otherWorld, 900, 64, 0, -90, 0);
        service.tick();
        assertEquals(0.45f, b.exhaustion, 1e-5, "a teleport in the same world is not walking");
        b.exhaustion = 39.9f; b.at = new Location(otherWorld, 902, 64, 0, -90, 0);
        service.tick();
        assertEquals(40f, b.exhaustion, 1e-5, "capped like vanilla");
    }

    @Test void releasingSprintBetweenChecksStillCostsHunger() {
        Body b = new Body(true);
        service.tick();                                          // striding from here
        b.at = new Location(world, 2.5, 64, 0.5, -90, 0);
        service.onInput(new PlayerInputEvent(b.p, input(true, false, false)));
        assertNull(b.modifier);
        b.sprint = false;
        service.tick();
        assertEquals(0.2f, b.exhaustion, 1e-5, "the 2 blocks walked while striding are charged");
        b.at = new Location(world, 4.5, 64, 0.5, -90, 0);
        service.tick();
        assertEquals(0.2f, b.exhaustion, 1e-5, "walking without stride is free");
        service.onInput(new PlayerInputEvent(b.p, input(true, true, false)));
        b.at = new Location(world, 5.5, 64, 0.5, -90, 0);
        service.onInput(new PlayerInputEvent(b.p, input(true, false, false)));
        service.tick();
        assertEquals(0.3f, b.exhaustion, 1e-5, "a short burst between checks is charged too");
    }

    @Test void aChangedBonusReplacesTheActiveModifier() {
        Body b = new Body(true);
        service.tick(); assertEquals(0.25, b.modifier.getAmount());
        settings.set(with("stride: {speed-bonus: 0.1}"));
        service.tick(); assertEquals(0.1, b.modifier.getAmount());
        service.tick();
        verify(b.speed, times(2)).addTransientModifier(any());
    }

    @Test void playersWithoutASpeedAttributeAreLeftAlone() {
        Body b = new Body(true); when(b.p.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(null);
        assertDoesNotThrow(service::tick);
        assertFalse(BlindAidService.striding(b.p));
        blindPlayers.clear(); assertDoesNotThrow(service::tick);
    }

    @Test void inputEventsUpdateStrideAtOnceForBlindPlayersOnly() {
        Body b = new Body(true); Body sighted = new Body(false);
        service.onInput(new PlayerInputEvent(b.p, input(true, true, false)));
        assertNull(b.modifier, "not a viewer before the first check");
        service.tick(); assertNotNull(b.modifier);
        service.onInput(new PlayerInputEvent(b.p, input(true, false, false)));
        assertNull(b.modifier);
        service.onInput(new PlayerInputEvent(sighted.p, input(true, true, false)));
        assertNull(sighted.modifier);
    }

    // ------------------------------------------------------------------ sound sense

    @Test void movingCreaturesInSightGlowAndStopGlowingWhenTheyStop() {
        Body b = new Body(true);
        Location spot = new Location(world, 4, 64, 0);
        LivingEntity walker = creature(spot);
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(walker));
        service.tick();
        verify(glow, never()).send(any(), any(), anyBoolean());
        spot.setX(6);
        service.tick();
        verify(glow).send(b.p, walker, true);
        assertEquals(Set.of(walker.getUniqueId()), service.viewer(b.p).glowing);
        service.tick();                                          // stood still
        verify(glow).send(b.p, walker, false);
        assertTrue(service.viewer(b.p).glowing.isEmpty());
    }

    @Test void quietHiddenFarAndUnseenCreaturesStayDark() {
        Body b = new Body(true);
        Location a = new Location(world, 2, 64, 0), c = new Location(world, 3, 64, 0), d = new Location(world, 4, 64, 0);
        Location e2 = new Location(world, 5, 64, 0), f = new Location(world, 1, 64, 2), g = new Location(world, 9, 64, 9);
        Location h = new Location(otherWorld, 1, 64, -2);   // heard last near someone in another world
        LivingEntity sneaker = creature(a); when(sneaker.isSneaking()).thenReturn(true);
        LivingEntity invisible = creature(c); when(invisible.isInvisible()).thenReturn(true);
        Player vanished = mock(Player.class); UUID vid = UUID.randomUUID(); when(vanished.getUniqueId()).thenReturn(vid);
        when(vanished.getLocation()).thenAnswer(call -> d.clone()); when(b.p.canSee(vanished)).thenReturn(false);
        Player spectator = mock(Player.class); UUID sid = UUID.randomUUID(); when(spectator.getUniqueId()).thenReturn(sid);
        when(spectator.getLocation()).thenAnswer(call -> e2.clone()); when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        LivingEntity behindWall = creature(f); when(b.p.hasLineOfSight(behindWall)).thenReturn(false);
        LivingEntity far = creature(g);                           // inside the box, outside the radius
        Item item = mock(Item.class); ArmorStand stand = mock(ArmorStand.class);
        LivingEntity hopper = creature(h);
        Body other = new Body(true); other.at = new Location(otherWorld, 0.5, 64, 0.5);
        when(other.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(hopper));
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(sneaker, invisible, vanished, spectator, behindWall, far, item, stand));
        service.tick();
        for (Location l : List.of(a, c, d, e2, f, g)) l.setX(l.getX() + 1);
        h.setWorld(world); h.setX(2);
        when(other.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of());
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(sneaker, invisible, vanished, spectator, behindWall, far, item, stand, hopper));
        service.tick();
        verify(glow, never()).send(any(), any(), anyBoolean());
        settings.set(with("sound-sense: {line-of-sight: false}"));
        f.setX(f.getX() + 1);
        service.tick();
        verify(glow).send(b.p, behindWall, true);
    }

    @Test void noGlowWithoutProtocolLibOrWithSenseOff() {
        BlindAidService bare = service(null);
        Body b = new Body(true);
        Location spot = new Location(world, 4, 64, 0); LivingEntity walker = creature(spot);
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(walker));
        bare.tick(); spot.setX(6); bare.tick();
        assertTrue(bare.viewer(b.p).glowing.isEmpty());
        settings.set(with("sound-sense: {enabled: false}"));
        service.tick(); spot.setX(8); service.tick();
        verify(glow, never()).send(any(), any(), anyBoolean());
        bare.onQuit(new PlayerQuitEvent(b.p, "bye"));
    }

    @Test void glowingCreaturesThatLeftTheServerAreSkipped() {
        Body b = new Body(true);
        Location spot = new Location(world, 4, 64, 0); LivingEntity walker = creature(spot);
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(walker));
        service.tick(); spot.setX(6); service.tick();
        entities.clear(); when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of());
        service.tick();
        verify(glow, never()).send(b.p, walker, false);
        spot.setX(8); entities.put(walker.getUniqueId(), walker);
        when(b.p.getNearbyEntities(10, 10, 10)).thenReturn(List.of(walker));
        service.tick(); spot.setX(10); service.tick();
        entities.clear();
        service.onQuit(new PlayerQuitEvent(b.p, "bye"));
        verify(glow, never()).send(b.p, walker, false);
    }

    // ------------------------------------------------------------------ cane tap

    PlayerInteractEvent click(Player p, Action action, ItemStack item) {
        return new PlayerInteractEvent(p, action, item, null, org.bukkit.block.BlockFace.UP);
    }

    @Test void rightClickWithACaneOutlinesSurroundingsForAFewSeconds() {
        Body b = new Body(true);
        for (int z = -8; z <= 8; z++) for (int y = 64; y <= 65; y++) blocks.put("3," + y + "," + z, Kind.SOLID);
        blocks.put("-2,63,0", Kind.OPEN); blocks.put("-2,62,0", Kind.OPEN);
        blocks.put("0,64,2", Kind.DOOR); blocks.put("0,63,-2", Kind.HAZARD);
        service.tick();
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK)));
        assertFalse(spawned.isEmpty());
        BlockDisplay first = spawned.getFirst();
        verify(first).setVisibleByDefault(false); verify(first).setPersistent(false); verify(first).setGlowing(true);
        verify(first).setGlowColorOverride(Color.ORANGE);
        verify(b.p, times(spawned.size())).showEntity(eq(plugin), any(BlockDisplay.class));
        verify(b.p).playSound(any(Location.class), eq("minecraft:block.wood.hit"), eq(1f), eq(1f));
        int count = spawned.size();
        service.onInteract(click(b.p, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.STICK)));
        assertEquals(count, spawned.size(), "cooldown");
        server.getScheduler().performTicks(60);
        for (BlockDisplay d : spawned) verify(d).remove();
        now.addAndGet(5000);
        service.onInteract(click(b.p, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.STICK)));
        assertEquals(2 * count, spawned.size());
        service.onQuit(new PlayerQuitEvent(b.p, "bye"));
        for (BlockDisplay d : spawned.subList(count, spawned.size())) verify(d).remove();
    }

    @Test void tapSkipsMarkersInUnloadedChunks() {
        Body b = new Body(true);
        for (int z = -8; z <= 8; z++) for (int y = 64; y <= 65; y++) blocks.put("3," + y + "," + z, Kind.SOLID);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        service.tick();
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK)));
        assertTrue(spawned.isEmpty());
        verify(b.p).playSound(any(Location.class), eq("minecraft:block.wood.hit"), eq(1f), eq(1f));
    }

    @Test void tapIgnoresLeftClicksOtherItemsSightedPlayersAndTheSwitch() {
        Body b = new Body(true); Body sighted = new Body(false);
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK)));  // not a viewer yet
        service.tick();
        service.onInteract(click(b.p, Action.LEFT_CLICK_AIR, new ItemStack(Material.STICK)));
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.BREAD)));
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, null));
        service.onInteract(click(sighted.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK)));
        settings.set(with("cane-tap: {enabled: false}"));
        service.onInteract(click(b.p, Action.RIGHT_CLICK_AIR, new ItemStack(Material.STICK)));
        verify(b.p, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
        verify(sighted.p, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
    }

    @Test void markerBlocksAlwaysDrawAnOutline() {
        assertEquals(Material.ORANGE_STAINED_GLASS, BlindAidService.markerBlock(Surroundings.Marker.HAZARD));
        assertEquals(Material.WHITE_CARPET, BlindAidService.markerBlock(Surroundings.Marker.DROP));
        assertEquals(Material.YELLOW_STAINED_GLASS, BlindAidService.markerBlock(Surroundings.Marker.DOOR));
        assertEquals(Material.WHITE_STAINED_GLASS, BlindAidService.markerBlock(Surroundings.Marker.WALL));
    }

    @Test void markerColours() {
        assertEquals(Color.ORANGE, BlindAidService.colour(Surroundings.Marker.HAZARD));
        assertEquals(Color.RED, BlindAidService.colour(Surroundings.Marker.DROP));
        assertEquals(Color.YELLOW, BlindAidService.colour(Surroundings.Marker.DOOR));
        assertEquals(Color.WHITE, BlindAidService.colour(Surroundings.Marker.WALL));
    }

    // ------------------------------------------------------------------ warnings

    @Test void walkingTowardADropOrHazardWarnsOncePerCooldown() {
        Body b = new Body(true);
        for (int y = 58; y <= 63; y++) blocks.put("3," + y + ",0", Kind.OPEN);
        service.tick();
        b.at = new Location(world, 1.5, 64, 0.5, -90, 0);
        service.tick();
        verify(b.p).sendActionBar("§cThe ground falls away ahead.");
        verify(b.p).playSound(any(Location.class), eq("minecraft:block.note_block.bass"), eq(1f), eq(0.6f));
        b.at = new Location(world, 1.6, 64, 0.5, -90, 0);
        service.tick();
        verify(b.p, times(1)).sendActionBar(anyString());
        now.addAndGet(3000);
        blocks.clear(); blocks.put("3,64,0", Kind.HAZARD);
        b.at = new Location(world, 1.7, 64, 0.5, -90, 0);
        service.tick();
        verify(b.p).sendActionBar("§6Something dangerous is just ahead.");
    }

    @Test void warningsLookWhereThePlayerWalksNotWhereTheyFace() {
        Body b = new Body(true);
        blocks.put("0,64,3", Kind.HAZARD);                       // to the south
        service.tick();
        b.at = new Location(world, 0.5, 64, 1.5, -90, 0);        // facing east, walking south
        service.tick();
        verify(b.p).sendActionBar("\u00a76Something dangerous is just ahead.");
    }

    @Test void noWarningStandingStillWalkingAwayOnSafeGroundOrWithWarningsOff() {
        Body b = new Body(true);
        blocks.put("2,64,0", Kind.HAZARD);
        service.tick(); service.tick();                          // standing still
        b.at = new Location(world, 0.5, 64, 0.6, -90, 0);        // facing the hazard, walking away from it
        service.tick();
        blocks.clear();
        b.at = new Location(world, 0.5, 64, 1.6, -90, 0);        // safe ground
        service.tick();
        blocks.put("2,64,1", Kind.HAZARD); settings.set(with("warnings: {enabled: false}"));
        b.at = new Location(world, 0.5, 64, 2.6, -90, 0);
        service.tick();
        verify(b.p, never()).sendActionBar(anyString());
    }
}
