package org.example.fake;

import org.example.money.Amount;
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
        assertFalse(snapshot.isEmpty(), "a reading with spend");
    }

    @Test
    void theAwkwardPartsOfARealResponseAreThereToo() throws Exception {
        org.example.json.Json document =
                org.example.json.JsonReader.read(new UsageDocument().next(java.time.Instant.now()));

        // The keys beside spend are there and null, as a real response has them.
        for (String key : List.of("five_hour", "seven_day", "seven_day_oauth_apps", "seven_day_opus", "seven_day_sonnet",
                "juniper_tide", "cedar_ember", "seven_day_breakdown")) {
            assertTrue(document.has(key) && document.get(key).isNull(), key + " is there and null");
        }
        // extra_usage restates the spend.
        assertTrue(document.at("/extra_usage/utilization").isNumber());
        assertEquals(document.at("/spend/used/amount_minor").asLong(), document.at("/extra_usage/used_credits").asLong());
        assertTrue(document.get("limits").isArray());
        assertTrue(document.at("/spend/enabled").asBoolean());
    }

    @Test
    void theFiguresMoveFromOneReadingToTheNext() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);

        UsageSnapshot first = fetch(backend);
        UsageSnapshot second = fetch(backend);

        assertTrue(second.spend().used().compareTo(first.spend().used()) > 0,
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
                .getMessage().contains("no \"spend\" object"));
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

    // ---- the ordinary answer, shaped by the options

    /** A clock a test moves by hand. */
    private static final class HandClock extends java.time.Clock {

        private java.time.Instant now = java.time.Instant.parse("2026-10-11T12:00:00Z");

        void forward(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private final List<FakeBackend> shaped = new ArrayList<>();

    @AfterEach
    void closeShaped() {
        shaped.forEach(FakeBackend::close);
    }

    private FakeBackend shaped(FakeOptions options, java.time.Clock clock, java.util.function.IntUnaryOperator dice) throws IOException {
        FakeBackend backend = FakeBackend.start(Scenario.NORMAL, clock, options, dice);
        shaped.add(backend);
        return backend;
    }

    private static FakeOptions options(FakeOptions.Account account, int increase, long budget, long start, int refuse, int delay) {
        return new FakeOptions(account, increase, budget, start, refuse, delay);
    }

    @Test
    void asItWasTheFakeBackendBeginsAt18602OfAThousandAndGrowsBy137() throws Exception {
        FakeBackend backend = start(Scenario.NORMAL);

        assertEquals(Amount.parse("187.39"), fetch(backend).spend().used(), "186.02 and the full step, with no chance in a test");
        assertEquals(Amount.parse("188.76"), fetch(backend).spend().used());
        assertEquals(Amount.parse("1000.0"), fetch(backend).spend().limit());
        assertEquals(FakeOptions.DEFAULTS, backend.options());
    }

    @Test
    void theAmountBeginsAtTheStartingAmountAndGrowsByWhatIsDrawnUpToTheLargestIncrease() throws Exception {
        List<Integer> asked = new ArrayList<>();
        int[] draws = {0, 25, 50};
        FakeBackend backend = shaped(options(FakeOptions.Account.USAGE, 50, 20_000, 1_000, 0, 0), java.time.Clock.systemUTC(),
                most -> { asked.add(most); return draws[asked.size() - 1]; });

        assertEquals(Amount.parse("10.00"), fetch(backend).spend().used(), "a draw of nothing: it may not grow at all");
        assertEquals(Amount.parse("10.25"), fetch(backend).spend().used());
        UsageSnapshot third = fetch(backend);
        assertEquals(Amount.parse("10.75"), third.spend().used());
        assertEquals(Amount.parse("200.0"), third.spend().limit(), "the budget");
        assertEquals(List.of(50, 50, 50), asked, "each time a number from 0 to the largest increase");
    }

    @Test
    void anIncreaseOfNothingNeverGrowsAndAnAmountPastTheBudgetGoesOn() throws Exception {
        FakeBackend still = shaped(options(FakeOptions.Account.USAGE, 0, 10_000, 500, 0, 0), java.time.Clock.systemUTC(), most -> most);
        assertEquals(Amount.parse("5.0"), fetch(still).spend().used());
        assertEquals(Amount.parse("5.0"), fetch(still).spend().used());

        FakeBackend over = shaped(options(FakeOptions.Account.USAGE, 600, 1_000, 900, 0, 0), java.time.Clock.systemUTC(), most -> most);
        UsageSnapshot reading = fetch(over);
        assertEquals(Amount.parse("15.0"), reading.spend().used(), "past the budget of 10.00, as an account that spent more than its limit");
        assertEquals(150, reading.spend().percent());
    }

    @Test
    void aSeatBasedAccountAnswersWithNoSpendAndTheLimitsOfSuchAnAccount() throws Exception {
        FakeBackend backend = shaped(options(FakeOptions.Account.SEAT, 137, 100_000, 18_602, 0, 0), java.time.Clock.systemUTC(), most -> most);

        UsageSnapshot reading = fetch(backend);

        assertTrue(reading.isEmpty(), "spend is there and not enabled");
        assertTrue(reading.otherLimits(), "with the limits of a session and of a week");
        assertEquals(UsageSnapshot.NO_SPEND_SEAT_BASED, reading.noSpendMessage());
        assertTrue(Scenario.ofOptionName("seat-based").isEmpty(), "the scenario there was for it is gone: the account took its place");
    }

    @Test
    void aRequestThatComesTooSoonAfterTheOneBeforeItIsRefusedUntilTheClientHasWaited() throws Exception {
        HandClock clock = new HandClock();
        FakeBackend backend = shaped(options(FakeOptions.Account.USAGE, 137, 100_000, 18_602, 30, 0), clock, most -> most);

        assertNotNull(fetch(backend), "the first is answered");
        clock.forward(Duration.ofSeconds(29));
        UsageFetchException refused = assertThrows(UsageFetchException.class, () -> fetch(backend));
        assertEquals(429, refused.status());
        assertEquals(Duration.ZERO, refused.retryAfter(), "it names no wait");

        clock.forward(Duration.ofSeconds(1));
        assertEquals(Amount.parse("188.76"), fetch(backend).spend().used(), "30 seconds after the answered one: answered, and the refusal grew nothing");
        clock.forward(Duration.ofSeconds(29));
        assertThrows(UsageFetchException.class, () -> fetch(backend), "counted from the answer, not from the refusal before it");
        clock.forward(Duration.ofSeconds(29));
        assertNotNull(fetch(backend));
    }

    @Test
    void withNothingToRefuseWithinEveryRequestIsAnswered() throws Exception {
        HandClock clock = new HandClock();
        FakeBackend backend = shaped(options(FakeOptions.Account.USAGE, 137, 100_000, 18_602, 0, 0), clock, most -> most);

        for (int i = 0; i < 5; i++) {
            assertNotNull(fetch(backend));
        }
    }

    @Test
    void anAnswerIsHeldBackForTheTimeThatIsDrawnUpToTheLongestRequest() throws Exception {
        List<Integer> asked = new ArrayList<>();
        // Asked twice at an answer: for the wait, and for the cents.
        FakeBackend backend = shaped(options(FakeOptions.Account.USAGE, 137, 100_000, 18_602, 0, 400), java.time.Clock.systemUTC(),
                most -> { asked.add(most); return most == 400 ? 300 : most; });

        long began = System.nanoTime();
        fetch(backend);
        long took = Duration.ofNanos(System.nanoTime() - began).toMillis();

        assertTrue(took >= 290, "held back for the 300 milliseconds drawn: " + took);
        assertEquals(List.of(400, 137), asked);
    }

    @Test
    void otherOptionsTakeEffectWhileItRunsAndTheAmountGoesOnUnlessTheStartingAmountIsAnother() throws Exception {
        FakeBackend backend = shaped(options(FakeOptions.Account.USAGE, 100, 100_000, 1_000, 0, 0), java.time.Clock.systemUTC(), most -> most);
        assertEquals(Amount.parse("11.0"), fetch(backend).spend().used());

        backend.configure(options(FakeOptions.Account.USAGE, 10, 50_000, 1_000, 0, 0));
        UsageSnapshot next = fetch(backend);
        assertEquals(Amount.parse("11.10"), next.spend().used(), "on from where it was, by the new increase");
        assertEquals(Amount.parse("500.0"), next.spend().limit(), "against the new budget");

        backend.configure(options(FakeOptions.Account.USAGE, 10, 50_000, 2_000, 0, 0));
        assertEquals(Amount.parse("20.10"), fetch(backend).spend().used(), "another starting amount: it begins there again");

        backend.configure(options(FakeOptions.Account.SEAT, 10, 50_000, 2_000, 0, 0));
        assertTrue(fetch(backend).isEmpty(), "and another kind of account at once");
    }

    @Test
    void optionsThatCannotBeSayWhy() {
        assertTrue(FakeOptions.DEFAULTS.fault().isEmpty());
        assertTrue(options(FakeOptions.Account.USAGE, 0, 1, 0, 0, 0).fault().isEmpty(), "the least of everything");
        assertTrue(options(FakeOptions.Account.USAGE, 100_000, 100_000_000L, 100_000_000L, 3_600, 60_000).fault().isEmpty(), "and the most");
        assertTrue(options(FakeOptions.Account.USAGE, -1, 100_000, 0, 0, 0).fault().get().startsWith("The largest increase"));
        assertTrue(options(FakeOptions.Account.USAGE, 100_001, 100_000, 0, 0, 0).fault().get().startsWith("The largest increase"));
        assertTrue(options(FakeOptions.Account.USAGE, 1, 0, 0, 0, 0).fault().get().startsWith("The budget"));
        assertTrue(options(FakeOptions.Account.USAGE, 1, 100, 101, 0, 0).fault().get().startsWith("The starting amount"), "more than the budget");
        assertTrue(options(FakeOptions.Account.USAGE, 1, 100, 0, 3_601, 0).fault().get().startsWith("The time within which"));
        assertTrue(options(FakeOptions.Account.USAGE, 1, 100, 0, 0, 60_001).fault().get().startsWith("The longest request"));
    }
}
