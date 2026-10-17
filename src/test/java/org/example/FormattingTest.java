package org.example;

import org.example.money.Amount;
import org.example.settings.TimeFormat;
import org.junit.jupiter.api.Test;

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
    void aRequestDurationIsMillisecondsAndFromASecondOnSecondsAndThreeDigits() {
        assertEquals("0ms", Formatting.duration(0));
        assertEquals("87ms", Formatting.duration(87));
        assertEquals("999ms", Formatting.duration(999));
        assertEquals("1s000ms", Formatting.duration(1000));
        assertEquals("1s034ms", Formatting.duration(1034));
        assertEquals("12s005ms", Formatting.duration(12005));
        assertEquals("75s250ms", Formatting.duration(75250), "never minutes");
        assertEquals("0ms", Formatting.duration(-5));
    }

    @Test
    void theRowWritesSecondsWithoutTheSpace() {
        assertEquals("42s", Formatting.rowSeconds(42));
        assertEquals("0s", Formatting.rowSeconds(0));
        assertEquals("-3s", Formatting.rowSeconds(-3), "an overdue countdown keeps its sign");
        assertEquals("3600s", Formatting.rowSeconds(3600), "an hour is still seconds");
    }

    @Test
    void amountsArePlainNumbersWithTwoDecimalsAndNoCurrencySignWhateverTheLanguage() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("186.02", Formatting.amount(Amount.parse("186.02")));
            assertEquals("1,000.00", Formatting.amount(Amount.parse("1000.0")));
            assertEquals("0.00", Formatting.amount(Amount.parse("0.0")));
            assertEquals("1,234,567.89", Formatting.amount(Amount.parse("1234567.891")));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void aThirdDecimalOfAnAmountIsRoundedUpwardsAtHalfAsTheHistoryFileRoundsIt() {
        assertEquals("0.13", Formatting.amount(Amount.parse("0.125")), "not 0.12, which rounding to the even digit gives");
        assertEquals("2.68", Formatting.amount(Amount.parse("2.675")), "not 2.67, which the number as it is held gives");
        assertEquals("1.01", Formatting.amount(Amount.parse("1.005")));
        assertEquals("186.02", Formatting.amount(Amount.parse("186.024")));
        assertEquals("1,000.00", Formatting.amount(Amount.parse("999.995")));
    }

    @Test
    void aShortAmountIsWholeBelowAThousandThenThousandsAndMillionsWithOneDecimalAtMost() {
        assertEquals("0", Formatting.shortAmount(Amount.ZERO));
        assertEquals("617", Formatting.shortAmount(Amount.parse("617.28")));
        assertEquals("999", Formatting.shortAmount(Amount.parse("999.49")));
        assertEquals("1k", Formatting.shortAmount(Amount.parse("999.5")), "rounded half up into the thousands");
        assertEquals("1k", Formatting.shortAmount(Amount.parse("999.6")));
        assertEquals("1k", Formatting.shortAmount(Amount.parse("1000")));
        assertEquals("1k", Formatting.shortAmount(Amount.parse("1049.99")), "a decimal .0 is left out");
        assertEquals("1.1k", Formatting.shortAmount(Amount.parse("1050")));
        assertEquals("1.2k", Formatting.shortAmount(Amount.parse("1234.56")));
        assertEquals("1.5k", Formatting.shortAmount(Amount.parse("1500")));
        assertEquals("12.3k", Formatting.shortAmount(Amount.parse("12345")));
        assertEquals("1M", Formatting.shortAmount(Amount.parse("999950")), "rounded half up into the millions");
        assertEquals("1M", Formatting.shortAmount(Amount.parse("1000000")));
        assertEquals("2.5M", Formatting.shortAmount(Amount.parse("2500000")));
        assertEquals("-500", Formatting.shortAmount(Amount.parse("-500")));
    }

    @Test
    void aMissingAmountIsADashNotAZero() {
        assertEquals("—", Formatting.amount(null));
    }

    @Test
    void aChangeInAnAmountCarriesItsSign() {
        assertEquals("+0.05", Formatting.signedAmount(Amount.parse("0.05")));
        assertEquals("-0.05", Formatting.signedAmount(Amount.parse("-0.05")));
        assertEquals("0.00", Formatting.signedAmount(Amount.ZERO));
        assertEquals("+1,234.50", Formatting.signedAmount(Amount.parse("1234.5")));
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
