package org.example.usage;

import org.example.fake.FakeMonth;
import org.example.money.Amount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The history of the fake backend begun with invented readings of the month (since 0.97). */
class UsageHistorySeedTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("java-aip-usage.fake.csv");
    }

    private static final List<FakeMonth.Reading> TWO = List.of(
            new FakeMonth.Reading(LocalDateTime.of(2026, 10, 1, 8, 0), 1_000),
            new FakeMonth.Reading(LocalDateTime.of(2026, 10, 1, 11, 0), 2_050));

    @Test
    void aNewFileIsBegunWithTheHeaderAndTheReadingsWithNoStatus() throws IOException {
        assertTrue(new UsageHistory(file()).seed(TWO, Amount.parse("1000.00"), "USD"));

        assertEquals(List.of(UsageHistory.HEADER, "2026-10-01 08:00:00,10.00,1000.00,USD,,,,,,", "2026-10-01 11:00:00,20.50,1000.00,USD,,,,,,"),
                Files.readAllLines(file(), StandardCharsets.UTF_8));
    }

    @Test
    void theReadingsGoBeforeTheRowsOfTodayAndAfterThoseOfTheMonthBefore() throws IOException {
        Files.writeString(file(), UsageHistory.HEADER + "\n"
                + "2026-09-30 17:00:00,990.00,1000.00,USD,start,60,400,,,\n"
                + "2026-10-14 09:00:00,186.02,1000.00,USD,start,60,400,,,\n");

        assertTrue(new UsageHistory(file()).seed(TWO, Amount.parse("1000.00"), "USD"));

        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        assertEquals(5, lines.size());
        assertTrue(lines.get(1).startsWith("2026-09-30"));
        assertTrue(lines.get(2).startsWith("2026-10-01 08:00:00"));
        assertTrue(lines.get(3).startsWith("2026-10-01 11:00:00"));
        assertTrue(lines.get(4).startsWith("2026-10-14"));
    }

    @Test
    void aMonthThatHasAReadingBeforeTodayIsLeftAlone() throws IOException {
        String before = UsageHistory.HEADER + "\n2026-10-01 09:30:00,5.00,1000.00,USD,start,60,400,,,\n";
        Files.writeString(file(), before);

        assertFalse(new UsageHistory(file()).seed(TWO, Amount.parse("1000.00"), "USD"));

        assertEquals(before, Files.readString(file()));
    }

    @Test
    void aFailedLineIsNoReading() throws IOException {
        Files.writeString(file(), UsageHistory.HEADER + "\n2026-10-01 09:30:00,,,,start-failed,60,400,,,\n");

        assertTrue(new UsageHistory(file()).seed(TWO, Amount.parse("1000.00"), "USD"));
    }

    @Test
    void theReadingsOfTheFakeMonthAreReadBackAsTheHistory() throws IOException {
        List<FakeMonth.Reading> month = FakeMonth.readings(18_602, LocalDate.of(2026, 10, 14));
        new UsageHistory(file()).seed(month, Amount.parse("1000.00"), "USD");

        HistoryReader.Table table = HistoryReader.read(file(), Integer.MAX_VALUE);

        assertEquals(month.size(), table.rows().size());
        assertEquals("182.29", table.rows().get(0).get(1), "the newest first, a little under 186.02");
    }
}
