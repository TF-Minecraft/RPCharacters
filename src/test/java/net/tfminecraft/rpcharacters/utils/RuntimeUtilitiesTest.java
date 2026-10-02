package net.tfminecraft.rpcharacters.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.calendar.*;
import net.tfminecraft.rpcharacters.display.TextWrapUtil;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

class RuntimeUtilitiesTest {
    @TempDir Path folder; ServerMock server; PlayerMock player; RuntimeTestState state;
    PlayerData data; RPCharacter character; MockedStatic<PlayerManager> players;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(); Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        Cache.calendarAgeMinimum = 16; Cache.calendarAgeUnsetLabel = "Unset"; Cache.calendarYearOffset = 0;
        player = server.addPlayer(); data = new PlayerData(player); var config = new YamlConfiguration(); config.set("name", "Human"); config.set("age-max", 100);
        character = new RPCharacter(player, UUID.randomUUID().toString(), "Aria", true, Status.ALIVE, new Race("human", config), new ArrayList<>(), null);
        players = mockStatic(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(data);
    }
    @AfterEach void cleanup() { players.close(); state.close(); MockBukkit.unmock(); }

    @Test void textFormattingPreservesDisplayAndGuiColoursAndLoreStructure() {
        assertNull(RPTexts.formatDisplay(null)); assertEquals("", RPTexts.formatDisplay("")); assertEquals("§aHello", RPTexts.formatDisplay("&aHello"));
        assertEquals(RPTexts.formatGui("#abcdefName"), RPTexts.formatGui("&#abcdefName")); assertEquals("Name", ChatColor.stripColor(RPTexts.formatGui("&#abcdefName")));
        RPTexts.sendPrefixed(player, "&aDone"); assertTrue(player.nextMessage().endsWith("§aDone"));
        assertTrue(RPTexts.separator().endsWith("----")); assertTrue(RPTexts.enterTitle("Forest").contains("Forest")); assertTrue(RPTexts.leaveTitle("Forest").contains("Forest"));
        assertEquals(" ", ChatColor.stripColor(RPTexts.spacer())); assertEquals("- Text", ChatColor.stripColor(RPTexts.bullet("Text")));
        assertEquals("- Text", ChatColor.stripColor(RPTexts.bulletFormatted("§aText"))); assertEquals(" (Text)", ChatColor.stripColor(RPTexts.mutedParenthetical("Text")));
        assertEquals("Not selected", ChatColor.stripColor(RPTexts.joinFormatted(null, ", "))); assertEquals("Not selected", ChatColor.stripColor(RPTexts.joinFormatted(List.of(), ", ")));
        assertEquals("A, B", ChatColor.stripColor(RPTexts.joinFormatted(List.of("§aA", "§bB"), ", "))); assertEquals("Text", ChatColor.stripColor(RPTexts.lore("Text"))); assertEquals("Name: Aria", ChatColor.stripColor(RPTexts.labeled("Name: ", "§aAria")));
        Player recipient = mock(Player.class); RPTexts.title(recipient, "&aTitle", "&eSubtitle"); verify(recipient).sendTitle("§aTitle", "§eSubtitle", 10, 60, 20);
        RPTexts.longTitle(recipient, "Long", "Sub"); verify(recipient).sendTitle("Long", "Sub", 10, 400, 20);
    }

    @Test void timeFormattingRoundsAcrossUnitBoundariesWithoutNegativeDurations() {
        assertEquals("0m", TraitStateFormat.formatRemaining(0)); assertEquals("1m", TraitStateFormat.formatRemaining(1));
        assertEquals("1h", TraitStateFormat.formatRemaining(3_599_999)); assertEquals("1d", TraitStateFormat.formatRemaining(86_399_999));
        assertEquals("1d 1h 1m", TraitStateFormat.formatRemaining(90_060_000)); assertEquals("0m", TraitStateFormat.formatHoursRemaining(-1));
        assertEquals("1m", TraitStateFormat.formatHoursRemaining(1)); assertEquals("2h", TraitStateFormat.formatHoursRemaining(3_600_001));
        assertEquals("2/10", TraitStateFormat.formatFuel(1.6, 10.1)); assertEquals("0h", AgeFormatter.formatAge(1)); assertEquals("0s", AgeFormatter.formatCountdown(1)); assertEquals("0s", AgeFormatter.formatCountdown(0)); assertEquals("0s", AgeFormatter.formatCountdown(-1));
    }

    @Test void largestRemainingDurationCannotOverflowTheRoundedHourCount() {
        assertEquals((Long.MAX_VALUE / 3_600_000L + 1) + "h", TraitStateFormat.formatHoursRemaining(Long.MAX_VALUE));
    }

