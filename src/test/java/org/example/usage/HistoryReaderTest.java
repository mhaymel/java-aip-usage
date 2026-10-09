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
}
