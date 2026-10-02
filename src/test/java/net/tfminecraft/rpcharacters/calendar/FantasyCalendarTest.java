package net.tfminecraft.rpcharacters.calendar;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class FantasyCalendarTest {
    RuntimeTestState state;
    TimeZone timeZone;
    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(); timeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.ROOT);
        Cache.calendarYearOffset = 1675; Cache.calendarEraSuffix = "AE"; Cache.calendarAgeUnsetLabel = "Unknown";
    }
    @AfterEach void restore() { TimeZone.setDefault(timeZone); state.close(); MockBukkit.unmock(); }

    @Test void datesAndEpochFormattingUseTheConfiguredEra() {
        assertEquals(351, FantasyCalendar.toFantasyYear(2026));
        assertEquals(LocalDate.of(351, 10, 2), FantasyCalendar.toFantasyDate(LocalDate.of(2026, 10, 2)));
        assertNull(FantasyCalendar.toFantasyDate(null)); assertNull(FantasyCalendar.toIso(null));
        assertEquals("0351-10-02", FantasyCalendar.toIso(LocalDate.of(351, 10, 2)));
        assertEquals("351 AE", FantasyCalendar.formatFantasyYear(351));
        long instant = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli();
        assertEquals("351 AE", FantasyCalendar.formatFantasyYear(instant)); assertEquals("02/10/351 AE", FantasyCalendar.formatDate(instant));
        assertEquals("02/10/351 AE", FantasyCalendar.formatFantasyDate(LocalDate.of(351, 10, 2)));
        assertEquals("Unknown", FantasyCalendar.formatFantasyDate(null));
        Cache.calendarEraSuffix = ""; assertEquals("351", FantasyCalendar.formatFantasyYear(351));
        Cache.calendarEraSuffix = null; assertEquals("351", FantasyCalendar.formatFantasyYear(351));
        LocalDate before = LocalDate.now(); LocalDate current = FantasyCalendar.getCurrentDate(); LocalDate after = LocalDate.now();
        assertTrue(current.equals(FantasyCalendar.toFantasyDate(before)) || current.equals(FantasyCalendar.toFantasyDate(after)));
        int year = FantasyCalendar.getCurrentFantasyYear(); assertTrue(year == before.getYear() - 1675 || year == after.getYear() - 1675);
    }

    @Test void displayAndIsoBirthdaysRejectInvalidDatesWithoutThrowing() {
        for (String input : List.of("2.10.351", "02/10/0351", " 2 , 10 , 351 ", "0351-10-02"))
            assertEquals("0351-10-02", FantasyCalendar.parseBirthdayInput(input), input);
        for (String input : Arrays.asList(null, " ", "", "nonsense", "31.2.351", "0/1/351", "1/13/351", "day/1/351", "0351-02-31")) {
            assertNull(FantasyCalendar.parseBirthdayInput(input), input);
            assertNull(FantasyCalendar.fromDisplayDate(input), input);
        }
        assertNull(FantasyCalendar.fromIso(null)); assertNull(FantasyCalendar.fromIso(" ")); assertNull(FantasyCalendar.fromIso("broken"));
        assertEquals(LocalDate.of(351, 10, 2), FantasyCalendar.fromIso(" 0351-10-02 "));
        assertEquals("02/10/351 AE", FantasyCalendar.formatBirthday("0351-10-02"));
        for (String input : Arrays.asList(null, " ", "broken")) assertEquals("Unknown", FantasyCalendar.formatBirthday(input));
    }

    @Test void leapDayYearOffsetClampsToTheLastValidDayOfFebruary() {
        assertEquals(LocalDate.of(349, 2, 28), FantasyCalendar.toFantasyDate(LocalDate.of(2024, 2, 29)));
        Cache.calendarYearOffset = 1676;
        assertEquals(LocalDate.of(348, 2, 29), FantasyCalendar.toFantasyDate(LocalDate.of(2024, 2, 29)));
    }
}