    @Test void lockAndTimestampInputsPreserveUnitsAndFileFallback() throws Exception {
        assertEquals(2_592_000_000L, DurationParser.parseLockTimeMs("1mo")); assertEquals(-1, DurationParser.parseLockTimeMs("mo"));
        assertEquals(-1, DurationParser.parseShortDurationMs("ms")); assertEquals(-1, DurationParser.parseShortDurationMs("x"));
        assertEquals(123, DurationParser.resolveCreatedAtEpochSeconds(true, 123, false, 0, null));
        assertEquals(1_600_000_000, DurationParser.resolveCreatedAtEpochSeconds(false, 0, true, 1_600_000_000, null));
        Path file = Files.writeString(folder.resolve("player.json"), "{}"); Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000L));
        assertEquals(1_500_000_000, DurationParser.resolveCreatedAtEpochSeconds(false, 0, false, 0, file.toFile()));
        long now = Instant.now().getEpochSecond(); assertTrue(DurationParser.resolveCreatedAtEpochSeconds(false, 0, false, 0, null) >= now);
    }

    @Test void birthdaysRespectRaceLimitsAndGeneratedDatesHaveTheRequestedAge() {
        assertNotNull(BirthdayValidator.validateForCharacter(null, "2000-01-01")); assertNotNull(BirthdayValidator.validateForCharacter(character, null)); assertNotNull(BirthdayValidator.validateForCharacter(character, " "));
        LocalDate today = FantasyCalendar.getCurrentDate();
        assertNull(BirthdayValidator.validateForCharacter(character, today.minusYears(25).toString()));
        assertNotNull(BirthdayValidator.validateForCharacter(character, today.minusYears(10).toString()));
        assertNotNull(BirthdayValidator.validateForCharacter(character, today.minusYears(101).toString()));
        character.setRace(null); assertNotNull(BirthdayValidator.validateForCharacter(character, "2000-01-01"));
        assertEquals(0, AgeCalculator.computeAgeYears(null, today)); assertEquals(0, AgeCalculator.computeAgeYears(today.toString(), null)); assertEquals(0, AgeCalculator.computeAgeYears(today.plusYears(1).toString(), today));
        assertEquals("Unset", AgeCalculator.formatAge(" ")); assertNull(AgeCalculator.birthdayFromAge(-1, today));
        assertEquals(25, AgeCalculator.computeAgeYears(AgeCalculator.birthdayFromAge(25, null), today));
        assertEquals(25, AgeCalculator.computeAgeYears(AgeCalculator.birthdayFromAge(25, today, "stable"), today));
        assertEquals("25", AgeCalculator.formatAge(today.minusYears(25).toString()));
    }

    @Test void playtimeGateUsesAccountAgeAndReportsTheRemainingTime() {
        var config = new YamlConfiguration(); config.set("name", "Veteran"); config.set("required-account-playtime", 2);
        Trait gated = new Trait("veteran", config); var free = new Trait("free", new YamlConfiguration());
        assertFalse(PlaytimeGate.canSelectTrait(null, gated)); assertFalse(PlaytimeGate.canSelectTrait(player, null)); assertTrue(PlaytimeGate.canSelectTrait(player, free));
        players.when(() -> PlayerManager.get(player)).thenReturn(null); assertFalse(PlaytimeGate.canSelectTrait(player, gated)); assertTrue(PlaytimeGate.denialMessage(player, gated).contains("2h"));
        players.when(() -> PlayerManager.get(player)).thenReturn(data); data.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond() - 3600);
        assertFalse(PlaytimeGate.canSelectTrait(player, gated)); assertTrue(PlaytimeGate.denialMessage(player, gated).contains("1h"));
        data.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond() - 7201); assertTrue(PlaytimeGate.canSelectTrait(player, gated));
    }

    @Test void clueProgressAndWrappingShowStoredCountsAndRespectEmptyLore() {
        Cache.clueMinLength = 1; Cache.clueMaxLength = 50; Cache.maxClues = 5;
        character.addPlayerClue("A footprint");
        assertTrue(ChatColor.stripColor(ClueProgressFormatter.guiTitle(character)).contains("1 stored, max 5"));
        assertTrue(ChatColor.stripColor(ClueProgressFormatter.progressLine(character)).contains("1 stored"));
        assertTrue(ClueProgressFormatter.lackingCluesMessage(character).contains("enough clues"));
        assertEquals("", TextWrapUtil.stripColor(null)); assertEquals(List.of("§7"), TextWrapUtil.wrapLines(null, 10, null));
        assertEquals(List.of("§a"), TextWrapUtil.wrapLines(" ", 10, "§a"));
        assertEquals(List.of("a", "b"), TextWrapUtil.wrapLines("a b", 0, ""));
    }
}
