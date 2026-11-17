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

    @Test void lavaLakesAreOnlyMarkedAtTheShoreAndHazardsLeaveRoomForTheRest() {
        for (int x = -9; x <= -2; x++) for (int z = -9; z <= 9; z++) set(x, 63, z, Kind.HAZARD);
        for (int z = -4; z <= 4; z++) column(3, z, 64, 65, Kind.SOLID);
        List<Spot> spots = Surroundings.tap(probe, 0, 64, 0, 6, 60);
        assertTrue(spots.stream().filter(s -> s.marker() == Marker.HAZARD).allMatch(s -> s.x() == -2), "only the shore row");
        List<Spot> few = Surroundings.tap(probe, 0, 64, 0, 6, 4);
        assertEquals(2, few.stream().filter(s -> s.marker() == Marker.HAZARD).count());
        assertEquals(2, few.stream().filter(s -> s.marker() == Marker.WALL).count());
        assertEquals(1, Surroundings.tap(probe, 0, 64, 0, 6, 1).size());
    }

    @Test void aheadFollowsStairsDownWithoutCallingThemDrops() {
        for (int step = 1; step <= 4; step++) column(step, 0, 64 - step, 63, Kind.OPEN);
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3));
        set(3, 61, 0, Kind.HAZARD);
        assertEquals(Optional.of(Marker.HAZARD), Surroundings.ahead(probe, 0.5, 64, 0.5, 1, 0, 3, 3), "hazards are found at the stair's level");
    }

    @Test void aheadLooksAtEveryColumnADiagonalCrosses() {
        double d = Math.sqrt(0.5);
        column(1, 1, 58, 63, Kind.OPEN);                         // only crossed between the (1,0) and (2,1) samples
        assertEquals(Optional.of(Marker.DROP), Surroundings.ahead(probe, 0.9, 64, 0.1, d, d, 3, 3));
    }

    @Test void aheadVisitsTheCornerColumnTheLineCrossesFirst() {
        double d = Math.sqrt(0.5);
        column(1, 0, 58, 63, Kind.OPEN);                         // crossed before (1,1)
        assertEquals(Optional.of(Marker.DROP), Surroundings.ahead(probe, 0.95, 64, 0.94, d, d, 3, 3));
        blocks.clear();
        column(1, 0, 64, 65, Kind.SOLID); column(2, 2, 58, 63, Kind.OPEN);
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.95, 64, 0.94, d, d, 3, 3), "a wall in that column stops the look");
    }

    @Test void aheadWalksStraightAlongEitherAxisAndNotAtAllWithoutADirection() {
        column(0, -3, 58, 63, Kind.OPEN);
        assertEquals(Optional.of(Marker.DROP), Surroundings.ahead(probe, 0.5, 64, 0.5, 0, -1, 3, 3));
        column(-3, 0, 58, 63, Kind.OPEN);
        assertEquals(Optional.of(Marker.DROP), Surroundings.ahead(probe, 0.5, 64, 0.5, -1, 0, 3, 3));
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 0, 0, 3, 3));
        assertEquals(Optional.empty(), Surroundings.ahead(probe, 0.5, 64, 0.5, 0, -1, 2, 3), "beyond the look-ahead");
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
