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
}
