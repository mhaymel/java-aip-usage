package org.example.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageHistoryTest {

    private static final Instant AT = Instant.parse("2026-10-08T14:24:53Z");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("history.csv");
    }

    private static UsageSnapshot reading(Instant at, Double used, Double limit) {
        return new UsageSnapshot(at, new Spend(used, limit, "USD", 19, "normal"), List.of());
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(file());
    }

    @Test
    void theFirstReadingCreatesTheFileWithAHeaderAndARow() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0));

        assertEquals(List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,186.02,1000.00,USD"), lines());
    }

    @Test
    void laterReadingsAddRowsAndNoSecondHeader() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);

        history.append(reading(AT, 186.02, 1000.0));
        history.append(reading(AT.plusSeconds(60), 186.07, 1000.0));
        history.append(reading(AT.plusSeconds(120), 186.12, 1000.0));

        assertEquals(List.of(
                "datetime,used,limit,currency",
                "2026-10-08 14:24:53,186.02,1000.00,USD",
                "2026-10-08 14:25:53,186.07,1000.00,USD",
                "2026-10-08 14:26:53,186.12,1000.00,USD"), lines());
    }

    @Test
    void aFileLeftByAnEarlierRunIsAddedToNotStartedAgain() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(3600), 190.0, 1000.0));

        assertEquals(3, lines().size());
        assertEquals(1, lines().stream().filter(l -> l.startsWith("datetime")).count(), "one header only");
    }

    @Test
    void anEmptyFileGetsTheHeader() throws IOException {
        Files.createFile(file());

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1.0, 2.0));

        assertEquals("datetime,used,limit,currency", lines().get(0));
    }

    @Test
    void theTimeIsALocalDateAndTimeToTheSecondWithNoFraction() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(Instant.parse("2026-10-08T14:24:53.987654Z"), 1.0, 2.0));

        assertEquals("2026-10-08 14:24:53,1.00,2.00,USD", lines().get(1), "cut to the second, not rounded up");
    }

    @Test
    void theTimeIsInTheFormExcelReadsAsADateAndTime() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1.0, 2.0));

        String time = lines().get(1).split(",")[0];
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), time);
        assertFalse(time.contains("T"), "no ISO 'T' between date and time, which Excel leaves as text");
        assertFalse(time.contains("Z"), "no 'Z' or offset");
        assertFalse(time.contains("+"));
    }

    @Test
    void theTimeIsWrittenInTheZoneItWasGiven() throws IOException {
        new UsageHistory(file(), ZoneId.of("Asia/Tokyo")).append(reading(AT, 1.0, 2.0));
        new UsageHistory(file(), ZoneId.of("America/New_York")).append(reading(AT, 1.0, 2.0));

        assertEquals("2026-10-08 23:24:53,1.00,2.00,USD", lines().get(1));
        assertEquals("2026-10-08 10:24:53,1.00,2.00,USD", lines().get(2));
    }

    @Test
    void withoutAZoneTheMachinesOwnIsUsedWhichIsTheWindowsClock() throws IOException {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Vienna"));
            new UsageHistory(file()).append(reading(AT, 1.0, 2.0));
        } finally {
            java.util.TimeZone.setDefault(before);
        }

        assertEquals("2026-10-08 16:24:53,1.00,2.00,USD", lines().get(1), "14:24:53 UTC is 16:24:53 in Vienna in October");
    }

    @Test
    void whenTheClocksGoBackTheSameLocalTimeTurnsUpTwice() throws IOException {
        // Europe/Vienna, 25 October 2026: 02:30 happens at 00:30 UTC and again at 01:30 UTC.
        UsageHistory history = new UsageHistory(file(), ZoneId.of("Europe/Vienna"));
        history.append(reading(Instant.parse("2026-10-25T00:30:00Z"), 1.0, 2.0));
        history.append(reading(Instant.parse("2026-10-25T01:30:00Z"), 3.0, 4.0));

        // Excel has no zone to tell them apart by; that is the price of a local time.
        assertEquals("2026-10-25 02:30:00,1.00,2.00,USD", lines().get(1));
        assertEquals("2026-10-25 02:30:00,3.00,4.00,USD", lines().get(2));
    }

    @Test
    void amountsHaveTwoDecimalsAndADotWhateverTheLanguage() throws IOException {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.5, 1000.0));
        } finally {
            Locale.setDefault(before);
        }

        assertEquals("2026-10-08 14:24:53,186.50,1000.00,USD", lines().get(1));
    }

    @Test
    void largeAmountsAreNotGroupedAndNothingIsInScientificNotation() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1234567.891, 10000000.0));

        assertEquals("2026-10-08 14:24:53,1234567.89,10000000.00,USD", lines().get(1));
    }

    @Test
    void thereIsNoCurrencySignAmongTheAmountsAndNoQuoting() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0));

        String content = Files.readString(file());
        assertFalse(content.contains("$"));
        assertFalse(content.contains("\""));
        assertTrue(content.contains(",USD\n"), "the currency is the last column, as its code");
    }

    @Test
    void aMissingAmountIsAnEmptyField() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, null, 1000.0));
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(60), 5.0, null));

        assertEquals("2026-10-08 14:24:53,,1000.00,USD", lines().get(1));
        assertEquals("2026-10-08 14:25:53,5.00,,USD", lines().get(2));
    }

    @Test
    void aZeroIsWrittenAsAZeroNotLeftEmpty() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 0.0, 1000.0));

        assertEquals("2026-10-08 14:24:53,0.00,1000.00,USD", lines().get(1));
    }

    @Test
    void aPlanAccountsReadingHasNoAmountsAndWritesNothing() throws IOException {
        UsageSnapshot windows = new UsageSnapshot(AT, null, List.of(new UsageWindow("five_hour", 12.0, null)));

        new UsageHistory(file(), ZoneOffset.UTC).append(windows);

        assertFalse(Files.exists(file()), "not even the header");
    }

    @Test
    void aReadingThatReportsNothingWritesNothing() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(new UsageSnapshot(AT, null, List.of()));

        assertFalse(Files.exists(file()));
    }

    @Test
    void aFileThatCannotBeWrittenIsReportedNotSwallowed() {
        UsageHistory history = new UsageHistory(dir.resolve("no-such-dir").resolve("history.csv"));

        assertThrows(IOException.class, () -> history.append(reading(AT, 1.0, 2.0)));
    }

    @Test
    void rowsEndWithANewlineSoTheNextOneStartsOnItsOwnLine() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(reading(AT, 1.0, 2.0));
        history.append(reading(AT.plusSeconds(60), 3.0, 4.0));

        String content = Files.readString(file());
        assertEquals("datetime,used,limit,currency\n2026-10-08 14:24:53,1.00,2.00,USD\n2026-10-08 14:25:53,3.00,4.00,USD\n", content);
    }

    @Test
    void aReadingWithNoCurrencyLeavesItEmpty() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC)
                .append(new UsageSnapshot(AT, new Spend(1.0, 2.0, null, 50, null), List.of()));

        assertEquals("2026-10-08 14:24:53,1.00,2.00,", lines().get(1));
    }

    @Test
    void aFileFromBeforeTheCurrencyWasAColumnIsUpgradedInPlaceByTheNextRow() throws IOException {
        Files.write(file(), List.of("datetime,used,limit", "2026-10-08 14:24:53,1.00,2.00", "2026-10-08 14:25:53,3.00,4.00"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(120), 5.0, 6.0));

        assertEquals(List.of(
                "datetime,used,limit,currency",
                "2026-10-08 14:24:53,1.00,2.00,",
                "2026-10-08 14:25:53,3.00,4.00,",
                "2026-10-08 14:26:53,5.00,6.00,USD"), lines());
        assertFalse(Files.exists(dir.resolve("history.csv.tmp")), "nothing left behind");
    }

    @Test
    void aFileAlreadyWithTheCurrencyIsLeftAlone() throws IOException {
        Files.write(file(), List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,1.00,2.00,EUR"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(60), 3.0, 4.0));

        assertEquals(List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,1.00,2.00,EUR", "2026-10-08 14:25:53,3.00,4.00,USD"), lines());
    }

    // ---- the newest reading, to show at startup

    @Test
    void theNewestRowComesBackAsAReadingAtItsTime() throws IOException {
        Files.write(file(), List.of("datetime,used,limit,currency",
                "2026-10-08 14:24:53,1.00,2.00,USD", "2026-10-08 16:00:00,263.89,1000.00,EUR", "2026-10-08 15:00:00,5.00,6.00,USD"));

        UsageSnapshot latest = new UsageHistory(file(), ZoneId.of("Europe/Vienna")).latest().orElseThrow();

        assertEquals(Instant.parse("2026-10-08T14:00:00Z"), latest.fetchedAt(), "16:00 in Vienna");
        assertEquals(263.89, latest.spend().used());
        assertEquals(1000.0, latest.spend().limit());
        assertEquals("EUR", latest.spend().currency());
        assertEquals(26, latest.spend().percent());
        assertTrue(latest.windows().isEmpty());
    }

    @Test
    void anOldRowWithNoCurrencyStillComesBack() throws IOException {
        Files.write(file(), List.of("datetime,used,limit", "2026-10-08 14:24:53,1.00,2.00"));

        UsageSnapshot latest = new UsageHistory(file(), ZoneOffset.UTC).latest().orElseThrow();

        assertEquals(null, latest.spend().currency());
        assertEquals(50, latest.spend().percent());
    }

    @Test
    void thereIsNothingToShowWithNoFileNoRowsOrNoAmounts() throws IOException {
        assertTrue(new UsageHistory(file(), ZoneOffset.UTC).latest().isEmpty(), "no file");

        Files.write(file(), List.of("datetime,used,limit,currency"));
        assertTrue(new UsageHistory(file(), ZoneOffset.UTC).latest().isEmpty(), "no rows");

        Files.write(file(), List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,,,USD"));
        assertTrue(new UsageHistory(file(), ZoneOffset.UTC).latest().isEmpty(), "no amounts");
    }

    @Test
    void aRowThatCannotBeUnderstoodIsNothingToShowNotAFailure() throws IOException {
        Files.write(file(), List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,abc,2.00,USD"));
        assertTrue(new UsageHistory(file(), ZoneOffset.UTC).latest().isEmpty());

        Files.write(file(), List.of("datetime,used,limit,currency", "yesterday,1.00,2.00,USD"));
        assertTrue(new UsageHistory(file(), ZoneOffset.UTC).latest().isEmpty());
    }

    @Test
    void aBudgetOfZeroHasNoPercent() throws IOException {
        Files.write(file(), List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,1.00,0.00,USD"));

        assertEquals(null, new UsageHistory(file(), ZoneOffset.UTC).latest().orElseThrow().spend().percent());
    }
}
