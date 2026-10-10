package org.example.fake;

import org.example.token.PlaceholderTokenProvider;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetchException;
import org.example.usage.UsageParseException;
import org.example.usage.UsageSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fake backend answered by a real {@link UsageClient}, which is the whole point of
 * it: a run against it must go through the same client, the same parser and the same
 * failure handling as a run against Anthropic.
 */
class FakeBackendTest {

    private final List<FakeBackend> started = new ArrayList<>();

    @AfterEach
    void closeAll() {
        started.forEach(FakeBackend::close);
    }

    private FakeBackend start(Scenario scenario) throws IOException {
        FakeBackend backend = FakeBackend.start(scenario);
        started.add(backend);
        return backend;
    }

    /** The client the application would use, pointed at the fake backend. */
    private static UsageSnapshot fetch(FakeBackend backend) {
        return UsageClient.create(UsageClient.usageUri(backend.baseUrl()))
                .fetch(new PlaceholderTokenProvider().acquire());
    }

    private static UsageFetchException fetchFailure(FakeBackend backend) {
        return assertThrows(UsageFetchException.class, () -> fetch(backend));
    }

    // ---- the normal answer

    @Test
    void theNormalAnswerIsAUsageDocumentTheRealParserUnderstands() throws Exception {
        UsageSnapshot snapshot = fetch(start(Scenario.NORMAL));

        assertNotNull(snapshot.spend(), "an enabled spend, as a credit account sends");
        assertEquals("USD", snapshot.spend().currency());
        assertFalse(snapshot.isEmpty(), "the usage-based format: spend, and nothing the parser refuses");
    }

    @Test
    void theAwkwardPartsOfARealUsageBasedResponseAreThereToo() throws Exception {
        com.fasterxml.jackson.databind.JsonNode document =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(new UsageDocument().next(java.time.Instant.now()));

        // The keys of the plan windows are there and null, as a real response of the usage-based format has them.
        for (String key : List.of("five_hour", "seven_day", "seven_day_oauth_apps", "seven_day_opus", "seven_day_sonnet",
                "juniper_tide", "cedar_ember", "seven_day_breakdown")) {
            assertTrue(document.has(key) && document.get(key).isNull(), key + " is there and null");
        }
        // extra_usage restates the spend and has a utilization of its own, and is still no plan window.
        assertTrue(document.at("/extra_usage/utilization").isNumber());
        assertEquals(document.at("/spend/used/amount_minor").asLong(), document.at("/extra_usage/used_credits").asLong());
        assertTrue(document.get("limits").isArray());
        assertTrue(document.at("/spend/enabled").asBoolean());
    }

    @Test
    void theNormalAnswerIsNeverInTheSeatBasedFormatHoweverLongTheRun() {
        UsageDocument document = new UsageDocument();
        org.example.usage.UsageParser parser = new org.example.usage.UsageParser();

        for (int reading = 1; reading <= 1000; reading++) {
            // The parser refuses a document that carries a plan window, so parsing is the check.
            assertNotNull(parser.parse(document.next(java.time.Instant.now()), java.time.Instant.now()).spend(), "reading " + reading);
        }
    }

