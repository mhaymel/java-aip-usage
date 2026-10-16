package org.example.usage;

import org.example.money.Amount;
import org.example.settings.HistoryGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryGroupedTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("history.csv");
        Files.writeString(file, "datetime,used,limit,currency,status,interval,duration_ms,monthly,daily,daily_auto\n" + content);
        return file;
    }

    private static List<String> times(HistoryReader.Table table) {
        return table.rows().stream().map(row -> row.get(0)).toList();
    }

    private static List<String> used(HistoryReader.Table table) {
        return table.deltas().stream().map(delta -> delta.used() == null ? null : delta.used().plain()).toList();
    }

    @Test
    void eachIntervalIsOneRowWithItsLastReadingNewestFirst() throws IOException {
        HistoryReader.Table table = HistoryReader.readGrouped(write("""
                2026-10-16 14:01:00,10.00,100.00,USD,start,60,5,150.00,45.45,auto
                2026-10-16 14:07:00,10.50,100.00,USD,,60,5,150.00,45.45,auto
                2026-10-16 14:14:00,11.00,100.00,USD,,60,5,150.00,45.45,auto
                2026-10-16 14:16:00,11.25,100.00,USD,,60,5,150.00,45.45,auto
                """), 1000, true, HistoryGroup.MIN15);

        assertEquals(List.of("2026-10-16 14:15:00", "2026-10-16 14:00:00"), times(table), "the beginning of each interval");
        assertEquals("11.00", table.rows().get(1).get(1), "the cells of the interval's last reading");
        assertEquals("", table.rows().get(1).get(4), "and no status: it begins no run");
        assertEquals("45.45", table.rows().get(1).get(8));
        assertEquals(List.of("0.25", "11.00"), used(table), "the changes summed, the first against nothing before it");
        assertEquals(2, table.total());
        assertEquals(2, table.visible());
    }

    @Test
    void theTimeOfAnIntervalRunsFromTheReadingBeforeItsFirstToItsLast() throws IOException {
        HistoryReader.Table table = HistoryReader.readGrouped(write("""
                2026-10-16 13:58:00,10.00,100.00,USD,,60,5,,,
                2026-10-16 14:01:00,10.50,100.00,USD,,60,5,,,
                2026-10-16 14:09:00,11.00,100.00,USD,,60,5,,,
                """), 1000, true, HistoryGroup.MIN15);

        assertEquals(660L, table.deltas().get(0).seconds(), "13:58 to 14:09");
        assertNull(table.deltas().get(1).seconds(), "the very first reading has none before it");
    }

    @Test
    void aNewMonthWithinAWeekIsSummedRight() throws IOException {
        // The amount used begins again at the start of a month: the changes, not the amounts, are added.
        HistoryReader.Table table = HistoryReader.readGrouped(write("""
                2026-09-29 10:00:00,140.00,150.00,USD,,60,5,,,
                2026-09-30 10:00:00,145.00,150.00,USD,,60,5,,,
                2026-10-01 10:00:00,2.00,150.00,USD,,60,5,,,
                2026-10-02 10:00:00,6.00,150.00,USD,,60,5,,,
                """), 1000, true, HistoryGroup.WEEK);

        assertEquals(List.of("2026-09-28 00:00:00"), times(table));
        assertEquals(List.of("6.00"), used(table), "140.00, then +5.00, -143.00, +4.00");
        assertEquals("6.00", table.rows().get(0).get(1), "and the amount of the last reading");
    }

    @Test
    void failedReadingsAreNotGroupedAndAnIntervalWithoutAGoodOneHasNoRow() throws IOException {
        HistoryReader.Table table = HistoryReader.readGrouped(write("""
                2026-10-16 13:00:00,10.00,100.00,USD,,60,5,,,
                2026-10-16 14:00:00,,,,failed,60,5,,,
                2026-10-16 15:00:00,12.00,100.00,USD,,60,5,,,
                """), 1000, true, HistoryGroup.HOUR);

        assertEquals(List.of("2026-10-16 15:00:00", "2026-10-16 13:00:00"), times(table));
        assertEquals(0, table.hiddenFailed());
    }

    @Test
    void anIntervalWithNoChangeIsHiddenWithTheZeroUsageLines() throws IOException {
        Path file = write("""
                2026-10-16 13:00:00,10.00,100.00,USD,,60,5,,,
                2026-10-16 14:00:00,10.00,100.00,USD,,60,5,,,
                2026-10-16 14:30:00,10.00,100.00,USD,,60,5,,,
                2026-10-16 15:00:00,12.00,100.00,USD,,60,5,,,
                """);

        HistoryReader.Table table = HistoryReader.readGrouped(file, 1000, false, HistoryGroup.HOUR);

        assertEquals(List.of("2026-10-16 15:00:00", "2026-10-16 13:00:00"), times(table));
        assertEquals(1, table.hiddenZero());
        assertEquals(3, table.total(), "the intervals are counted");
        assertEquals(2, table.visible());
    }

    @Test
    void theNewestIntervalsAreKeptWithinTheLimit() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int hour = 0; hour < 24; hour++) {
            rows.append(String.format("2026-10-16 %02d:10:00,%d.00,100.00,USD,,60,5,,,%n", hour, hour + 1));
        }
        HistoryReader.Table table = HistoryReader.readGrouped(write(rows.toString()), 5, true, HistoryGroup.HOUR);

        assertEquals(5, table.rows().size());
        assertEquals("2026-10-16 23:00:00", times(table).get(0));
        assertEquals(24, table.visible());
    }

    @Test
    void aMissingFileIsAnEmptyTable() throws IOException {
        HistoryReader.Table table = HistoryReader.readGrouped(dir.resolve("none.csv"), 1000, true, HistoryGroup.DAY);

        assertFalse(table.exists());
        assertEquals(List.of(), table.rows());
    }

    @Test
    void aYearOfReadingsIsGroupedInReasonableTime() throws IOException {
        // A reading a minute for a year, as the history would hold it: the cost of reading the whole file at each request.
        Path file = dir.resolve("year.csv");
        try (var out = Files.newBufferedWriter(file)) {
            out.write("datetime,used,limit,currency,status,interval,duration_ms,monthly,daily,daily_auto\n");
            java.time.LocalDateTime at = java.time.LocalDateTime.of(2025, 10, 16, 0, 0);
            java.time.format.DateTimeFormatter time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < 525_600; i++) {
                out.write(time.format(at.plusMinutes(i)) + "," + Amount.ofCents(i % 100_000).plain() + ",1000.00,USD,,60,120,150.00,45.45,auto\n");
            }
        }
        long began = System.nanoTime();
        HistoryReader.readGrouped(file, 1000, true, HistoryGroup.MIN15);
        long first = (System.nanoTime() - began) / 1_000_000;
        // A refresh adds a reading: only that is read.
        Files.writeString(file, "2026-10-16 00:00:00,1.00,1000.00,USD,,60,120,150.00,45.45,auto\n", java.nio.file.StandardOpenOption.APPEND);
        began = System.nanoTime();
        HistoryReader.Table table = HistoryReader.readGrouped(file, 1000, true, HistoryGroup.MIN15);
        long next = (System.nanoTime() - began) / 1_000_000;
        System.out.println("A year of readings grouped by 15 minutes in " + first + " ms, and again after a reading in " + next
                + " ms (" + Files.size(file) / 1_000_000 + " MB)");

        assertEquals(35_041, table.visible());
        assertTrue(next < 300, "a reading added is not the whole file read again: " + next + " ms");
    }

    @Test
    void linesAddedToTheFileAreGroupedAsTheWholeFileWouldBe() throws IOException {
        Path file = write("""
                2026-10-16 14:01:00,10.00,100.00,USD,start,60,5,,,
                2026-10-16 14:07:00,10.50,100.00,USD,,60,5,,,
                """);
        HistoryReader.readGrouped(file, 1000, true, HistoryGroup.MIN15);

        Files.writeString(file, """
                2026-10-16 14:14:00,11.00,100.00,USD,,60,5,,,
                2026-10-16 14:15:00,,,,failed,60,5,,,
                2026-10-16 14:16:00,11.25,100.00,USD,,60,5,,,
                """, java.nio.file.StandardOpenOption.APPEND);
        HistoryReader.Table added = HistoryReader.readGrouped(file, 1000, true, HistoryGroup.MIN15);

        Path copy = dir.resolve("copy.csv");
        Files.copy(file, copy);
        HistoryReader.Table whole = HistoryReader.readGrouped(copy, 1000, true, HistoryGroup.MIN15);
        assertEquals(whole.rows(), added.rows());
        assertEquals(whole.deltas(), added.deltas());
        assertEquals(List.of("0.25", "11.00"), used(added));
    }

    @Test
    void aFileThatWasReplacedIsGroupedAnew() throws IOException {
        Path file = write("2026-10-16 14:01:00,10.00,100.00,USD,,60,5,,,\n");
        HistoryReader.readGrouped(file, 1000, true, HistoryGroup.HOUR);

        write("2026-10-15 09:01:00,20.00,100.00,USD,,60,5,,,\n2026-10-15 10:01:00,21.00,100.00,USD,,60,5,,,\n");
        HistoryReader.Table table = HistoryReader.readGrouped(file, 1000, true, HistoryGroup.HOUR);

        assertEquals(List.of("2026-10-15 10:00:00", "2026-10-15 09:00:00"), times(table));
    }
}
