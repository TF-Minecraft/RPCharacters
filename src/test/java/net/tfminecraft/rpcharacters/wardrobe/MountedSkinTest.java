package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.mount.MountPairManager;
import com.ticxo.modelengine.api.mount.controller.MountController;
import net.tfminecraft.rpcharacters.identity.MaskService;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.player.PlayerQuitEvent;
import net.kyori.adventure.text.Component;

class MountedSkinTest extends WardrobeFixture {
    private Runnable retry;
    private BukkitTask retryTask;

    @BeforeEach void captureRetry() {
        retryTask = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(5L)))
                .thenAnswer(call -> { retry = call.getArgument(1); return retryTask; });
    }

    @AfterEach void clearRetries() {
        SkinApplyHelper.stopPending();
    }

    private Entity mount() {
        var seat = new org.mockbukkit.mockbukkit.entity.ArmorStandMock(server, java.util.UUID.randomUUID());
        seat.addPassenger(player);
        return seat;
    }

    @Test void skinRefreshMustNotRespawnAVanillaPassenger() {
        Entity seat = mount();
        assertTrue(player.isInsideVehicle());
        assertFalse(SkinApplyHelper.apply(player, "next", "signed"));
        assertEquals("account-value", SkinApplyHelper.readTextures(player).getValue());
        assertNull(WardrobeCache.getLastApplied(player));
        assertEquals(seat, player.getVehicle());
        retry.run();
        assertEquals("account-value", SkinApplyHelper.readTextures(player).getValue());
        verify(retryTask, never()).cancel();
        player.leaveVehicle();
        retry.run();
        assertEquals("next", SkinApplyHelper.readTextures(player).getValue());
        assertEquals("next", WardrobeCache.getLastApplied(player).getValue());
        verify(retryTask).cancel();
    }

    @Test void removingAMaskMustNotRefreshAVirtualModelEnginePassenger() {
        var plugins = mock(PluginManager.class);
        bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(plugins);
        when(plugins.isPluginEnabled("ModelEngine")).thenReturn(true);
        try (var engine = mockStatic(ModelEngineAPI.class)) {
            var mounts = mock(MountPairManager.class);
            engine.when(ModelEngineAPI::getMountPairManager).thenReturn(mounts);
            var slots = new java.util.LinkedHashMap<>(snapshot().getSlots());
            slots.put("masked", slot("masked", true, true, "Masked"));
            cache(new WardrobeSnapshot("alpha", "base", 3, slots));
            masks.when(() -> MaskService.isMasked(player)).thenReturn(true);
            WardrobeService.applyFor(player);
            assertEquals("masked-value", SkinApplyHelper.readTextures(player).getValue());
            when(mounts.getController(player.getUniqueId())).thenReturn(mock(MountController.class));
            assertFalse(player.isInsideVehicle(), "ModelEngine seats are virtual");
            masks.when(() -> MaskService.isMasked(player)).thenReturn(false);
            WardrobeService.applyFor(player);
            assertEquals("masked-value", SkinApplyHelper.readTextures(player).getValue());
            assertEquals("masked-value", WardrobeCache.getLastApplied(player).getValue());
            retry.run();
            assertEquals("masked-value", SkinApplyHelper.readTextures(player).getValue());
            when(mounts.getController(player.getUniqueId())).thenReturn(null);
            retry.run();
            assertEquals("base-value", SkinApplyHelper.readTextures(player).getValue());
            verify(retryTask).cancel();
        }
    }
    @Test void repeatedChangesApplyOnlyTheLatestSkinAfterDismount() {
        mount();
        SkinApplyHelper.apply(player, "first", "signed");
        SkinApplyHelper.apply(player, "latest", "signed");
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(5L));
        player.leaveVehicle();
        retry.run();
        assertEquals("latest", SkinApplyHelper.readTextures(player).getValue());
    }

    @Test void returningToTheCurrentSkinCancelsTheOlderPendingChange() {
        SkinApplyHelper.apply(player, "current", "signed");
        mount();
        SkinApplyHelper.apply(player, "discard", "signed");
        assertTrue(SkinApplyHelper.apply(player, "current", "signed"));
        player.leaveVehicle();
        retry.run();
        assertEquals("current", SkinApplyHelper.readTextures(player).getValue());
        verify(retryTask).cancel();
    }

    @Test void quittingClearsPendingSkinAndStopsTheRetry() {
        mount();
        SkinApplyHelper.apply(player, "discard", "signed");
        new WardrobeListener().onQuit(new PlayerQuitEvent(player, Component.empty()));
        verify(retryTask).cancel();
        player.leaveVehicle();
        retry.run();
        assertEquals("account-value", SkinApplyHelper.readTextures(player).getValue());
    }

    @Test void offlinePlayersAreDiscardedEvenWithoutTheQuitCallback() {
        mount();
        SkinApplyHelper.apply(player, "discard", "signed");
        player.disconnect();
        retry.run();
        verify(retryTask).cancel();
        assertNull(WardrobeCache.getLastApplied(player));
    }

    @Test void deathDiscardsThePendingSkin() {
        mount();
        SkinApplyHelper.apply(player, "discard", "signed");
        player.setHealth(0);
        retry.run();
        verify(retryTask).cancel();
        assertNull(WardrobeCache.getLastApplied(player));
    }

    @Test void shutdownDiscardsPendingSkinWithoutApplyingIt() {
        mount();
        SkinApplyHelper.apply(player, "discard", "signed");
        SkinApplyHelper.stopPending();
        verify(retryTask).cancel();
        player.leaveVehicle();
        retry.run();
        assertEquals("account-value", SkinApplyHelper.readTextures(player).getValue());
    }

    @Test void clearingOnePlayerKeepsOtherPlayersWaiting() {
        Entity seat = mount();
        var other = server.addPlayer();
        seat.addPassenger(other);
        SkinApplyHelper.apply(player, "discard", "signed");
        SkinApplyHelper.apply(other, "other", "signed");
        WardrobeCache.clear(player);
        verify(retryTask, never()).cancel();
        other.leaveVehicle();
        retry.run();
        assertEquals("other", SkinApplyHelper.readTextures(other).getValue());
        assertEquals("account-value", SkinApplyHelper.readTextures(player).getValue());
        verify(retryTask).cancel();
    }

}
