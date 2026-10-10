package org.example.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageParserTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-07T15:23:12.302447Z");

    private final UsageParser parser = new UsageParser();

    @Test
    void decodesUsageBasedAccount() throws IOException {
        UsageSnapshot snapshot = parser.parse(fixture("usage-credits.json"), FETCHED_AT);

        assertEquals(FETCHED_AT, snapshot.fetchedAt());
        assertEquals(new Spend(186.02, 1000.0, "USD", 19, "normal"), snapshot.spend());
        // Every plan window key is null and extra_usage holds a utilization: neither makes it the seat-based format.
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void aResponseWithPlanWindowsIsAReadingInTheSeatBasedFormat() throws IOException {
        UsageSnapshot snapshot = parser.parse(fixture("usage-windows.json"), FETCHED_AT);

        assertEquals(UsageFormat.SEAT_BASED, snapshot.format());
        assertNull(snapshot.spend());
        assertEquals(new PlanLimits.Limit(12.34, "2026-10-06T18:00:00Z"), snapshot.limits().fiveHour());
        assertEquals(new PlanLimits.Limit(80.0, "2026-10-10T00:00:00Z"), snapshot.limits().sevenDay());
    }

    @Test
    void theUsageBasedFormatIsToldFromItsNullWindowKeysAndItsExtraUsage() throws IOException {
        assertEquals(UsageFormat.USAGE_BASED, parser.parse(fixture("usage-credits.json"), FETCHED_AT).format());
        assertNull(parser.parse(fixture("usage-empty.json"), FETCHED_AT).format(), "a reading that reports nothing has no format");
    }

    @Test
    void planWindowsWithSpendBesideThemAreSeatBasedAndTheSpendIsIgnored() {
        String body = """
                {"five_hour": {"utilization": 12.5, "resets_at": "2026-10-06T18:00:00Z"},
                 "seven_day": {"utilization": 40, "resets_at": null},
                 "spend": {"enabled": true,
                  "used": {"amount_minor": 18602, "currency": "USD", "exponent": 2},
                  "limit": {"amount_minor": 100000, "currency": "USD", "exponent": 2}}}
                """;

        UsageSnapshot snapshot = parser.parse(body, FETCHED_AT);

        assertEquals(UsageFormat.SEAT_BASED, snapshot.format());
        assertNull(snapshot.spend(), "not shown, not written");
        assertEquals(12.5, snapshot.limits().fiveHour().utilization());
        assertNull(snapshot.limits().sevenDay().resetsAt(), "a missing reset time is no fault");
    }

    @Test
    void aSpendThatCouldNotBeReadDoesNotFailASeatBasedReadingSinceItIsIgnored() {
        String body = """
                {"five_hour": {"utilization": 1}, "seven_day": {"utilization": 2}, "spend": {"enabled": true}}
                """;

        assertEquals(UsageFormat.SEAT_BASED, parser.parse(body, FETCHED_AT).format());
    }

    @Test
    void onlyTheFiveHourAndTheSevenDayWindowAreUsedAndEveryOtherIsPassedOver() {
        String body = """
                {"five_hour": {"utilization": 0, "resets_at": null}, "seven_day": {"utilization": 150.5},
                 "seven_day_opus": {"utilization": 77}, "cedar_ember": {"utilization": 3.5, "resets_at": null}}
                """;

        PlanLimits limits = parser.parse(body, FETCHED_AT).limits();

        assertEquals(new PlanLimits.Limit(0, null), limits.fiveHour(), "exactly zero is a reading");
        assertEquals(new PlanLimits.Limit(150.5, null), limits.sevenDay(), "over 100 is kept as it is");
    }

    @Test
    void aSeatBasedResponseThatLacksOneOfTheTwoLimitsIsRefused() {
        for (String body : List.of(
                "{\"five_hour\": {\"utilization\": 7.5}}",
                "{\"seven_day\": {\"utilization\": 7.5}, \"five_hour\": null}",
                "{\"seven_day_opus\": {\"utilization\": 7.5}}",
                "{\"five_hour\": {\"utilization\": 1}, \"seven_day\": {\"utilization\": \"high\"}}")) {
            UsageParseException refused = assertThrows(UsageParseException.class, () -> parser.parse(body, FETCHED_AT), body);

            assertEquals(
                    "The usage response is in the seat-based format but lacks \"five_hour\" or \"seven_day\";"
                            + " its format may have changed.",
                    refused.getMessage(), body);
        }
    }

    @Test
    void whatIsNoPlanWindowDoesNotMakeItTheSeatBasedFormat() {
        // Null keys, a list, a plain value, an object without a numeric utilization, and extra_usage, which has one.
        String body = """
                {"spend": {"enabled": false}, "five_hour": null, "limits": [], "member_dashboard_available": true,
                 "tangelo": {"utilization": "high"}, "iguana_necktie": {"resets_at": null},
                 "extra_usage": {"utilization": 18.6, "is_enabled": true}}
                """;

        assertTrue(parser.parse(body, FETCHED_AT).isEmpty());
    }

    @Test
    void emptyAccountIsAnAnswerNotAFailure() throws IOException {
        UsageSnapshot snapshot = parser.parse(fixture("usage-empty.json"), FETCHED_AT);

        assertNull(snapshot.spend());
        assertTrue(snapshot.isEmpty());
    }

    @Test
    void scalesMinorUnitsExactly() {
        String body = """
                {"spend": {"enabled": true,
                  "used": {"amount_minor": 1, "currency": "EUR", "exponent": 2},
                  "limit": {"amount_minor": 500, "currency": "EUR", "exponent": 0}}}
                """;

        Spend spend = parser.parse(body, FETCHED_AT).spend();

        assertEquals(0.01, spend.used());
        assertEquals(500.0, spend.limit());
        assertEquals("EUR", spend.currency());
        assertNull(spend.percent());
        assertNull(spend.severity());
    }

    @Test
    void acceptsSpendWithOnlyALimit() {
        String body = """
                {"spend": {"enabled": true, "limit": {"amount_minor": 100, "currency": "USD", "exponent": 2}}}
                """;

        Spend spend = parser.parse(body, FETCHED_AT).spend();

        assertNull(spend.used());
        assertEquals(1.0, spend.limit());
    }

    @Test
    void toleratesUnknownFields() {
        String body = """
                {"spend": {"enabled": false, "brand_new": true}, "something_new": [1, 2, 3], "another": {"deep": {"er": 1}}}
                """;

        assertTrue(parser.parse(body, FETCHED_AT).isEmpty());
    }

    @Test
    void windowsWithoutASpendObjectAreTheSeatBasedFormatAndNotSomeOtherDocument() {
        UsageSnapshot snapshot = parser.parse(
                "{\"five_hour\": {\"utilization\": 7.5}, \"seven_day\": {\"utilization\": 1}}", FETCHED_AT);

        assertEquals(UsageFormat.SEAT_BASED, snapshot.format());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "not json",
            "{\"spend\": ",
            "{} {}",
            "[]",
            "42",
            "\"text\"",
            "null",
            // An object, but not a usage document.
            "{}",
            "{\"type\": \"error\", \"error\": {\"type\": \"authentication_error\"}}",
            // Spend switched on, yet it reports no amounts at all.
            "{\"spend\": {\"enabled\": true}}",
            "{\"spend\": {\"enabled\": true, \"used\": {\"currency\": \"USD\"}}}",
    })
    void rejectsDocumentsThatAreNotUsageData(String body) {
        UsageParseException e =
                assertThrows(UsageParseException.class, () -> parser.parse(body, FETCHED_AT));

        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().startsWith("The usage response"), e.getMessage());
    }

    @Test
    void errorMessagesNeverQuoteTheBody() {
        String body = "{\"secret\": \"sk-ant-do-not-log\" oops";

        UsageParseException e =
                assertThrows(UsageParseException.class, () -> parser.parse(body, FETCHED_AT));

        assertFalse(e.getMessage().contains("sk-ant-do-not-log"), e.getMessage());
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = UsageParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
