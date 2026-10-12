package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SavedMountTest {
    @Test void unmountedPlayerNeedsNoVehicleMutation() throws Exception {
        Player player = mock(Player.class);
        SavedMount.detach(player);
        verify(player, never()).leaveVehicle();
    }

    @Test void detachesOnlyThePlayerLeavingNestedCargoAndOtherPassengersUntouched() throws Exception {
        Player player = mock(Player.class);
        Boat root = mock(Boat.class);
        Donkey cargo = mock(Donkey.class);
        Pig other = mock(Pig.class);
        when(cargo.getVehicle()).thenReturn(root);
        when(root.getPassengers()).thenReturn(List.of(cargo, other));
        when(cargo.getPassengers()).thenReturn(List.of(player));
        when(player.getVehicle()).thenReturn(cargo, null);
        when(player.leaveVehicle()).thenReturn(true);
        clearInvocations(root, cargo, other);
        SavedMount.detach(player);
        verify(player).leaveVehicle();
        verifyNoInteractions(root, cargo, other);
        verify(player, never()).teleport(any(org.bukkit.Location.class));
    }

    @Test void cancelledOrIneffectiveDismountFailsWithoutEjectingOrRemovingTheMount() {
        Player player = mock(Player.class);
        Donkey cargo = mock(Donkey.class);
        when(player.getVehicle()).thenReturn(cargo);
        assertThrows(IOException.class, () -> SavedMount.detach(player));
        when(player.leaveVehicle()).thenReturn(true);
        assertThrows(IOException.class, () -> SavedMount.detach(player));
        verifyNoInteractions(cargo);
    }
}
