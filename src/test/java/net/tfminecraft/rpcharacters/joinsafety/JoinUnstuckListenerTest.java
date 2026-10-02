package net.tfminecraft.rpcharacters.joinsafety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;

class JoinUnstuckListenerTest {
    ServerMock server;
    RuntimeTestState state;
    World world;
    Player player;
    PlayerManager manager;
    MockedStatic<RPCharacters> pluginAccess;
    final Map<String, Material> terrain = new HashMap<>();
    int ground = 63;
    boolean teleportSucceeds = true;
    Location destination;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class);
        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.isEnabled()).thenReturn(true);
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("join-test"));
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(128);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(world.getHighestBlockAt(anyInt(), anyInt())).thenAnswer(call ->
                block(call.getArgument(0), ground, call.getArgument(1)));
        player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Traveller");
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getWidth()).thenReturn(0.6);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0.5, 64, 0.5, 90, 20));
        when(player.getEyeLocation()).thenAnswer(call -> player.getLocation().add(0, 1.62, 0));
        when(player.teleport(any(Location.class))).thenAnswer(call -> {
            destination = ((Location) call.getArgument(0)).clone();
            return teleportSucceeds;
        });
        manager = mock(PlayerManager.class);
        pluginAccess = mockStatic(RPCharacters.class, CALLS_REAL_METHODS);
        pluginAccess.when(RPCharacters::getPlayerManager).thenReturn(manager);
        put(0, 64, 0, Material.STONE);
        put(0, 65, 0, Material.STONE);
    }

    @AfterEach void cleanup() {
        server.getScheduler().cancelTasks(RPCharacters.plugin);
        pluginAccess.close();
        state.close();
        MockBukkit.unmock();
    }

    void put(int x, int y, int z, Material material) { terrain.put(x + ":" + y + ":" + z, material); }

    Block block(int x, int y, int z) {
        Material type = terrain.getOrDefault(x + ":" + y + ":" + z, y <= ground ? Material.STONE : Material.AIR);
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        when(block.isSuffocating()).thenReturn(type == Material.STONE);
        when(block.isPassable()).thenReturn(!type.isSolid());
        when(block.isLiquid()).thenReturn(type == Material.WATER || type == Material.LAVA);
        VoxelShape shape = mock(VoxelShape.class);
        when(shape.overlaps(any())).thenAnswer(call -> type.isSolid()
                && new BoundingBox(0, 0, 0, 1, 1, 1).overlaps((BoundingBox) call.getArgument(0)));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }

    void join() {
        new JoinUnstuckListener().onJoin(new PlayerJoinEvent(player, (String) null));
        server.getScheduler().performTicks(1);
    }

    @Test void joinWaitsOneTickThenMovesToNearestSafeFloorAndRecapturesFreeze() {
        new JoinUnstuckListener().onJoin(new PlayerJoinEvent(player, (String) null));
        assertNull(destination);
        server.getScheduler().performTicks(1);
        assertEquals(new Location(world, -0.5, 64, 0.5, 90, 20), destination);
        verify(manager).releaseFreeze(player);
        verify(manager).reevaluateFreeze(player);
        verify(player).sendMessage(contains("moved to a safe spot"));
    }

    @Test void offlineCreativeSpectatorAndPassengersAreLeftAlone() {
        when(player.isOnline()).thenReturn(false); join();
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE); join();
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR); join();
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isInsideVehicle()).thenReturn(true); join();
        assertNull(destination);
        verifyNoInteractions(manager);
    }

    @Test void openEyesAndCrawlSpaceDoNotCauseUnnecessaryTeleports() {
        put(0, 65, 0, Material.GLASS); join();
        assertFalse(JoinUnstuckListener.isInWall(player));
        put(0, 65, 0, Material.STONE);
        put(0, 64, 0, Material.AIR); join();
        assertTrue(JoinUnstuckListener.isInWall(player));
        assertFalse(JoinUnstuckListener.crawlSpaceBlocked(player));
        assertNull(destination);
    }

    @Test void rejectedTeleportDoesNotReleaseTheFreezeOrClaimSuccess() {
        teleportSucceeds = false; join();
        assertNotNull(destination);
        verifyNoInteractions(manager);
        verify(player, never()).sendMessage(anyString());
    }

    @Test void fullyBuriedPlayerCanUseSurfaceBeyondTheNearbySearch() {
        ground = 100; join();
        assertEquals(new Location(world, 0.5, 101, 0.5, 90, 20), destination);
    }

    @Test void unsafeSurfaceAndWorldCeilingDoNotTeleport() {
        ground = 100;
        put(0, 100, 0, Material.MAGMA_BLOCK); join();
        assertNull(destination);
        ground = 127; join();
        assertNull(destination);
        verifyNoInteractions(manager);
        assertNull(SafeSpotSearch.find(0, 0, 0, null));
    }

    @Test void liquidsAndPassableHazardsAreNeverChosen() {
        put(-1, 64, 0, Material.WATER);
        put(0, 64, -1, Material.FIRE);
        put(0, 64, 1, Material.SWEET_BERRY_BUSH);
        join();
        assertEquals(new Location(world, 1.5, 64, 0.5, 90, 20), destination);
    }
}
