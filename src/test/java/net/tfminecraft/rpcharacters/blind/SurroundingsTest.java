package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.blind.Surroundings.Kind;
import net.tfminecraft.rpcharacters.blind.Surroundings.Marker;
import net.tfminecraft.rpcharacters.blind.Surroundings.Spot;

/** Flat ground: solid up to y 63, open above; the character stands at y 64. */
class SurroundingsTest {
    final Map<String, Kind> blocks = new HashMap<>();
    Surroundings.Probe probe;

    @BeforeEach void ground() {
        probe = (x, y, z) -> blocks.getOrDefault(x + "," + y + "," + z, y <= 63 ? Kind.SOLID : Kind.OPEN);
    }

    void set(int x, int y, int z, Kind kind) { blocks.put(x + "," + y + "," + z, kind); }
    void column(int x, int z, int fromY, int toY, Kind kind) { for (int y = fromY; y <= toY; y++) set(x, y, z, kind); }

    @Test void openGroundAndGentleStepsStayQuiet() {
        assertEquals(List.of(), Surroundings.tap(probe, 0, 64, 0, 4, 60));
        set(2, 63, 0, Kind.OPEN);             // one block down: still floor
        set(-2, 64, 0, Kind.SOLID);           // one block up: still floor
        assertEquals(List.of(), Surroundings.tap(probe, 0, 64, 0, 4, 60));
    }

    @Test void marksWallsDropsDoorsAndHazardsOnlyAtTheEdgeOfFloor() {
        for (int z = -4; z <= 4; z++) for (int x = 2; x <= 4; x++) column(x, z, 64, 65, Kind.SOLID);
        column(-2, 0, 61, 63, Kind.OPEN);     // a hole three deep
        set(0, 64, 1, Kind.DOOR);
        set(0, 63, -1, Kind.HAZARD);          // lava in the floor
        set(-1, 65, 2, Kind.SOLID); set(-1, 63, 2, Kind.SOLID); // low ceiling: neither wall nor drop
        List<Spot> spots = Surroundings.tap(probe, 0, 64, 0, 3, 60);
        assertEquals(new Spot(Marker.HAZARD, 0, 63, -1), spots.get(0));
        assertEquals(new Spot(Marker.DROP, -2, 64, 0), spots.get(1));
        assertEquals(new Spot(Marker.DOOR, 0, 64, 1), spots.get(2));
        assertTrue(spots.subList(3, spots.size()).stream().allMatch(s -> s.marker() == Marker.WALL && s.x() == 2));
        assertTrue(spots.stream().noneMatch(s -> s.x() == 3), "wall cells behind the first row are not marked");
        assertTrue(spots.stream().noneMatch(s -> s.x() == -1 && s.z() == 2), "a low ceiling is not marked");
        assertEquals(List.of(new Spot(Marker.HAZARD, 0, 63, -1)), Surroundings.tap(probe, 0, 64, 0, 3, 1));
        assertEquals(new Spot(Marker.WALL, 2, 64, 0), spots.get(3), "nearest wall first");
    }

    @Test void doorAboveTheFeetAndHazardAtHeadHeightCount() {
        set(1, 65, 0, Kind.DOOR); set(-1, 65, 0, Kind.HAZARD);
        List<Spot> spots = Surroundings.tap(probe, 0, 64, 0, 1, 60);
        assertEquals(List.of(new Spot(Marker.HAZARD, -1, 65, 0), new Spot(Marker.DOOR, 1, 65, 0)), spots);
    }

    @Test void floorAndDepthHelpers() {
        assertEquals(Optional.of(64), Surroundings.floor(probe, 0, 64, 0));
        column(0, 0, 60, 63, Kind.OPEN);
        assertEquals(Optional.empty(), Surroundings.floor(probe, 0, 64, 0));
        assertEquals(2, Surroundings.depth(probe, 0, 64, 0, 2));
        assertEquals(4, Surroundings.depth(probe, 0, 64, 0, 9));
    }

    @Test void aheadFindsHazardsAndDeepDropsButStopsAtWalls() {
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3));
        set(2, 64, 0, Kind.HAZARD);
        assertEquals(Optional.of(Marker.HAZARD), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3));
        blocks.clear(); column(1, 0, 64, 65, Kind.SOLID); set(2, 64, 0, Kind.HAZARD);
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3), "a wall stops the look");
        blocks.clear(); column(2, 0, 58, 63, Kind.OPEN);
        assertEquals(Optional.of(Marker.DROP), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3));
        blocks.clear(); column(2, 0, 62, 63, Kind.OPEN);
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3), "too shallow to warn about");
    }
}
