package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
import org.example.usage.Spend;
import org.example.usage.UsageFetchException;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application as a whole, minus JavaFX and the network: real settings, real
 * service, real HTTP server, with a stand-in for the usage fetch.
 */
class ApiTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-08T12:00:00Z");

    private static final UsageSnapshot SPEND = new UsageSnapshot(
            FETCHED_AT, new Spend(186.02, 1000.0, "USD", 19, "normal"), List.of());

    private static final UsageSnapshot WINDOWS = new UsageSnapshot(FETCHED_AT, null, List.of(
            new UsageWindow("five_hour", 12.34, "2026-10-06T18:00:00Z"),
            new UsageWindow("cedar_ember", 3.5, null)));

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<AppRuntime> runtimes = new ArrayList<>();

    /** A fetcher that counts its calls, can be held open, and answers with whatever is set. */
    private static final class FakeFetch implements Supplier<UsageSnapshot> {

        final AtomicInteger calls = new AtomicInteger();

        final AtomicInteger inFlight = new AtomicInteger();

        final AtomicInteger maxInFlight = new AtomicInteger();

        volatile Supplier<UsageSnapshot> answer = () -> SPEND;

        volatile CountDownLatch hold;

        volatile CountDownLatch entered = new CountDownLatch(1);

        @Override
        public UsageSnapshot get() {
            calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                CountDownLatch latch = hold;
                if (latch != null) {
                    latch.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UsageFetchException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
            return answer.get();
        }

        CountDownLatch holdNext() {
            hold = new CountDownLatch(1);
            entered = new CountDownLatch(1);
            return hold;
        }
    }

    @AfterEach
    void stop() {
        runtimes.forEach(AppRuntime::close);
    }

    private AppRuntime start(FakeFetch fetch) throws IOException {
        return start(fetch, LaunchOptions.none());
    }

    private AppRuntime start(FakeFetch fetch, LaunchOptions options) throws IOException {
        AppRuntime runtime = AppRuntime.start(dir.resolve("settings.json"), options, fetch);
        runtimes.add(runtime);
        return runtime;
    }

    private static LaunchOptions cli(Integer usage, Integer poll) {
        return new LaunchOptions(
                usage == null ? OptionalInt.empty() : OptionalInt.of(usage),
                poll == null ? OptionalInt.empty() : OptionalInt.of(poll),
                false);
    }

    // ---- /api/config: the effective intervals, requested by the UI at startup

    @Test
    void startupConfigReportsTheEffectiveIntervalsAndTheUsageLimits() throws Exception {
        AppRuntime app = start(new FakeFetch());

        JsonNode config = json(get(app, "/api/config"));

        assertEquals(60, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
        assertEquals(5, config.at("/limits/usageIntervalSeconds/min").asInt());
        assertEquals(3600, config.at("/limits/usageIntervalSeconds/max").asInt());
    }

    @Test
    void theUpdateIntervalIsReportedButHasNoLimitsBecauseItCannotBeEdited() throws Exception {
        JsonNode config = json(get(start(new FakeFetch()), "/api/config"));

        assertTrue(config.get("pollIntervalSeconds").isInt(), "the page needs it to know how often to poll");
        assertTrue(config.at("/limits/pollIntervalSeconds").isMissingNode(), "nothing to validate: " + config);
    }

    @Test
    void startupConfigReflectsTheCommandLineOverrides() throws Exception {
        AppRuntime app = start(new FakeFetch(), cli(10, 4));

        JsonNode config = json(get(app, "/api/config"));

        assertEquals(10, config.get("usageIntervalSeconds").asInt());
        assertEquals(4, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void startupConfigReflectsTheSavedUsageInterval() throws Exception {
        new SettingsStore(dir.resolve("settings.json")).save(new SettingsStore.Saved(120));

        JsonNode config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(120, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void anUpdateIntervalLeftInTheFileByAnEarlierVersionIsNotUsed() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"usageIntervalSeconds\": 120, \"pollIntervalSeconds\": 5}");

        JsonNode config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(120, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void aValidUsageIntervalReachesTheRefreshServiceAndIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/config", "{\"usageIntervalSeconds\": 90}");

        assertEquals(200, response.statusCode());
        assertEquals(90, json(response).get("usageIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(90), app.service().interval());
        assertEquals(90, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds());
        assertEquals(90, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt());
    }

    @Test
    void aFrontendValueReplacesTheCommandLineOverrideForTheRestOfTheRun() throws Exception {
        AppRuntime app = start(new FakeFetch(), cli(10, 4));

        post(app, "/api/config", "{\"usageIntervalSeconds\": 75}");

        JsonNode config = json(get(app, "/api/config"));
        assertEquals(75, config.get("usageIntervalSeconds").asInt());
        assertEquals(4, config.get("pollIntervalSeconds").asInt(), "the update interval is still the command-line one");
        assertEquals(Duration.ofSeconds(75), app.service().interval());
    }

    @Test
    void aChangeSurvivesARestartButACommandLineUpdateIntervalDoesNot() throws Exception {
        AppRuntime first = start(new FakeFetch(), cli(null, 9));
        post(first, "/api/config", "{\"usageIntervalSeconds\": 200}");
        first.close();

        JsonNode config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(200, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void theSavedFileNeverHoldsTheUpdateInterval() throws Exception {
        AppRuntime app = start(new FakeFetch(), cli(null, 9));

        post(app, "/api/config", "{\"usageIntervalSeconds\": 200}");

        assertFalse(Files.readString(dir.resolve("settings.json")).contains("poll"), Files.readString(dir.resolve("settings.json")));
    }

    // ---- the update interval cannot be changed through the API

    @Test
    void anAttemptToChangeTheUpdateIntervalIsRefusedAndSaysWhatToDoInstead() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/config", "{\"pollIntervalSeconds\": 7}");

        assertEquals(400, response.statusCode());
        assertTrue(json(response).get("error").asText().contains("--poll-interval"), response.body());
        assertEquals(1, json(get(app, "/api/config")).get("pollIntervalSeconds").asInt());
        assertFalse(Files.exists(dir.resolve("settings.json")), "nothing was saved");
    }

    @Test
    void aValidUsageIntervalSentTogetherWithAnUpdateIntervalIsRefusedWhole() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/config", "{\"usageIntervalSeconds\": 90, \"pollIntervalSeconds\": 5}");

        assertEquals(400, response.statusCode());
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt(), "the valid half was not applied either");
        assertFalse(Files.exists(dir.resolve("settings.json")));
    }

    @Test
    void anExplicitlyNullUpdateIntervalIsTreatedAsNotGiven() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(200, post(app, "/api/config", "{\"usageIntervalSeconds\": 90, \"pollIntervalSeconds\": null}").statusCode());
    }

    // ---- invalid usage intervals

    @Test
    void invalidUsageIntervalsAreRefusedWithAReasonAndChangeNothing() throws Exception {
        AppRuntime app = start(new FakeFetch());
        String[] bodies = {
                "{\"usageIntervalSeconds\": 4}", "{\"usageIntervalSeconds\": 3601}",
                "{\"usageIntervalSeconds\": 0}", "{\"usageIntervalSeconds\": -60}",
                "{\"usageIntervalSeconds\": 30.5}", "{\"usageIntervalSeconds\": \"30\"}",
                "{\"usageIntervalSeconds\": true}", "{\"usageIntervalSeconds\": [1]}",
                "{\"usageIntervalSeconds\": 99999999999999999999}",
                "{}", "{\"usageIntervalSeconds\": null}", "{\"somethingElse\": 5}"};

        for (String body : bodies) {
            HttpResponse<String> response = post(app, "/api/config", body);

            assertEquals(400, response.statusCode(), body);
            assertFalse(json(response).get("error").asText().isBlank(), body);
        }

        JsonNode config = json(get(app, "/api/config"));
        assertEquals(60, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(60), app.service().interval());
        assertFalse(Files.exists(dir.resolve("settings.json")));
    }

    @Test
    void theRefusalSaysWhatIsAccepted() throws Exception {
        HttpResponse<String> response = post(start(new FakeFetch()), "/api/config", "{\"usageIntervalSeconds\": 4}");

        assertEquals("The usage interval must be a whole number of seconds from 5 to 3600.",
                json(response).get("error").asText());
    }

    @Test
    void theBoundariesAreAccepted() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(200, post(app, "/api/config", "{\"usageIntervalSeconds\": 5}").statusCode());
        assertEquals(200, post(app, "/api/config", "{\"usageIntervalSeconds\": 3600}").statusCode());
    }

    @Test
    void malformedOrOversizedBodiesAreRefused() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(400, post(app, "/api/config", "not json").statusCode());
        assertEquals(400, post(app, "/api/config", "").statusCode());
        assertEquals(400, post(app, "/api/config", "[1, 2]").statusCode());
        assertEquals(400, post(app, "/api/config", "\"text\"").statusCode());
        assertEquals(413, post(app, "/api/config", "{\"x\": \"" + "a".repeat(5000) + "\"}").statusCode());
    }

    @Test
    void whenTheFileCannotBeWrittenTheChangeIsRefusedAndNothingChanges() throws Exception {
        AppRuntime app = AppRuntime.start(
                dir.resolve("no-such-dir").resolve("settings.json"), LaunchOptions.none(), new FakeFetch());
        runtimes.add(app);

        HttpResponse<String> response = post(app, "/api/config", "{\"usageIntervalSeconds\": 90}");

        assertEquals(500, response.statusCode());
        assertTrue(json(response).get("error").asText().startsWith("The setting could not be saved"));
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(60), app.service().interval());
    }

    // ---- /api/status: read-only, and the two response shapes

    @Test
    void statusShowsASpendReading() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);

        JsonNode status = json(get(app, "/api/status"));

        assertFalse(status.get("stale").asBoolean());
        assertTrue(status.get("error").isNull());
        JsonNode usage = status.get("usage");
        assertEquals("anthropic-oauth-usage", usage.get("source").asText());
        assertEquals("2026-10-08T12:00:00Z", usage.get("fetched_at").asText());
        assertEquals(186.02, usage.at("/spend/used").asDouble());
        assertEquals(1000.0, usage.at("/spend/limit").asDouble());
        assertEquals("USD", usage.at("/spend/currency").asText());
        assertEquals(19, usage.at("/spend/percent").asInt());
        assertEquals("normal", usage.at("/spend/severity").asText());
        assertEquals(0, usage.get("windows").size());
    }

    @Test
    void statusShowsAPlanReadingWithWindowsAndANullSpend() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> WINDOWS;
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);

        JsonNode usage = json(get(app, "/api/status")).get("usage");

        assertTrue(usage.get("spend").isNull());
        assertEquals(2, usage.get("windows").size());
        assertEquals("five_hour", usage.at("/windows/0/window").asText());
        assertEquals(12.34, usage.at("/windows/0/utilization").asDouble());
        assertEquals("2026-10-06T18:00:00Z", usage.at("/windows/0/resets_at").asText());
        assertEquals("cedar_ember", usage.at("/windows/1/window").asText());
        assertTrue(usage.at("/windows/1/resets_at").isNull(), "an unknown reset time is null, not missing");
    }

    @Test
    void statusKeepsTheLastReadingAndShowsTheErrorWhenARefreshFails() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);

        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        assertTrue(post(app, "/api/refresh", "{}").statusCode() < 300);
        await(() -> app.service().state().error() != null);

        JsonNode status = json(get(app, "/api/status"));
        assertTrue(status.get("stale").asBoolean());
        assertEquals("Anthropic returned HTTP 503.", status.at("/error/message").asText());
        assertFalse(status.at("/error/at").asText().isBlank());
        assertEquals(186.02, status.at("/usage/spend/used").asDouble(), "the last good reading is kept");
    }

    @Test
    void statusBeforeAnyReadingShowsAnErrorAndNoUsage() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Cannot reach api.anthropic.com: down", new IOException());
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().error() != null);

        JsonNode status = json(get(app, "/api/status"));

        assertTrue(status.get("usage").isNull());
        assertFalse(status.get("stale").asBoolean());
        assertEquals("Cannot reach api.anthropic.com: down", status.at("/error/message").asText());
    }

    @Test
    void statusWhileTheFirstFetchIsStillRunningHasNothingYet() throws Exception {
        FakeFetch fetch = new FakeFetch();
        CountDownLatch release = fetch.holdNext();
        AppRuntime app = start(fetch);
        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));

        JsonNode status = json(get(app, "/api/status"));

        assertTrue(status.get("refreshing").asBoolean());
        assertTrue(status.get("usage").isNull());
        assertTrue(status.get("error").isNull());
        release.countDown();
    }

    @Test
    void pollingStatusNeverFetchesFromAnthropic() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);
        assertEquals(1, fetch.calls.get(), "exactly the fetch made at startup");

        for (int i = 0; i < 100; i++) {
            assertEquals(200, get(app, "/api/status").statusCode());
            get(app, "/api/config");
        }
        Thread.sleep(200);

        assertEquals(1, fetch.calls.get());
    }

    @Test
    void theFirstFetchHappensAtOnceAtStartup() throws Exception {
        FakeFetch fetch = new FakeFetch();

        start(fetch);

        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, fetch.calls.get());
    }

    // ---- /api/refresh: the manual action

    @Test
    void manualRefreshReturnsAtOnceWhileTheFetchIsStillRunning() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null && !app.service().state().refreshing());
        CountDownLatch release = fetch.holdNext();

        HttpResponse<String> response = post(app, "/api/refresh", "{}");
        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));

        assertEquals(202, response.statusCode());
        assertTrue(json(response).get("started").asBoolean());
        assertEquals(2, fetch.calls.get());
        assertTrue(json(get(app, "/api/status")).get("refreshing").asBoolean());
        release.countDown();
    }

    @Test
    void manualRefreshTriggersABackendFetch() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null && !app.service().state().refreshing());

        assertEquals(202, post(app, "/api/refresh", "{}").statusCode());

        await(() -> fetch.calls.get() == 2);
    }

    @Test
    void repeatedManualRefreshesDuringAFetchStartNothingMore() throws Exception {
        FakeFetch fetch = new FakeFetch();
        CountDownLatch release = fetch.holdNext();
        AppRuntime app = start(fetch);
        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = post(app, "/api/refresh", "{}");
            assertEquals(200, response.statusCode());
            assertFalse(json(response).get("started").asBoolean());
        }
        release.countDown();
        await(() -> !app.service().state().refreshing());

        assertEquals(1, fetch.calls.get());
        assertEquals(1, fetch.maxInFlight.get());
    }

    @Test
    void aFloodOfManualRefreshesNeverOverlaps() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        fetch.answer = () -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SPEND;
        };

        for (int i = 0; i < 60; i++) {
            post(app, "/api/refresh", "{}");
        }
        await(() -> fetch.calls.get() >= 3);

        assertEquals(1, fetch.maxInFlight.get());
    }

    // ---- the contract around the endpoints

    @Test
    void wrongMethodsAreRefusedWithAnAllowHeader() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> statusPost = post(app, "/api/status", "{}");
        assertEquals(405, statusPost.statusCode());
        assertEquals("GET", statusPost.headers().firstValue("Allow").orElse(""));

        HttpResponse<String> refreshGet = get(app, "/api/refresh");
        assertEquals(405, refreshGet.statusCode());
        assertEquals("POST", refreshGet.headers().firstValue("Allow").orElse(""));

        assertEquals(405, send(app, "/api/config", HttpRequest.newBuilder().DELETE()).statusCode());
        assertEquals(405, send(app, "/api/config", HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")).statusCode());
    }

    @Test
    void anUnknownEndpointIsAJsonNotFound() throws Exception {
        HttpResponse<String> response = get(start(new FakeFetch()), "/api/nothing");

        assertEquals(404, response.statusCode());
        assertFalse(json(response).get("error").asText().isBlank());
    }

    @Test
    void postsMustDeclareJsonSoAnotherWebPageCannotDriveThem() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null && !app.service().state().refreshing());

        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data"}) {
            HttpRequest.Builder refresh = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", type);
            HttpRequest.Builder config = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString("{\"usageIntervalSeconds\": 90}")).header("Content-Type", type);
            assertEquals(415, send(app, "/api/refresh", refresh).statusCode(), type);
            assertEquals(415, send(app, "/api/config", config).statusCode(), type);
        }
        HttpRequest.Builder noType = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertEquals(415, send(app, "/api/refresh", noType).statusCode());

        Thread.sleep(100);
        assertEquals(1, fetch.calls.get(), "no refresh was started");
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt(), "nothing was changed");
    }

    @Test
    void jsonWithACharsetParameterIsAccepted() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString("{\"usageIntervalSeconds\": 90}"))
                .header("Content-Type", "application/json; charset=utf-8");

        assertEquals(200, send(start(new FakeFetch()), "/api/config", request).statusCode());
    }

    @Test
    void apiResponsesAreJsonAndNeverCached() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        for (HttpResponse<String> response : List.of(
                get(app, "/api/config"), get(app, "/api/status"), post(app, "/api/refresh", "{}"), get(app, "/api/nope"))) {
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        }
    }

    @Test
    void theStatusNeverCarriesACredential() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);

        String body = get(app, "/api/status").body().toLowerCase();

        for (String forbidden : new String[] {"token", "authorization", "bearer", "sk-ant", "password", "secret"}) {
            assertFalse(body.contains(forbidden), forbidden);
        }
    }

    @Test
    void closingStopsTheServerTheServiceAndItsThread() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);
        URI base = app.baseUri();

        app.close();

        assertThrowsIo(() -> http.send(HttpRequest.newBuilder(base.resolve("/api/status")).build(), HttpResponse.BodyHandlers.ofString()));
        int callsAtClose = fetch.calls.get();
        assertFalse(app.service().refreshNow());
        Thread.sleep(150);
        assertEquals(callsAtClose, fetch.calls.get());
    }

    @Test
    void closingInterruptsAFetchInFlight() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.holdNext();
        AppRuntime app = start(fetch);
        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        app.close();

        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 4, "close must not wait for the fetch");
        await(() -> fetch.inFlight.get() == 0);
    }

    // ---- helpers

    private HttpResponse<String> get(AppRuntime app, String path) throws Exception {
        return send(app, path, HttpRequest.newBuilder().GET());
    }

    private HttpResponse<String> post(AppRuntime app, String path, String body) throws Exception {
        return send(app, path, HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json"));
    }

    private HttpResponse<String> send(AppRuntime app, String path, HttpRequest.Builder request) throws Exception {
        return http.send(request.uri(app.baseUri().resolve(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return json.readTree(response.body());
    }

    private static void assertThrowsIo(ThrowingRunnable action) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError("expected an IOException, got " + other, other);
        }
        throw new AssertionError("expected the request to fail");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
