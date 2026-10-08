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
        // Every plan window is null, and extra_usage must not stand in for one.
        assertEquals(List.of(), snapshot.windows());
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void decodesPlanAccountKeepingKeysOrderAndNullResets() throws IOException {
        UsageSnapshot snapshot = parser.parse(fixture("usage-windows.json"), FETCHED_AT);

        assertNull(snapshot.spend());
        assertEquals(
                List.of(
                        new UsageWindow("five_hour", 12.34, "2026-10-06T18:00:00Z"),
                        new UsageWindow("seven_day", 80.0, "2026-10-10T00:00:00Z"),
                        // Exactly zero is a reading, not an absent window.
                        new UsageWindow("seven_day_opus", 0.0, "2026-10-10T00:00:00Z"),
                        new UsageWindow("cedar_ember", 3.5, null)),
                snapshot.windows());
    }

    @Test
    void emptyAccountIsAnAnswerNotAFailure() throws IOException {
        UsageSnapshot snapshot = parser.parse(fixture("usage-empty.json"), FETCHED_AT);

        assertNull(snapshot.spend());
        assertEquals(List.of(), snapshot.windows());
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
                {"spend": {"enabled": false}, "something_new": [1, 2, 3],
                 "five_hour": {"utilization": 1, "resets_at": null, "brand_new": true}}
                """;

        UsageSnapshot snapshot = parser.parse(body, FETCHED_AT);

        assertEquals(List.of(new UsageWindow("five_hour", 1.0, null)), snapshot.windows());
    }

    @Test
    void acceptsWindowsWithoutASpendObject() {
        UsageSnapshot snapshot =
                parser.parse("{\"five_hour\": {\"utilization\": 7.5}}", FETCHED_AT);

        assertNull(snapshot.spend());
        assertEquals(List.of(new UsageWindow("five_hour", 7.5, null)), snapshot.windows());
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
