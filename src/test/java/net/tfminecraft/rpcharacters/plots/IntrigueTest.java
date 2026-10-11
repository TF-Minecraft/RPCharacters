package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntrigueTest {
    @TempDir Path directory;
    RuntimeTestState state;

    @BeforeEach
    void setup() { state = new RuntimeTestState(Intrigue.class); }

    @AfterEach
    void close() { state.close(); }

    @Test
    void existingSpymasterRollsAreImportedAndKeptAndNewOnesRollOnce() throws Exception {
        Path own = directory.resolve("data/intrigue.json"), factions = directory.resolve("character-aptitudes.json");
        Files.writeString(factions, "{\"c-old\": 71, \"c-wild\": 400, \"c-null\": null}");
        Intrigue.load(own, factions);
        assertEquals(71, Intrigue.known("c-old"));
        assertEquals(100, Intrigue.known("c-wild"));
        assertNull(Intrigue.known("c-null"));
        assertNull(Intrigue.known(null));
        assertTrue(Files.exists(own));
        Player player = mock(Player.class);
        assertEquals(71, Intrigue.of(player, "c-old"));
        try (var characters = mockStatic(PlotCharacters.class); var factionsBridge = mockStatic(PlotFactions.class)) {
            characters.when(() -> PlotCharacters.attributes(player)).thenReturn(Map.of());
            factionsBridge.when(() -> PlotFactions.intrigueRoll(any(), any())).thenReturn(63);
            assertEquals(63, Intrigue.of(player, "c-new"));
            factionsBridge.when(() -> PlotFactions.intrigueRoll(any(), any())).thenReturn(null);
            Intrigue.random = () -> new ScriptedRandom();
            assertEquals(26, Intrigue.of(player, "c-default"), "no attributes at all is well below average");
            assertEquals(63, Intrigue.of(player, "c-new"), "never rerolled");
        }
        Files.writeString(factions, "{\"c-old\": 5}");
        Intrigue.load(own, factions);
        assertEquals(71, Intrigue.known("c-old"), "this plugin's own roll wins");
        assertEquals(63, Intrigue.known("c-new"));
        Intrigue.load(own, null);
        Intrigue.load(own, directory.resolve("missing.json"));
        Files.writeString(own, "");
        assertThrows(java.io.IOException.class, () -> Intrigue.load(own, null));
    }

    @Test
    void theDefaultFormulaWeighsTheAttributes() {
        Map<String, Integer> average = Map.of("intelligence", 6, "wisdom", 6, "charisma", 6, "dexterity", 6, "constitution", 6, "strength", 6);
        assertEquals(50, Intrigue.defaultRoll(average, new ScriptedRandom()), "6 in every attribute is average");
        assertEquals(74, Intrigue.defaultRoll(Map.of("intelligence", 20), new ScriptedRandom()), "capped at 16, others at 0");
    }

    @Test
    void aFailedSaveForgetsTheRollAndSavesFallBackWhenMovesCannotBeAtomic() throws Exception {
        Path own = directory.resolve("intrigue.json");
        Intrigue.load(own, null);
        Player player = mock(Player.class);
        try (var characters = mockStatic(PlotCharacters.class); var factions = mockStatic(PlotFactions.class)) {
            characters.when(() -> PlotCharacters.attributes(player)).thenReturn(Map.of());
            factions.when(() -> PlotFactions.intrigueRoll(any(), any())).thenReturn(42);
            AtomicInteger fallback = new AtomicInteger();
            try (var files = mockStatic(Files.class, call -> {
                if (call.getMethod().getName().equals("move")) {
                    CopyOption[] options = (CopyOption[]) call.getRawArguments()[2];
                    if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                        throw new AtomicMoveNotSupportedException("a", "b", "test");
                    fallback.incrementAndGet();
                }
                return call.callRealMethod();
            })) {
                assertEquals(42, Intrigue.of(player, "c-a"));
            }
            assertEquals(1, fallback.get());
            Files.createDirectories(directory.resolve("blocked"));
            Intrigue.load(directory.resolve("blocked-file/intrigue.json"), null);
            Files.writeString(directory.resolve("blocked-file"), "");
            assertThrows(java.io.IOException.class, () -> Intrigue.of(player, "c-b"));
            assertNull(Intrigue.known("c-b"));
        }
    }
}
