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
        return new UsageSnapshot(at, new Spend(used, limit, "USD", 19, "normal"));
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(file());
    }

    @Test
    void theFirstReadingCreatesTheFileWithAHeaderAndARow() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0), 60, 412);

        assertEquals(List.of("datetime,used,limit,currency,status,interval,duration_ms", "2026-10-08 14:24:53,186.02,1000.00,USD,start,60,412"), lines());
    }

    @Test
    void laterReadingsAddRowsAndNoSecondHeader() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);

        history.append(reading(AT, 186.02, 1000.0), 60, 412);
        history.append(reading(AT.plusSeconds(60), 186.07, 1000.0), 60, 412);
        history.append(reading(AT.plusSeconds(120), 186.12, 1000.0), 60, 412);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,186.02,1000.00,USD,start,60,412",
                "2026-10-08 14:25:53,186.07,1000.00,USD,,60,412",
                "2026-10-08 14:26:53,186.12,1000.00,USD,,60,412"), lines());
    }

    @Test
    void aFileLeftByAnEarlierRunIsAddedToNotStartedAgain() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0), 60, 412);

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(3600), 190.0, 1000.0), 60, 412);

        assertEquals(3, lines().size());
        assertEquals(1, lines().stream().filter(l -> l.startsWith("datetime")).count(), "one header only");
    }

    @Test
    void anEmptyFileGetsTheHeader() throws IOException {
        Files.createFile(file());

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1.0, 2.0), 60, 412);

        assertEquals("datetime,used,limit,currency,status,interval,duration_ms", lines().get(0));
    }

    @Test
    void theTimeIsALocalDateAndTimeToTheSecondWithNoFraction() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(Instant.parse("2026-10-08T14:24:53.987654Z"), 1.0, 2.0), 60, 412);

        assertEquals("2026-10-08 14:24:53,1.00,2.00,USD,start,60,412", lines().get(1), "cut to the second, not rounded up");
    }

    @Test
    void theTimeIsInTheFormExcelReadsAsADateAndTime() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1.0, 2.0), 60, 412);

        String time = lines().get(1).split(",")[0];
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), time);
        assertFalse(time.contains("T"), "no ISO 'T' between date and time, which Excel leaves as text");
        assertFalse(time.contains("Z"), "no 'Z' or offset");
        assertFalse(time.contains("+"));
    }

    @Test
    void theTimeIsWrittenInTheZoneItWasGiven() throws IOException {
        new UsageHistory(file(), ZoneId.of("Asia/Tokyo")).append(reading(AT, 1.0, 2.0), 60, 412);
        new UsageHistory(file(), ZoneId.of("America/New_York")).append(reading(AT, 1.0, 2.0), 60, 412);

        assertEquals("2026-10-08 23:24:53,1.00,2.00,USD,start,60,412", lines().get(1));
        assertEquals("2026-10-08 10:24:53,1.00,2.00,USD,start,60,412", lines().get(2));
    }

    @Test
    void withoutAZoneTheMachinesOwnIsUsedWhichIsTheWindowsClock() throws IOException {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Vienna"));
            new UsageHistory(file()).append(reading(AT, 1.0, 2.0), 60, 412);
        } finally {
            java.util.TimeZone.setDefault(before);
        }

        assertEquals("2026-10-08 16:24:53,1.00,2.00,USD,start,60,412", lines().get(1), "14:24:53 UTC is 16:24:53 in Vienna in October");
    }

    @Test
    void whenTheClocksGoBackTheSameLocalTimeTurnsUpTwice() throws IOException {
        // Europe/Vienna, 25 October 2026: 02:30 happens at 00:30 UTC and again at 01:30 UTC.
        UsageHistory history = new UsageHistory(file(), ZoneId.of("Europe/Vienna"));
        history.append(reading(Instant.parse("2026-10-25T00:30:00Z"), 1.0, 2.0), 60, 412);
        history.append(reading(Instant.parse("2026-10-25T01:30:00Z"), 3.0, 4.0), 60, 412);

        // Excel has no zone to tell them apart by; that is the price of a local time.
        assertEquals("2026-10-25 02:30:00,1.00,2.00,USD,start,60,412", lines().get(1));
        assertEquals("2026-10-25 02:30:00,3.00,4.00,USD,,60,412", lines().get(2));
    }

    @Test
    void amountsHaveTwoDecimalsAndADotWhateverTheLanguage() throws IOException {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.5, 1000.0), 60, 412);
        } finally {
            Locale.setDefault(before);
        }

        assertEquals("2026-10-08 14:24:53,186.50,1000.00,USD,start,60,412", lines().get(1));
    }

    @Test
    void largeAmountsAreNotGroupedAndNothingIsInScientificNotation() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 1234567.891, 10000000.0), 60, 412);

        assertEquals("2026-10-08 14:24:53,1234567.89,10000000.00,USD,start,60,412", lines().get(1));
    }

    @Test
    void thereIsNoCurrencySignAmongTheAmountsAndNoQuoting() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0), 60, 412);

        String content = Files.readString(file());
        assertFalse(content.contains("$"));
        assertFalse(content.contains("\""));
        assertTrue(content.contains(",USD,start,60,412\n"), "the currency is the fourth column, as its code");
    }

    @Test
    void aMissingAmountIsAnEmptyField() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, null, 1000.0), 60, 412);
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(60), 5.0, null), 60, 412);

        assertEquals("2026-10-08 14:24:53,,1000.00,USD,start,60,412", lines().get(1));
        assertEquals("2026-10-08 14:25:53,5.00,,USD,start,60,412", lines().get(2));
    }

    @Test
    void aZeroIsWrittenAsAZeroNotLeftEmpty() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 0.0, 1000.0), 60, 412);

        assertEquals("2026-10-08 14:24:53,0.00,1000.00,USD,start,60,412", lines().get(1));
    }

    @Test
    void aReadingWithNoUsageHasNoAmountsAndWritesNothing() throws IOException {
        UsageSnapshot noUsage = new UsageSnapshot(AT, null);

        new UsageHistory(file(), ZoneOffset.UTC).append(noUsage, 60, 412);

        assertFalse(Files.exists(file()), "not even the header");
    }

    @Test
    void aReadingThatReportsNothingWritesNothing() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(new UsageSnapshot(AT, null), 60, 412);

        assertFalse(Files.exists(file()));
    }

    @Test
    void aFileThatCannotBeWrittenIsReportedNotSwallowed() {
        UsageHistory history = new UsageHistory(dir.resolve("no-such-dir").resolve("history.csv"));

        assertThrows(IOException.class, () -> history.append(reading(AT, 1.0, 2.0), 60, 412));
    }

    @Test
    void rowsEndWithANewlineSoTheNextOneStartsOnItsOwnLine() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(reading(AT, 1.0, 2.0), 60, 412);
        history.append(reading(AT.plusSeconds(60), 3.0, 4.0), 60, 412);

        String content = Files.readString(file());
        assertEquals("datetime,used,limit,currency,status,interval,duration_ms\n2026-10-08 14:24:53,1.00,2.00,USD,start,60,412\n2026-10-08 14:25:53,3.00,4.00,USD,,60,412\n", content);
    }

    @Test
    void aReadingWithNoCurrencyLeavesItEmpty() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC)
                .append(new UsageSnapshot(AT, new Spend(1.0, 2.0, null, 50, null)), 60, 412);

        assertEquals("2026-10-08 14:24:53,1.00,2.00,,start,60,412", lines().get(1));
    }

    @Test
    void aFileFromBeforeTheCurrencyWasAColumnIsUpgradedInPlaceByTheNextRow() throws IOException {
        Files.write(file(), List.of("datetime,used,limit", "2026-10-08 14:24:53,1.00,2.00", "2026-10-08 14:25:53,3.00,4.00"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(120), 5.0, 6.0), 60, 412);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,1.00,2.00,,,,",
                "2026-10-08 14:25:53,3.00,4.00,,,,",
                "2026-10-08 14:26:53,5.00,6.00,USD,start,60,412"), lines());
        assertFalse(Files.exists(dir.resolve("history.csv.tmp")), "nothing left behind");
    }

    @Test
    void anUpgradedFileEndsEveryLineWithALineFeedOnEveryOperatingSystem() throws IOException {
        Files.writeString(file(), "datetime,used,limit\r\n2026-10-08 14:24:53,1.00,2.00\r\n");

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(120), 5.0, 6.0), 60, 412);

        assertEquals(
                "datetime,used,limit,currency,status,interval,duration_ms\n"
                        + "2026-10-08 14:24:53,1.00,2.00,,,,\n"
                        + "2026-10-08 14:26:53,5.00,6.00,USD,start,60,412\n",
                Files.readString(file()),
                "the rows that were there and the row that was added end alike");
    }

    @Test
    void aFileFromBeforeTheStartupMarkIsUpgradedInPlaceByTheNextRow() throws IOException {
        Files.write(file(), List.of("datetime,used,limit,currency", "2026-10-08 14:24:53,1.00,2.00,EUR"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(60), 3.0, 4.0), 60, 412);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,1.00,2.00,EUR,,,",
                "2026-10-08 14:25:53,3.00,4.00,USD,start,60,412"), lines());
    }

    @Test
    void aFileWithTheStartupColumnIsUpgradedAndAOneBecomesStart() throws IOException {
        Files.write(file(), List.of(
                "datetime,used,limit,currency,startup",
                "2026-10-08 14:24:53,1.00,2.00,EUR,1",
                "2026-10-08 14:25:53,3.00,4.00,EUR,"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(120), 5.0, 6.0), 60, 412);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,1.00,2.00,EUR,start,,",
                "2026-10-08 14:25:53,3.00,4.00,EUR,,,",
                "2026-10-08 14:26:53,5.00,6.00,USD,start,60,412"), lines(), "a new run marks its own first row");
    }

    @Test
    void aFileAlreadyWithTheNewColumnsIsLeftAlone() throws IOException {
        Files.write(file(), List.of(
                "datetime,used,limit,currency,status,interval,duration_ms", "2026-10-08 14:24:53,1.00,2.00,EUR,start,60,300"));

        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT.plusSeconds(60), 3.0, 4.0), 120, 500);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,1.00,2.00,EUR,start,60,300",
                "2026-10-08 14:25:53,3.00,4.00,USD,start,120,500"), lines(), "a new run marks its own first row");
    }

    @Test
    void onlyTheFirstRowOfARunIsMarkedAndAnEmptyReadingDoesNotUseTheMarkUp() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);

        history.append(new UsageSnapshot(AT, null), 60, 412);
        history.append(reading(AT.plusSeconds(60), 1.0, 2.0), 60, 412);
        history.append(reading(AT.plusSeconds(120), 3.0, 4.0), 60, 412);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:25:53,1.00,2.00,USD,start,60,412",
                "2026-10-08 14:26:53,3.00,4.00,USD,,60,412"), lines());
    }

    // ---- failed queries

    @Test
    void aFailedQueryWritesARowWithNoAmountsAndItsTiming() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(reading(AT, 1.0, 2.0), 60, 412);

        history.appendFailure(AT.plusSeconds(60), 120, 5003);

        assertEquals("2026-10-08 14:25:53,,,,failed,120,5003", lines().get(2));
    }

    @Test
    void ifTheFirstRowOfARunFailedItIsStartFailedAndTheNextIsNotMarked() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);

        history.appendFailure(AT, 60, 20);
        history.append(reading(AT.plusSeconds(60), 1.0, 2.0), 60, 412);
        history.appendFailure(AT.plusSeconds(120), 60, 30);

        assertEquals(List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,,,,start-failed,60,20",
                "2026-10-08 14:25:53,1.00,2.00,USD,,60,412",
                "2026-10-08 14:26:53,,,,failed,60,30"), lines());
    }

    @Test
    void theNewestRowWithAmountsIsShownAtStartupWhateverFailedAfterIt() throws IOException {
        Files.write(file(), List.of(
                "datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:24:53,1.00,2.00,USD,start,60,300",
                "2026-10-08 14:25:53,,,,failed,60,5000",
                "2026-10-08 14:26:53,,,,failed,60,5000"));

        UsageSnapshot latest = new UsageHistory(file(), ZoneOffset.UTC).latest().orElseThrow();

        assertEquals(AT, latest.fetchedAt());
        assertEquals(1.0, latest.spend().used());
    }

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

    // ---- the seat-based format, and one format to a file

    private static final String SEAT_HEADER =
            "datetime,five_hour,five_hour_resets,seven_day,seven_day_resets,status,interval,duration_ms";

    private static UsageSnapshot seatReading(Instant at, double fiveHour, double sevenDay) {
        return UsageSnapshot.seatBased(at, new PlanLimits(
                new PlanLimits.Limit(fiveHour, "2026-10-08T18:00:00Z"), new PlanLimits.Limit(sevenDay, "2026-10-10T02:00:00+00:00")));
    }

    /** A history whose clock, and so the name of a file it sets aside, is fixed. */
    private UsageHistory historyAt(String instant) {
        return new UsageHistory(file(), ZoneOffset.UTC, java.time.Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private List<String> filesBeside() throws IOException {
        try (java.util.stream.Stream<Path> all = Files.list(dir)) {
            return all.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSeatBasedReadingWritesTheTwoPercentagesAndWhenEachIsSetBack() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(seatReading(AT, 12.345, 80), 60, 412);

        assertEquals(List.of(SEAT_HEADER, "2026-10-08 14:24:53,12.35,2026-10-08 18:00:00,80.00,2026-10-10 02:00:00,start,60,412"), lines());
    }

    @Test
    void theResetTimesAreLocalLikeTheTimeOfTheReadingAndEmptyWhenThereIsNone() throws IOException {
        UsageSnapshot noResets = UsageSnapshot.seatBased(AT, new PlanLimits(
                new PlanLimits.Limit(1, null), new PlanLimits.Limit(2, "not a time")));

        new UsageHistory(file(), ZoneId.of("Europe/Vienna")).append(seatReading(AT, 12.34, 80), 60, 412);
        new UsageHistory(file(), ZoneId.of("Europe/Vienna")).append(noResets, 60, 412);

        assertEquals("2026-10-08 16:24:53,12.34,2026-10-08 20:00:00,80.00,2026-10-10 04:00:00,start,60,412", lines().get(1));
        assertEquals("2026-10-08 16:24:53,1.00,,2.00,,start,60,412", lines().get(2));
    }

    @Test
    void aFailedRefreshGoesIntoASeatBasedFileInItsShape() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(seatReading(AT, 12.34, 80), 60, 412);

        history.appendFailure(AT.plusSeconds(60), 60, 5003);

        assertEquals("2026-10-08 14:25:53,,,,,failed,60,5003", lines().get(2));
    }

    @Test
    void aFailedRefreshWithNoFileIsWrittenInTheFormatOfTheLastReadingAndElseInTheUsageBasedOne() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).appendFailure(AT, 60, 1);
        assertEquals(List.of("datetime,used,limit,currency,status,interval,duration_ms", "2026-10-08 14:24:53,,,,start-failed,60,1"), lines());

        Files.delete(file());
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(seatReading(AT, 1, 2), 60, 1);
        Files.delete(file());
        history.appendFailure(AT.plusSeconds(60), 60, 1);

        assertEquals(List.of(SEAT_HEADER, "2026-10-08 14:25:53,,,,,failed,60,1"), lines());
    }

    @Test
    void aReadingOfTheOtherFormatSetsTheFileAsideUnderTheDateAndTheTimeAndBeginsANewOne() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0), 60, 412);
        List<String> before = lines();

        historyAt("2026-10-10T14:24:53Z").append(seatReading(AT.plusSeconds(60), 12.34, 80), 60, 412);

        assertEquals(List.of("history.2026.10.10-14.24.53.csv", "history.csv"), filesBeside());
        assertEquals(before, Files.readAllLines(dir.resolve("history.2026.10.10-14.24.53.csv")), "moved whole, nothing changed in it");
        assertEquals(List.of(SEAT_HEADER, "2026-10-08 14:25:53,12.34,2026-10-08 18:00:00,80.00,2026-10-10 02:00:00,start,60,412"), lines());
    }

    @Test
    void itWorksTheOtherWayRoundAndTheNameIsInTheZoneOfTheFile() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(seatReading(AT, 12.34, 80), 60, 412);

        new UsageHistory(file(), ZoneId.of("Europe/Vienna"), java.time.Clock.fixed(Instant.parse("2026-10-10T22:30:00Z"), ZoneOffset.UTC))
                .append(reading(AT.plusSeconds(60), 5.0, 6.0), 60, 412);

        assertEquals(List.of("history.2026.10.11-00.30.00.csv", "history.csv"), filesBeside());
        assertEquals("datetime,used,limit,currency,status,interval,duration_ms", lines().get(0));
    }

    @Test
    void aFileWithAHeaderTheProgramDoesNotKnowIsSetAsideLikeOneOfTheOtherFormat() throws IOException {
        Files.write(file(), List.of("when,what", "yesterday,something"));

        historyAt("2026-10-10T14:24:53Z").append(reading(AT, 5.0, 6.0), 60, 412);

        assertEquals(List.of("history.2026.10.10-14.24.53.csv", "history.csv"), filesBeside());
        assertEquals(List.of("when,what", "yesterday,something"), Files.readAllLines(dir.resolve("history.2026.10.10-14.24.53.csv")));
        assertEquals(2, lines().size(), "a clean file: the header and the row");
    }

    @Test
    void aFileWithAHeaderOfTheOtherFormatAndNoRowsIsReplacedAndNothingIsSetAside() throws IOException {
        Files.writeString(file(), SEAT_HEADER + "\n");

        historyAt("2026-10-10T14:24:53Z").append(reading(AT, 5.0, 6.0), 60, 412);

        assertEquals(List.of("history.csv"), filesBeside());
        assertEquals(List.of("datetime,used,limit,currency,status,interval,duration_ms", "2026-10-08 14:24:53,5.00,6.00,USD,start,60,412"), lines());
    }

    @Test
    void aFileIsNeverSetAsideOntoOneThatIsThereAndTheRowIsNotWritten() throws IOException {
        new UsageHistory(file(), ZoneOffset.UTC).append(reading(AT, 186.02, 1000.0), 60, 412);
        Files.writeString(dir.resolve("history.2026.10.10-14.24.53.csv"), "kept\n");
        List<String> before = lines();

        assertThrows(IOException.class, () -> historyAt("2026-10-10T14:24:53Z").append(seatReading(AT, 1, 2), 60, 412));

        assertEquals("kept\n", Files.readString(dir.resolve("history.2026.10.10-14.24.53.csv")), "not overwritten");
        assertEquals(before, lines(), "and the history is as it was");
    }

    @Test
    void aFailedRefreshAndAReadingWithNothingToReportNeverMoveTheFile() throws IOException {
        UsageHistory history = historyAt("2026-10-10T14:24:53Z");
        history.append(seatReading(AT, 12.34, 80), 60, 412);

        history.appendFailure(AT.plusSeconds(60), 60, 1);
        history.append(new UsageSnapshot(AT.plusSeconds(120), null), 60, 1);

        assertEquals(List.of("history.csv"), filesBeside());
        assertEquals(3, lines().size());
    }

    @Test
    void theSameFormatAddsToTheFileAndSetsNothingAside() throws IOException {
        UsageHistory history = historyAt("2026-10-10T14:24:53Z");

        history.append(seatReading(AT, 12.34, 80), 60, 412);
        history.append(seatReading(AT.plusSeconds(60), 12.9, 80.1), 60, 388);

        assertEquals(List.of("history.csv"), filesBeside());
        assertEquals("2026-10-08 14:25:53,12.90,2026-10-08 18:00:00,80.10,2026-10-10 02:00:00,,60,388", lines().get(2));
    }

    @Test
    void theReadingShownAtStartupIsTakenFromASeatBasedFileInThatFormat() throws IOException {
        UsageHistory history = new UsageHistory(file(), ZoneOffset.UTC);
        history.append(seatReading(AT, 12.34, 80), 60, 412);
        history.appendFailure(AT.plusSeconds(60), 60, 1);

        UsageSnapshot latest = new UsageHistory(file(), ZoneOffset.UTC).latest().orElseThrow();

        assertEquals(UsageFormat.SEAT_BASED, latest.format());
        assertEquals(AT.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), latest.fetchedAt());
        assertEquals(new PlanLimits.Limit(12.34, "2026-10-08T18:00:00Z"), latest.limits().fiveHour());
        assertEquals(new PlanLimits.Limit(80.0, "2026-10-10T02:00:00Z"), latest.limits().sevenDay());
    }
}
