package org.example.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryReaderTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("history.csv");
        Files.writeString(file, content);
        return file;
    }

    private static List<String> times(HistoryReader.Table table) {
        return table.rows().stream().map(row -> row.get(0)).toList();
    }

    @Test
    void aMissingFileIsAnEmptyTableNotAnError() throws IOException {
        HistoryReader.Table table = HistoryReader.read(dir.resolve("none.csv"), 100);

        assertFalse(table.exists());
        assertEquals(0, table.total());
        assertEquals(List.of(), table.rows());
    }

    @Test
    void aFileWithOnlyTheHeaderHasNoRows() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("datetime,used,limit\n"), 100);

        assertTrue(table.exists());
        assertEquals(0, table.total());
        assertEquals(List.of(), table.rows());
    }

    @Test
    void rowsComeNewestFirst() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit
                2026-10-08 14:24:53,186.02,1000.00
                2026-10-08 14:25:53,186.07,1000.00
                2026-10-08 14:26:53,186.12,1000.00
                """), 100);

        assertEquals(List.of("2026-10-08 14:26:53", "2026-10-08 14:25:53", "2026-10-08 14:24:53"), times(table));
    }

    @Test
    void theyAreSortedByTheTimeNotMerelyTurnedRoundSoAFileOutOfOrderIsStillRight() throws IOException {
        // As if the clock had been set back for a moment between two readings.
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit
                2026-10-08 14:25:53,2.00,9.00
                2026-10-08 14:24:53,1.00,9.00
                2026-10-09 08:00:00,4.00,9.00
                2026-10-08 14:26:53,3.00,9.00
                """), 100);

        assertEquals(List.of("2026-10-09 08:00:00", "2026-10-08 14:26:53", "2026-10-08 14:25:53", "2026-10-08 14:24:53"), times(table));
    }

    @Test
    void theSortCrossesDayMonthAndYearBoundaries() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit
                2026-12-31 23:59:59,1.00,2.00
                2027-01-01 00:00:00,1.00,2.00
                2026-02-28 12:00:00,1.00,2.00
                2026-10-01 00:00:00,1.00,2.00
                """), 100);

        assertEquals(List.of("2027-01-01 00:00:00", "2026-12-31 23:59:59", "2026-10-01 00:00:00", "2026-02-28 12:00:00"), times(table));
    }

    @Test
    void theFieldsAreKeptExactlyAsWritten() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit
                2026-10-08 14:24:53,,1000.00
                2026-10-08 14:25:53,5.00,
                """), 100);

        assertEquals(List.of("2026-10-08 14:25:53", "5.00", "", "", "", "", ""), table.rows().get(0), "an old row has no currency, status or timing");
        assertEquals(List.of("2026-10-08 14:24:53", "", "1000.00", "", "", "", ""), table.rows().get(1));
    }

    @Test
    void theLimitKeepsTheNewestRowsAndTheTotalCountsThemAll() throws IOException {
        String rows = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> String.format("2026-10-08 10:%02d:00,%d.00,100.00", i % 60, i))
                .collect(Collectors.joining("\n"));
        HistoryReader.Table table = HistoryReader.read(write("datetime,used,limit\n" + rows + "\n"), 10);

        assertEquals(50, table.total());
        assertEquals(10, table.rows().size());
        assertEquals("2026-10-08 10:50:00", table.rows().get(0).get(0), "the newest of all");
        assertEquals("2026-10-08 10:41:00", table.rows().get(9).get(0), "and the tenth newest");
    }

    @Test
    void aDamagedLineIsSkippedAndTheRestIsKept() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit
                2026-10-08 14:24:53,186.02,1000.00
                this is not a row
                2026-10-08 14:25:53,186.07
                2026-10-08 14:26:53,1,2,3,4,5,6,7

                ,1.00,2.00,USD
                2026-10-08 14:27:53,186.12,1000.00
                """), 100);

        assertEquals(2, table.total());
        assertEquals(List.of("2026-10-08 14:27:53", "2026-10-08 14:24:53"), times(table));
    }

    @Test
    void theHeaderIsNotARow() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("datetime,used,limit\n2026-10-08 14:24:53,1.00,2.00\n"), 100);

        assertEquals(1, table.total());
        assertFalse(times(table).contains("datetime"));
    }

    @Test
    void windowsLineEndingsAreTolerated() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("datetime,used,limit\r\n2026-10-08 14:24:53,1.00,2.00\r\n"), 100);

        assertEquals(List.of("2026-10-08 14:24:53", "1.00", "2.00", "", "", "", ""), table.rows().get(0));
    }

    @Test
    void theColumnsAreThoseOfTheFile() throws IOException {
        assertEquals(List.of("datetime", "used", "limit", "currency"), HistoryReader.read(write("datetime,used,limit,currency\n"), 5).columns());
    }

    @Test
    void theCurrencyIsTheFourthField() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit,currency
                2026-10-08 14:24:53,1.00,2.00,USD
                2026-10-08 14:25:53,3.00,4.00,
                """), 100);

        assertEquals(List.of("2026-10-08 14:25:53", "3.00", "4.00", "", "", "", ""), table.rows().get(0));
        assertEquals(List.of("2026-10-08 14:24:53", "1.00", "2.00", "USD", "", "", ""), table.rows().get(1));
    }

    @Test
    void theStatusIntervalAndDurationAreTheLastThreeFieldsAndEmptyOnesAreAddedWhereTheFileHasNone() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit,currency,status,interval,duration_ms
                2026-10-08 14:24:53,1.00,2.00,USD,start,60,412
                2026-10-08 14:25:53,,,,failed,120,5003
                2026-10-08 14:26:53,5.00,6.00,USD
                2026-10-08 14:27:53,5.00,6.00,USD,1,2,3,extra
                """), 100);

        assertEquals(List.of("2026-10-08 14:26:53", "2026-10-08 14:25:53", "2026-10-08 14:24:53"), times(table), "an eighth field is damage");
        assertEquals(List.of("2026-10-08 14:26:53", "5.00", "6.00", "USD", "", "", ""), table.rows().get(0));
        assertEquals(List.of("2026-10-08 14:25:53", "", "", "", "failed", "120", "5003"), table.rows().get(1));
        assertEquals("start", table.rows().get(2).get(4));
        assertEquals(List.of("datetime", "used", "limit", "currency"), table.columns(), "the status and timing are not columns of the panel");
    }

    @Test
    void theDeltasAreWorkedOutInFileOrderAndComeBackWithTheirRowsNewestFirst() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit,currency,status,interval,duration_ms
                2026-10-08 14:24:53,10.00,1000.00,USD,start,60,400
                2026-10-08 14:25:53,10.50,1000.00,USD,,60,400
                2026-10-08 14:26:53,,,,failed,60,400
                2026-10-08 14:30:53,11.00,1000.00,USD,,60,400
                """), 3);

        assertEquals(List.of("2026-10-08 14:30:53", "2026-10-08 14:26:53", "2026-10-08 14:25:53"), times(table));
        assertEquals(3, table.deltas().size(), "one for each row shown");
        assertEquals(240L, table.deltas().get(0).seconds());
        assertEquals(null, table.deltas().get(0).used(), "the row before it failed");
        assertEquals(60L, table.deltas().get(1).seconds());
        assertEquals(new java.math.BigDecimal("0.50"), table.deltas().get(2).used());
    }

    @Test
    void aFileOutOfOrderIsWorkedOutInTheOrderItWasWritten() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write("""
                datetime,used,limit,currency,status,interval,duration_ms
                2026-10-08 14:26:53,10.00,1000.00,USD,start,60,400
                2026-10-08 14:24:53,10.50,1000.00,USD,,60,400
                """), 10);

        assertEquals(null, table.deltas().get(1).seconds(), "the second was written after, but is earlier");
    }

    // ---- the startup lines are zero usage lines

    private static final String HEADER = "datetime,used,limit,currency,status,interval,duration_ms\n";

    @Test
    void aStartupLineIsAZeroUsageLineAndAFailedStartupLineIsAFailedLine() throws IOException {
        Path file = write(HEADER
                + "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400\n"
                + "2026-10-08 14:01:00,10.10,1000.00,USD,,60,400\n"
                + "2026-10-08 15:00:00,,,,start-failed,60,5000\n"
                + "2026-10-08 15:01:00,10.20,1000.00,USD,,60,400\n");

        HistoryReader.Table all = HistoryReader.read(file, 100);
        HistoryReader.Table noZero = HistoryReader.read(file, 100, new HistoryReader.Filter(false, true));
        HistoryReader.Table noFailed = HistoryReader.read(file, 100, new HistoryReader.Filter(true, false));

        assertEquals(4, all.rows().size());
        assertEquals(0, all.hiddenZero());
        assertEquals(1, noZero.hiddenZero(), "the startup line that read the usage");
        assertEquals(0, noZero.hiddenFailed());
        assertEquals(List.of("2026-10-08 15:01:00", "2026-10-08 15:00:00", "2026-10-08 14:01:00"), times(noZero));
        assertEquals(1, noFailed.hiddenFailed(), "the failed startup line");
        assertEquals(0, noFailed.hiddenZero());
        assertEquals(List.of("2026-10-08 15:01:00", "2026-10-08 14:01:00", "2026-10-08 14:00:00"), times(noFailed));
    }

    @Test
    void whenTheStartupLineIsHiddenTheNextLineShownOfItsRunHasNoChange() throws IOException {
        Path file = write(HEADER
                + "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400\n"
                + "2026-10-08 14:01:00,10.10,1000.00,USD,,60,400\n");

        HistoryReader.Table noZero = HistoryReader.read(file, 100, new HistoryReader.Filter(false, true));

        assertEquals(List.of("2026-10-08 14:01:00"), times(noZero));
        assertEquals(HistoryDeltas.Delta.NONE, noZero.deltas().get(0));
        assertEquals(1, noZero.visible());
    }

    // ---- a file in the seat-based format

    private static final String SEAT_HEADER =
            "datetime,five_hour,five_hour_resets,seven_day,seven_day_resets,status,interval,duration_ms\n";

    @Test
    void theHeaderTellsTheFormatOfTheFile() throws IOException {
        assertEquals(UsageFormat.SEAT_BASED, HistoryReader.read(write(SEAT_HEADER), 10).format());
        assertEquals(UsageFormat.USAGE_BASED,
                HistoryReader.read(write("datetime,used,limit\n2026-10-08 14:00:00,1.00,2.00\n"), 10).format());
        assertEquals(UsageFormat.USAGE_BASED, HistoryReader.read(write("2026-10-08 14:00:00,1.00,2.00\n"), 10).format(), "no header at all");
    }

    @Test
    void theRowsOfASeatBasedFileHaveEightFieldsAndTheirStatusInTheSixth() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write(SEAT_HEADER
                + "2026-10-08 14:00:00,12.34,2026-10-08 18:00:00,80.00,2026-10-10 02:00:00,start,60,412\n"
                + "2026-10-08 14:01:00,,,,,failed,60,5003\n"
                + "2026-10-08 14:02:00,1.00,2.00\n"), 10);

        assertEquals(2, table.total(), "a line that has not the columns is left out");
        assertEquals(5, table.statusIndex());
        assertEquals(List.of("2026-10-08 14:01:00", "", "", "", "", "failed", "60", "5003"), table.rows().get(0));
        assertEquals("start", table.rows().get(1).get(5));
    }

    @Test
    void theChangesOfASeatBasedFileAreOfBothPercentages() throws IOException {
        HistoryReader.Table table = HistoryReader.read(write(SEAT_HEADER
                + "2026-10-08 14:00:00,12.34,2026-10-08 18:00:00,80.00,2026-10-10 02:00:00,start,60,412\n"
                + "2026-10-08 14:01:00,12.94,2026-10-08 18:00:00,80.10,2026-10-10 02:00:00,,60,412\n"
                + "2026-10-08 14:02:00,0.50,2026-10-08 23:00:00,80.10,2026-10-10 02:00:00,,60,412\n"), 10);

        HistoryDeltas.Delta newest = table.deltas().get(0);
        assertEquals(new java.math.BigDecimal("-12.44"), newest.used(), "set back: the change is negative");
        assertEquals(0, newest.other().signum());
        assertEquals(60L, newest.seconds());
        assertEquals(new java.math.BigDecimal("0.60"), table.deltas().get(1).used());
        assertEquals(new java.math.BigDecimal("0.10"), table.deltas().get(1).other());
        assertNull(table.deltas().get(2).used(), "the first line of a run has none");
    }

    @Test
    void aZeroUsageLineOfASeatBasedFileIsOneInWhichNeitherPercentageChanged() throws IOException {
        Path file = write(SEAT_HEADER
                + "2026-10-08 14:00:00,12.34,,80.00,,start,60,412\n"
                + "2026-10-08 14:01:00,12.34,,80.00,,,60,412\n"
                + "2026-10-08 14:02:00,12.34,,80.10,,,60,412\n"
                + "2026-10-08 14:03:00,,,,,failed,60,412\n");

        HistoryReader.Table table = HistoryReader.read(file, 10, new HistoryReader.Filter(false, false));

        assertEquals(List.of("2026-10-08 14:02:00"), times(table), "one percentage changed, so it is no zero usage line");
        assertEquals(2, table.hiddenZero(), "the startup line and the unchanged one");
        assertEquals(1, table.hiddenFailed());
    }
}