    @Test
    void theFiguresMoveFromOneReadingToTheNext() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);

        UsageSnapshot first = fetch(backend);
        UsageSnapshot second = fetch(backend);

        assertTrue(second.spend().used() > first.spend().used(),
                first.spend().used() + " then " + second.spend().used());
    }

    // ---- the statuses

    @ParameterizedTest
    @CsvSource({"http-401, 401", "http-403, 403", "http-429, 429", "http-429-retry-after, 429", "http-500, 500"})
    void eachErrorScenarioAnswersItsStatus(String name, int status) throws Exception {
        UsageFetchException e = fetchFailure(start(Scenario.ofOptionName(name).orElseThrow()));

        assertEquals(status, e.status(), e.getMessage());
    }

    @Test
    void onlyTheRetryAfterScenarioSaysHowLongToWait() throws Exception {
        assertEquals(
                Duration.ofSeconds(FakeBackend.RETRY_AFTER_SECONDS),
                fetchFailure(start(Scenario.HTTP_429_RETRY_AFTER)).retryAfter());
        assertEquals(Duration.ZERO, fetchFailure(start(Scenario.HTTP_429)).retryAfter());
    }

    // ---- the bodies that are not usage documents

    @Test
    void aBodyThatIsNotJsonIsAParseFailure() throws Exception {
        assertTrue(assertThrows(UsageParseException.class, () -> fetch(start(Scenario.NOT_JSON)))
                .getMessage().startsWith("The usage response is not valid JSON"));
    }

    @Test
    void anEmptyBodyIsAParseFailure() throws Exception {
        assertThrows(UsageParseException.class, () -> fetch(start(Scenario.EMPTY)));
    }

    @Test
    void aDocumentWithNeitherSpendNorWindowsSaysSo() throws Exception {
        assertTrue(assertThrows(UsageParseException.class, () -> fetch(start(Scenario.NO_SPEND_NO_WINDOWS)))
                .getMessage().contains("neither a \"spend\" object nor any usage windows"));
    }

    @Test
    void textAfterTheDocumentIsRefused() throws Exception {
        assertThrows(UsageParseException.class, () -> fetch(start(Scenario.TRAILING_TEXT)));
    }

    // ---- not answering

    /**
     * Both are checked with a client of its own on a short timeout: the point is that the
     * server does not answer in time, which needs no waiting out of the real 20 seconds.
     */
    @ParameterizedTest
    @EnumSource(names = {"SLOW", "HANG"})
    void anAnswerThatDoesNotComeInTimeTimesOut(Scenario scenario) throws Exception {
        FakeBackend backend = start(scenario);
        HttpRequest request = HttpRequest.newBuilder(UsageClient.usageUri(backend.baseUrl()))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build();

        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThrows(
                    HttpTimeoutException.class,
                    () -> http.send(request, HttpResponse.BodyHandlers.ofString()));
        }
    }

    // ---- what it does not serve

    @ParameterizedTest
    @CsvSource({"/, GET", "/api, GET", "/api/oauth/usage/extra, GET", "/api/oauth/usage, DELETE", "/scenario, GET"})
    void anythingElseIsRefused(String path, String method) throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);

        HttpResponse<String> response = send(backend, path, method, null);

        assertTrue(response.statusCode() == 404 || response.statusCode() == 405,
                method + " " + path + " answered " + response.statusCode());
    }

    // ---- switching while it runs

    @Test
    void theScenarioCanBeSwitchedWhileItRuns() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);
        assertNotNull(fetch(backend).spend(), "normal to begin with");

        HttpResponse<String> switched = send(backend, "/scenario", "POST", "http-429");

        assertEquals(200, switched.statusCode());
        assertEquals(Scenario.HTTP_429, backend.scenario());
        assertEquals(429, fetchFailure(backend).status());
    }

    @Test
    void anUnknownScenarioNameIsRefusedAndChangesNothing() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);

        HttpResponse<String> response = send(backend, "/scenario", "POST", "no-such-scenario");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("normal"), "the message lists the names: " + response.body());
        assertEquals(Scenario.NORMAL, backend.scenario());
    }

    // ---- stopping

    @Test
    void closingItLeavesNothingListening() throws Exception {
        FakeBackend backend = FakeBackend.start(Scenario.NORMAL);
        URI uri = UsageClient.usageUri(backend.baseUrl());
        backend.close();

        UsageFetchException e = assertThrows(
                UsageFetchException.class,
                () -> UsageClient.create(uri).fetch(PlaceholderTokenProvider.TOKEN));

        assertTrue(e.getCause() instanceof ConnectException, "expected a refused connection, got " + e.getCause());
        assertTrue(e.getMessage().startsWith("Cannot reach 127.0.0.1"), e.getMessage());
    }

    @Test
    void itListensOnLoopbackOnly() throws Exception {
        assertEquals("127.0.0.1", start(Scenario.NORMAL).baseUrl().getHost());
    }

    private static HttpResponse<String> send(FakeBackend backend, String path, String method, String body)
            throws Exception {
        HttpRequest.BodyPublisher content = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(backend.baseUrl() + path))
                .method(method, content)
                .timeout(Duration.ofSeconds(10))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // ---- the seat-based format

    @Test
    void theSeatBasedAnswerIsAReadingInThatFormatWhoseFiguresMove() throws Exception {
        FakeBackend backend = FakeBackend.start(Scenario.NORMAL, org.example.usage.UsageFormat.SEAT_BASED);
        started.add(backend);

        UsageSnapshot first = fetch(backend);
        UsageSnapshot second = fetch(backend);

        assertEquals(org.example.usage.UsageFormat.SEAT_BASED, first.format());
        assertNull(first.spend(), "its spend is not enabled");
        assertNotNull(first.limits().fiveHour().resetsAt());
        assertTrue(second.limits().fiveHour().utilization() > first.limits().fiveHour().utilization());
        assertTrue(second.limits().sevenDay().utilization() > first.limits().sevenDay().utilization());
        assertEquals(first.limits().fiveHour().resetsAt(), second.limits().fiveHour().resetsAt(), "the reset time is fixed, not a moving target");
    }

    @Test
    void aLimitOfTheSeatBasedAnswerIsSetBackWhenItsTimeIsReached() throws Exception {
        UsageDocument document = new UsageDocument();
        org.example.usage.UsageParser parser = new org.example.usage.UsageParser();
        java.time.Instant start = java.time.Instant.parse("2026-10-08T12:00:00Z");

        double before = 0;
        for (int i = 0; i < 5; i++) {
            before = parser.parse(document.nextSeatBased(start.plusSeconds(60L * i)), start).limits().fiveHour().utilization();
        }
        org.example.usage.PlanLimits after = parser.parse(document.nextSeatBased(start.plus(java.time.Duration.ofHours(5))), start).limits();

        assertTrue(after.fiveHour().utilization() < before, "set back: " + before + " then " + after.fiveHour().utilization());
        assertEquals("2026-10-08T22:00:00Z", after.fiveHour().resetsAt(), "and the next reset is five hours on");
        assertTrue(after.sevenDay().utilization() > 41.0 + 0.35 * 5, "the weekly limit goes on climbing");
    }

    @Test
    void theFormatCanBeSwitchedWhileTheBackendRuns() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);
        assertEquals(org.example.usage.UsageFormat.USAGE_BASED, fetch(backend).format());

        HttpResponse<String> switched = send(backend, "/format", "POST", " Seat-Based ");

        assertEquals(200, switched.statusCode());
        assertEquals("seat-based", switched.body());
        assertEquals(org.example.usage.UsageFormat.SEAT_BASED, fetch(backend).format());
        assertEquals(400, send(backend, "/format", "POST", "pro").statusCode());
        assertEquals(405, send(backend, "/format", "GET", "").statusCode());
        assertEquals(org.example.usage.UsageFormat.SEAT_BASED, backend.format(), "a refused name changes nothing");
    }
}
