package org.example;

import org.example.settings.TimeFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
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
    void spansReadNaturally() {
        assertEquals("0 s", Formatting.span(Duration.ZERO));
        assertEquals("59 s", Formatting.span(Duration.ofSeconds(59)));
        assertEquals("1 min", Formatting.span(Duration.ofSeconds(60)));
        assertEquals("1 h", Formatting.span(Duration.ofHours(1)));
        assertEquals("1 h 5 min", Formatting.span(Duration.ofMinutes(65)));
        assertEquals("1 d", Formatting.span(Duration.ofDays(1)));
        assertEquals("1 d 1 h", Formatting.span(Duration.ofHours(25)));
        assertEquals("0 s", Formatting.span(Duration.ofSeconds(-5)));
    }

    @Test
    void theTimeSinceThePreviousReadingIsShortAndRoundedToTheMinute() {
        assertEquals("0 s", Formatting.gap(0));
        assertEquals("45 s", Formatting.gap(45));
        assertEquals("1 m", Formatting.gap(60));
        assertEquals("1 m", Formatting.gap(61));
        assertEquals("2 m", Formatting.gap(90));
        assertEquals("1 h", Formatting.gap(3600));
        assertEquals("1 h 5 m", Formatting.gap(3900));
        assertEquals("1 d 4 h", Formatting.gap(28 * 3600));
        assertEquals("0 s", Formatting.gap(-3));
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
    void percentagesDropATrailingPointZero() {
        assertEquals("80%", Formatting.percent(80));
        assertEquals("12.3%", Formatting.percent(12.34));
        assertEquals("0%", Formatting.percent(0));
        assertEquals("100%", Formatting.percent(99.96));
        assertEquals("150%", Formatting.percent(150), "over 100 is shown as it is");
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
}
