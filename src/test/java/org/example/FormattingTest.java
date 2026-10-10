package org.example;

import org.example.settings.TimeFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormattingTest {

    private static final Instant AT = Instant.parse("2026-10-08T14:24:53Z");

    @Test
    void aTimeIsTheLocalTimeOfDayNeverTheDateAsHoursAndMinutesOrWithSeconds() {
        assertEquals("14:24", Formatting.time(AT, TimeFormat.HOURS_MINUTES, ZoneOffset.UTC));
        assertEquals("14:24:53", Formatting.time(AT, TimeFormat.HOURS_MINUTES_SECONDS, ZoneOffset.UTC));
        assertEquals("09:05", Formatting.time(Instant.parse("2026-10-08T09:05:07Z"), TimeFormat.HOURS_MINUTES, ZoneOffset.UTC), "24-hour, padded");
    }

    @Test
    void aTimeIsConvertedToTheZoneItIsShownIn() {
        assertEquals("16:24:53", Formatting.time(AT, TimeFormat.HOURS_MINUTES_SECONDS, ZoneId.of("Europe/Vienna")));
        assertEquals("23:24", Formatting.time(AT, TimeFormat.HOURS_MINUTES, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void aDateAndTimeReadsDayMonthYearAndTwentyFourHourTimeWhateverTheLanguage() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("8 Oct 2026, 14:24:53", Formatting.dateTime(AT, ZoneOffset.UTC));
            assertEquals("8 Oct 2026, 16:24:53", Formatting.dateTime(AT, ZoneId.of("Europe/Vienna")));
            assertEquals("9 Oct 2026, 03:24:53", Formatting.dateTime(AT, ZoneId.of("Pacific/Auckland")), "the date moves with the zone");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void theTimeSinceThePreviousReadingIsInWholeSecondsNeverMinutes() {
        assertEquals("0 s", Formatting.seconds(0));
        assertEquals("45 s", Formatting.seconds(45));
        assertEquals("63 s", Formatting.seconds(63));
        assertEquals("105 s", Formatting.seconds(105));
        assertEquals("3600 s", Formatting.seconds(3600), "an hour is still seconds");
        assertEquals("100800 s", Formatting.seconds(28 * 3600));
        assertEquals("0 s", Formatting.seconds(-3));
    }

    @Test
    void amountsArePlainNumbersWithTwoDecimalsAndNoCurrencySignWhateverTheLanguage() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("186.02", Formatting.amount(186.02));
            assertEquals("1,000.00", Formatting.amount(1000.0));
            assertEquals("0.00", Formatting.amount(0.0));
            assertEquals("1,234,567.89", Formatting.amount(1234567.891));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void aThirdDecimalOfAnAmountIsRoundedUpwardsAtHalfAsTheHistoryFileRoundsIt() {
        assertEquals("0.13", Formatting.amount(0.125), "not 0.12, which rounding to the even digit gives");
        assertEquals("2.68", Formatting.amount(2.675), "not 2.67, which the number as it is held gives");
        assertEquals("1.01", Formatting.amount(1.005));
        assertEquals("186.02", Formatting.amount(186.024));
        assertEquals("1,000.00", Formatting.amount(999.995));
    }

    @Test
    void aMissingAmountIsADashNotAZero() {
        assertEquals("—", Formatting.amount(null));
    }

    @Test
    void aChangeInAnAmountCarriesItsSign() {
        assertEquals("+0.05", Formatting.signedAmount(new BigDecimal("0.05")));
        assertEquals("-0.05", Formatting.signedAmount(new BigDecimal("-0.05")));
        assertEquals("0.00", Formatting.signedAmount(BigDecimal.ZERO));
        assertEquals("+1,234.50", Formatting.signedAmount(new BigDecimal("1234.5")));
    }

    @Test
    void severityMapsToAFixedSetOfStyleNamesWhateverTheEndpointSends() {
        assertEquals("normal", Formatting.severityKind("normal"));
        assertEquals("warning", Formatting.severityKind("WARNING"));
        assertEquals("warning", Formatting.severityKind("warn"));
        assertEquals("critical", Formatting.severityKind("critical"));
        assertEquals("critical", Formatting.severityKind("exceeded"));
        assertEquals("other", Formatting.severityKind("something new"));
        assertEquals("other", Formatting.severityKind("\"><script>alert(1)</script>"));
        assertEquals("other", Formatting.severityKind("constructor"));
        assertEquals("other", Formatting.severityKind(null));
    }

    @Test
    void theCurrencySymbolIsADollarSignForDollarsAndTheCodeAndASpaceForAnythingElse() {
        assertEquals("$", Formatting.currencySymbol("USD"));
        assertEquals("EUR ", Formatting.currencySymbol("EUR"));
        assertEquals("CHF ", Formatting.currencySymbol("CHF"));
        assertEquals("", Formatting.currencySymbol(null), "no currency named");
        assertEquals("", Formatting.currencySymbol(""));
        assertEquals("", Formatting.currencySymbol("  "));
    }

    @Test
    void theHistorysCurrencyIsADollarSignForDollarsAndTheBareCodeForAnythingElse() {
        assertEquals("$", Formatting.historyCurrency("USD"));
        assertEquals("EUR", Formatting.historyCurrency("EUR"));
        assertEquals("", Formatting.historyCurrency(null));
        assertEquals("", Formatting.historyCurrency(""));
    }

    // ---- the figures of the seat-based format

    @Test
    void aUtilizationHasOneDecimalRoundedUpwardsAtHalfAndNoPointZero() {
        assertEquals("12.3%", Formatting.percent(12.34));
        assertEquals("12.4%", Formatting.percent(12.35));
        assertEquals("80%", Formatting.percent(80));
        assertEquals("0%", Formatting.percent(0));
        assertEquals("100%", Formatting.percent(99.96));
        assertEquals("150%", Formatting.percent(150), "over 100 is shown as it is");
    }

    @Test
    void aSpanOfTimeIsCutAndNeverRounded() {
        assertEquals("0 s", Formatting.span(java.time.Duration.ZERO));
        assertEquals("59 s", Formatting.span(java.time.Duration.ofSeconds(59)));
        assertEquals("1 min", Formatting.span(java.time.Duration.ofSeconds(119)));
        assertEquals("1 h", Formatting.span(java.time.Duration.ofHours(1)));
        assertEquals("1 h 5 min", Formatting.span(java.time.Duration.ofMinutes(65)));
        assertEquals("1 d", Formatting.span(java.time.Duration.ofDays(1)));
        assertEquals("1 d 1 h", Formatting.span(java.time.Duration.ofMinutes(25 * 60 + 59)), "from a day on there are no minutes");
        assertEquals("0 s", Formatting.span(java.time.Duration.ofSeconds(-5)));
    }

    @Test
    void aChangeOfAPercentageHasOneDecimalAndAlwaysItsSign() {
        assertEquals("+0.6", Formatting.signedPoints(new BigDecimal("0.60")));
        assertEquals("-45.0", Formatting.signedPoints(new BigDecimal("-45")));
        assertEquals("+0.1", Formatting.signedPoints(new BigDecimal("0.05")));
        assertEquals("0.0", Formatting.signedPoints(BigDecimal.ZERO));
    }

    @Test
    void aResetTimeOfTheHistoryIsTheTimeOfDayWithTheDayWhenAskedOrWholeWithTheDate() {
        assertEquals("18:00", Formatting.resetCell("2026-10-08 18:00:00", false, false));
        assertEquals("10-10 02:00", Formatting.resetCell("2026-10-10 02:00:00", false, true));
        assertEquals("2026-10-10 02:00:00", Formatting.resetCell("2026-10-10 02:00:00", true, true));
        assertEquals("", Formatting.resetCell("", false, true), "none is none");
        assertEquals("soon", Formatting.resetCell("soon", false, false), "anything else as it is");
    }
}
