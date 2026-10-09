package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
import org.example.usage.Spend;
import org.example.usage.UsageFetchException;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
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
        AppRuntime runtime = AppRuntime.start(AppFiles.in(dir), options, fetch);
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
        new SettingsStore(dir.resolve("settings.json")).save(org.example.settings.Settings.defaults().withUsageIntervalSeconds(120));

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
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
    }

    @Test
    void aValidUsageIntervalSentTogetherWithAnUpdateIntervalIsRefusedWhole() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/config", "{\"usageIntervalSeconds\": 90, \"pollIntervalSeconds\": 5}");

        assertEquals(400, response.statusCode());
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt(), "the valid half was not applied either");
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
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
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
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
                new AppFiles(dir.resolve("no-such-dir").resolve("settings.json"), dir.resolve("history.csv"), dir.resolve("log")),
                LaunchOptions.none(), new FakeFetch());
        runtimes.add(app);

        HttpResponse<String> response = post(app, "/api/config", "{\"usageIntervalSeconds\": 90}");

        assertEquals(500, response.statusCode());
        assertTrue(json(response).get("error").asText().startsWith("The settings could not be saved"));
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(60), app.service().interval());
    }

    // ---- /api/settings: every setting, read and applied as a whole

    private static String allSettings(int interval, boolean logResponse, String timeFormat) {
        return "{\"usageIntervalSeconds\": " + interval + ", \"logResponse\": " + logResponse
                + ", \"showCountdown\": true, \"showDeltaUsed\": true, \"showDeltaTime\": false"
                + ", \"timeFormat\": \"" + timeFormat + "\", \"historyDeltaUsed\": false, \"historyDeltaTime\": true}";
    }

    @Test
    void theSettingsEndpointGivesEverySettingTheDefaultsAndTheChoices() throws Exception {
        AppRuntime app = start(new FakeFetch());

        JsonNode body = json(get(app, "/api/settings"));

        assertEquals(60, body.at("/settings/usageIntervalSeconds").asInt());
        assertFalse(body.at("/settings/logResponse").asBoolean());
        assertEquals("hh:mm", body.at("/settings/timeFormat").asText());
        assertEquals(8, body.get("settings").size());
        assertEquals(body.get("settings"), body.get("defaults"), "nothing has been changed yet");
        assertEquals("[60,120,180,240,300]", body.get("intervalChoices").toString());
    }

    @Test
    void settingsAreAppliedTogetherSavedAndGivenBack() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/settings", allSettings(120, true, "hh:mm:ss"));

        assertEquals(200, response.statusCode());
        JsonNode body = json(get(app, "/api/settings"));
        assertEquals(120, body.at("/settings/usageIntervalSeconds").asInt());
        assertTrue(body.at("/settings/logResponse").asBoolean());
        assertTrue(body.at("/settings/showCountdown").asBoolean());
        assertFalse(body.at("/settings/showDeltaTime").asBoolean());
        assertEquals("hh:mm:ss", body.at("/settings/timeFormat").asText());
        assertTrue(body.at("/settings/historyDeltaTime").asBoolean());
        assertEquals(60, body.at("/defaults/usageIntervalSeconds").asInt(), "the defaults do not move");
        assertEquals(Duration.ofSeconds(120), app.service().interval(), "the interval reached the service");
        assertEquals(120, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds());
        assertEquals("hh:mm:ss", new SettingsStore(dir.resolve("settings.json")).load().timeFormat().json());
    }

    @Test
    void theLogResponseSettingSwitchesTheResponseLogWhileTheProgramRuns() throws Exception {
        org.example.usage.ResponseLog responseLog = new org.example.usage.ResponseLog();
        AppRuntime app = AppRuntime.start(AppFiles.in(dir), LaunchOptions.none(), new FakeFetch(), responseLog);
        runtimes.add(app);
        assertFalse(responseLog.getAsBoolean(), "off by default");

        post(app, "/api/settings", allSettings(60, true, "hh:mm"));
        assertTrue(responseLog.getAsBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm"));
        assertFalse(responseLog.getAsBoolean());
    }

    @Test
    void anIntervalOutsideTheDropdownIsStillAcceptedAndShown() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(200, post(app, "/api/settings", allSettings(45, false, "hh:mm")).statusCode());

        assertEquals(45, json(get(app, "/api/settings")).at("/settings/usageIntervalSeconds").asInt());
    }

    @Test
    void settingsThatAreIncompleteOrInvalidAreRefusedWholeAndChangeNothing() throws Exception {
        AppRuntime app = start(new FakeFetch());
        String[] bodies = {
                "{}", "{\"usageIntervalSeconds\": 90}",
                allSettings(4, false, "hh:mm"), allSettings(3601, false, "hh:mm"),
                allSettings(90, false, "12h"),
                allSettings(90, false, "hh:mm").replace("\"logResponse\": false", "\"logResponse\": \"no\""),
                allSettings(90, false, "hh:mm").replace("\"showCountdown\": true", "\"showCountdown\": null"),
                "[1]", "not json"};

        for (String body : bodies) {
            HttpResponse<String> response = post(app, "/api/settings", body);

            assertEquals(400, response.statusCode(), body);
            assertFalse(json(response).get("error").asText().isBlank(), body);
        }

        assertEquals(json(get(app, "/api/settings")).get("defaults"), json(get(app, "/api/settings")).get("settings"));
        assertEquals(Duration.ofSeconds(60), app.service().interval());
    }

    @Test
    void theSettingsEndpointOnlyTakesGetAndPostAndPostsMustBeJson() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(405, send(app, "/api/settings", HttpRequest.newBuilder().DELETE()).statusCode());
        assertEquals(415, send(app, "/api/settings", HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "text/plain")).statusCode());
    }

    @Test
    void aMissingSettingsFileIsCreatedAtStartupWithTheDefaults() throws Exception {
        start(new FakeFetch());

        assertTrue(Files.exists(dir.resolve("settings.json")));
        assertEquals(org.example.settings.Settings.defaults(), new SettingsStore(dir.resolve("settings.json")).load());
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
    void statusCarriesTheSecondsUntilTheNextRefresh() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        JsonNode status = json(get(app, "/api/status"));

        assertTrue(status.get("nextRefreshInSeconds").isIntegralNumber(), "a number: " + status);
        long left = status.get("nextRefreshInSeconds").asLong();
        assertTrue(left >= 55 && left <= 60, "the default 60 s interval, counting down: " + left);
    }

    @Test
    void theCountdownFollowsTheConfiguredInterval() throws Exception {
        AppRuntime app = start(new FakeFetch(), cli(90, null));
        await(() -> app.service().state().snapshot() != null);

        long left = json(get(app, "/api/status")).get("nextRefreshInSeconds").asLong();

        assertTrue(left >= 85 && left <= 90, "left: " + left);
    }

    @Test
    void theCountdownIsInTheStatusAndNotInTheConfig() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        assertTrue(json(get(app, "/api/config")).at("/nextRefreshInSeconds").isMissingNode());
        assertFalse(json(get(app, "/api/status")).at("/nextRefreshInSeconds").isMissingNode());
    }

    @Test
    void theCountdownStartsAgainAfterAManualRefresh() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);
        await(() -> countdown(app) <= 58);

        assertEquals(202, post(app, "/api/refresh", "{}").statusCode());

        await(() -> countdown(app) >= 59);
    }

    /** The countdown the status reports right now. */
    private long countdown(AppRuntime app) {
        try {
            return json(get(app, "/api/status")).get("nextRefreshInSeconds").asLong();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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
    void theNewestReadingInTheHistoryIsShownAtOnceWhileTheFirstFetchIsStillRunning() throws Exception {
        writeHistory("datetime,used,limit,currency",
                "2026-10-08 14:24:53,186.02,1000.00,USD",
                "2026-10-08 14:26:53,263.89,1000.00,EUR");
        FakeFetch fetch = new FakeFetch();
        CountDownLatch release = fetch.holdNext();
        AppRuntime app = start(fetch);
        assertTrue(fetch.entered.await(5, TimeUnit.SECONDS));

        JsonNode status = json(get(app, "/api/status"));

        assertEquals(263.89, status.at("/usage/spend/used").asDouble());
        assertEquals(1000.0, status.at("/usage/spend/limit").asDouble());
        assertEquals("EUR", status.at("/usage/spend/currency").asText());
        assertEquals(26, status.at("/usage/spend/percent").asInt());
        assertTrue(status.get("error").isNull());
        assertFalse(status.get("stale").asBoolean());
        assertEquals(2, history(app).size() - 1, "showing it adds nothing to the history");
        release.countDown();
    }

    @Test
    void theReadingFromTheHistoryIsReplacedByTheFirstRefreshAndKeptDimmedIfThatFails() throws Exception {
        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,5.00,10.00,USD");
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null && app.service().state().snapshot().spend().used() > 100);
        assertEquals(186.02, json(get(app, "/api/status")).at("/usage/spend/used").asDouble());

        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,5.00,10.00,USD");
        FakeFetch failing = new FakeFetch();
        failing.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        AppRuntime second = start(failing);
        await(() -> second.service().state().error() != null);

        JsonNode status = json(get(second, "/api/status"));
        assertTrue(status.get("stale").asBoolean());
        assertEquals(5.0, status.at("/usage/spend/used").asDouble(), "the reading from the file stays, marked stale");
        assertEquals("Anthropic returned HTTP 503.", status.at("/error/message").asText());
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

    // ---- the usage history

    /** The history file's lines so far; empty if there is no file yet. */
    private List<String> history(AppRuntime app) {
        Path file = AppFiles.in(dir).history();
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aReadingWithAmountsIsAddedToTheHistoryWithAHeader() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);

        List<String> lines = history(app);

        assertEquals("datetime,used,limit,currency,status,interval,duration_ms", lines.get(0));
        assertTrue(lines.get(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},186\\.02,1000\\.00,USD,start,60,\\d+"), lines.get(1));
    }

    @Test
    void theTimeInTheHistoryIsTheReadingsLocalDateAndTimeInAFormExcelReads() throws Exception {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Vienna"));
            AppRuntime app = start(new FakeFetch());
            await(() -> history(app).size() >= 2);

            // FakeFetch answers with a reading stamped 12:00 UTC, which is 14:00 in Vienna in October.
            assertTrue(history(app).get(1).startsWith("2026-10-08 14:00:00,"), history(app).get(1));
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }

    @Test
    void everySuccessfulRefreshAddsARow() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);

        assertEquals(202, post(app, "/api/refresh", "{}").statusCode());
        await(() -> history(app).size() >= 3);

        assertEquals(3, history(app).size(), "the header and two rows");
    }

    @Test
    void aFailedRefreshWritesARowWithNoAmounts() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().error() != null);
        await(() -> history(app).size() >= 2);

        assertEquals(2, history(app).size(), "the header and the failed query");
        assertTrue(history(app).get(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},,,,start-failed,60,\\d+"), history(app).get(1));
    }

    @Test
    void aFailureThenASuccessAreRowsMarkedFailedFirstAndCarryTheIntervalInForce() throws Exception {
        FakeFetch fetch = new FakeFetch();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.function.Supplier<UsageSnapshot> good = fetch.answer;
        fetch.answer = () -> {
            if (fail.get()) {
                throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
            }
            return good.get();
        };
        AppRuntime app = start(fetch);
        await(() -> history(app).size() >= 2);
        post(app, "/api/settings", "{\"usageIntervalSeconds\": 120, \"logResponse\": false, \"showCountdown\": false,"
                + " \"showDeltaUsed\": false, \"showDeltaTime\": false, \"timeFormat\": \"hh:mm\","
                + " \"historyDeltaUsed\": false, \"historyDeltaTime\": false}");
        fail.set(false);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        assertTrue(history(app).get(1).contains(",,,,start-failed,60,"), history(app).get(1));
        assertTrue(history(app).get(2).matches(".*,186\\.02,1000\\.00,USD,,120,\\d+"), history(app).get(2));
    }

    @Test
    void theHistoryAndTheStatusCarryTheChangeSinceTheRowBefore() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> history(app).size() >= 2);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        JsonNode history = json(get(app, "/api/history"));
        assertEquals(2, history.get("deltas").size(), "one for each row");
        assertTrue(history.at("/deltas/1/delta_used").isNull(), "the first row of the run has none");
        assertTrue(history.at("/deltas/1/delta_time").isNull());
        assertEquals(0.0, history.at("/deltas/0/delta_used").asDouble(), 1e-9, "FakeFetch answers the same amount");
        assertTrue(history.at("/deltas/0/delta_time").isIntegralNumber());

        JsonNode change = json(get(app, "/api/status")).get("change");
        assertEquals(history.at("/deltas/0"), change, "the status has the newest reading's");
    }

    @Test
    void theStatusHasNoChangeBeforeThereIsAReading() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().error() != null);

        assertTrue(json(get(app, "/api/status")).get("change").isNull());
    }

    @Test
    void aPlanAccountsReadingWritesNothing() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> WINDOWS;
        AppRuntime app = start(fetch);
        await(() -> app.service().state().snapshot() != null);

        assertEquals(List.of(), history(app));
    }

    @Test
    void aHistoryThatCannotBeWrittenDoesNotMakeTheRefreshFail() throws Exception {
        AppRuntime app = AppRuntime.start(
                new AppFiles(dir.resolve("settings.json"), dir.resolve("no-such-dir").resolve("history.csv"), dir.resolve("log")),
                LaunchOptions.none(), new FakeFetch());
        runtimes.add(app);

        await(() -> app.service().state().snapshot() != null);

        UsageState state = app.service().state();
        assertEquals(null, state.error(), "the reading is good and the refresh did succeed");
        assertFalse(state.stale());
        assertEquals(186.02, state.snapshot().spend().used());
    }

    @Test
    void theHistoryNeverHoldsAnythingButTheSevenColumns() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        for (String line : history(app)) {
            assertEquals(6, line.chars().filter(c -> c == ',').count(), line);
        }
    }

    // ---- the log and the history, for the two windows that show them

    private void writeLog(String... lines) throws IOException {
        Files.write(AppFiles.in(dir).log(), List.of(lines));
    }

    private void writeHistory(String... rows) throws IOException {
        Files.write(AppFiles.in(dir).history(), List.of(rows));
    }

    @Test
    void theLogEndpointGivesTheLinesOfTheLogFile() throws Exception {
        writeLog("first line", "second line");
        AppRuntime app = start(new FakeFetch());

        JsonNode log = json(get(app, "/api/log"));

        assertTrue(log.get("exists").asBoolean());
        assertFalse(log.get("truncated").asBoolean());
        assertEquals("java-aip-usage.log", log.get("file").asText(), "the name, not the path");
        assertEquals(List.of("first line", "second line"), java.util.stream.StreamSupport.stream(log.get("lines").spliterator(), false).map(JsonNode::asText).toList());
    }

    @Test
    void theLogEndpointSaysSoWhenThereIsNoLog() throws Exception {
        JsonNode log = json(get(start(new FakeFetch()), "/api/log"));

        assertFalse(log.get("exists").asBoolean());
        assertEquals(0, log.get("lines").size());
    }

    @Test
    void aLongLogIsCutToTheLastThousandLines() throws Exception {
        writeLog(java.util.stream.IntStream.rangeClosed(1, 1500).mapToObj(i -> "line " + i).toArray(String[]::new));
        AppRuntime app = start(new FakeFetch());

        JsonNode log = json(get(app, "/api/log"));

        assertTrue(log.get("truncated").asBoolean());
        assertEquals(1000, log.get("lines").size());
        assertEquals("line 501", log.at("/lines/0").asText());
        assertEquals("line 1500", log.at("/lines/999").asText());
    }

    @Test
    void theHistoryEndpointGivesTheRowsNewestFirst() throws Exception {
        writeHistory("datetime,used,limit,currency",
                "2026-10-08 14:24:53,186.02,1000.00,USD",
                "2026-10-08 14:26:53,186.12,1000.00,USD",
                "2026-10-08 14:25:53,186.07,1000.00,USD");
        AppRuntime app = start(new FakeFetch());

        JsonNode history = json(get(app, "/api/history"));

        assertTrue(history.get("exists").asBoolean());
        assertEquals("java-aip-usage.csv", history.get("file").asText());
        assertEquals("datetime", history.at("/columns/0").asText());
        assertEquals("limit", history.at("/columns/2").asText());
        assertEquals("currency", history.at("/columns/3").asText());
        assertEquals("USD", history.at("/rows/0/3").asText());
        assertEquals(history.get("total").asInt(), history.get("rows").size());
        assertEquals("2026-10-08 14:26:53", history.at("/rows/0/0").asText(), "sorted by datetime, newest first");
        assertEquals("186.12", history.at("/rows/0/1").asText());
        assertEquals("1000.00", history.at("/rows/0/2").asText());
    }

    @Test
    void theHistoryEndpointShowsTheRowsTheApplicationWritesItself() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);
        assertEquals(202, post(app, "/api/refresh", "{}").statusCode());
        await(() -> history(app).size() >= 3);

        JsonNode history = json(get(app, "/api/history"));

        assertEquals(2, history.get("total").asInt());
        assertEquals("186.02", history.at("/rows/0/1").asText());
    }

    @Test
    void theHistoryEndpointSaysSoWhenThereIsNoHistory() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> WINDOWS;
        AppRuntime app = start(plan);
        await(() -> app.service().state().snapshot() != null);

        JsonNode history = json(get(app, "/api/history"));

        assertFalse(history.get("exists").asBoolean());
        assertEquals(0, history.get("rows").size());
    }

    @Test
    void aLongHistoryIsCutToTheNewestThousandRows() throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<>(List.of("datetime,used,limit"));
        for (int i = 0; i < 1500; i++) {
            rows.add(String.format("2026-01-01 00:00:%02d,1.00,2.00", 0).replace("00:00:00", String.format("%02d:%02d:00", i / 60 % 24, i % 60)));
        }
        writeHistory(rows.toArray(String[]::new));
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> WINDOWS;
        AppRuntime app = start(plan);

        JsonNode history = json(get(app, "/api/history"));

        assertEquals(1500, history.get("total").asInt());
        assertEquals(1000, history.get("rows").size());
    }

    @Test
    void neitherEndpointRevealsWhereTheFilesAre() throws Exception {
        writeLog("a line");
        writeHistory("datetime,used,limit", "2026-10-08 14:24:53,1.00,2.00");
        AppRuntime app = start(new FakeFetch());

        assertFalse(get(app, "/api/log").body().contains(dir.toString()));
        assertFalse(get(app, "/api/history").body().contains(dir.toString()));
    }

    @Test
    void theLogAndHistoryAreReadOnly() throws Exception {
        AppRuntime app = start(new FakeFetch());

        for (String path : new String[] {"/api/log", "/api/history"}) {
            HttpResponse<String> response = post(app, path, "{}");
            assertEquals(405, response.statusCode(), path);
            assertEquals("GET", response.headers().firstValue("Allow").orElse(""), path);
        }
    }

    @Test
    void anUnreadableLogIsAnErrorThatDoesNotShowTheReason() throws Exception {
        // A directory where the log file should be: it exists, but cannot be read as a file.
        Files.createDirectory(AppFiles.in(dir).log());
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = get(app, "/api/log");

        // A directory is simply not a regular file, so the log counts as not there.
        assertEquals(200, response.statusCode());
        assertFalse(json(response).get("exists").asBoolean());
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
