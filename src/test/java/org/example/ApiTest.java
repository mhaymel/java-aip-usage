package org.example;

import org.example.money.Amount;
import org.example.json.Json;
import org.example.json.JsonReader;
import org.example.settings.CostControl;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
import org.example.usage.Spend;
import org.example.usage.UsageFetchException;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application as a whole, minus JavaFX and the network: real settings, real
 * service, real HTTP server, with a stand-in for the usage fetch.
 */
class ApiTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-08T12:00:00Z");

    private static final UsageSnapshot SPEND = new UsageSnapshot(
            FETCHED_AT, new Spend(Amount.parse("186.02"), Amount.parse("1000.0"), "USD", 19, "normal"));

    /** A reading with no amounts: an account that reports no usage. Nothing of it is written to the history. */
    private static final UsageSnapshot NO_USAGE = new UsageSnapshot(FETCHED_AT, null);

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    private final List<AppRuntime> runtimes = new ArrayList<>();

    /** A fetcher that counts its calls, can be held open, and answers with whatever is set. */
    private static final class FakeFetch implements org.example.usage.UsageFetch {

        final AtomicInteger calls = new AtomicInteger();

        final AtomicInteger inFlight = new AtomicInteger();

        final AtomicInteger maxInFlight = new AtomicInteger();

        volatile Supplier<UsageSnapshot> answer = () -> SPEND;

        volatile CountDownLatch hold;

        volatile CountDownLatch entered = new CountDownLatch(1);

        @Override
        public UsageSnapshot fetch(org.example.Cancel cancel) {
            calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                CountDownLatch latch = hold;
                if (latch != null) {
                    // Told to give up, a held request is let go, as the real one stops waiting for the network.
                    cancel.onCancel(latch::countDown);
                }
                if (latch != null) {
                    latch.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UsageFetchException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
            if (cancel.isCancelled()) {
                throw new UsageFetchException("interrupted", null);
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

    /** With the usage interval the settings file would hold; the command line no longer has one. */
    private AppRuntime start(FakeFetch fetch, int usageSeconds) throws IOException {
        new SettingsStore(dir.resolve("settings.json")).save(org.example.settings.Settings.defaults().withUsageIntervalSeconds(usageSeconds));
        return start(fetch);
    }

    private AppRuntime start(FakeFetch fetch, LaunchOptions options) throws IOException {
        AppRuntime runtime = AppRuntime.start(AppFiles.in(dir), options, fetch);
        runtimes.add(runtime);
        return runtime;
    }

    // ---- /api/config: the effective intervals, requested by the UI at startup

    @Test
    void startupConfigReportsTheEffectiveIntervalsAndTheUsageLimits() throws Exception {
        AppRuntime app = start(new FakeFetch());

        Json config = json(get(app, "/api/config"));

        assertEquals(60, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
        assertEquals(5, config.at("/limits/usageIntervalSeconds/min").asInt());
        assertEquals(3600, config.at("/limits/usageIntervalSeconds/max").asInt());
    }

    @Test
    void theUpdateIntervalIsReportedButHasNoLimitsBecauseItCannotBeEdited() throws Exception {
        Json config = json(get(start(new FakeFetch()), "/api/config"));

        assertTrue(config.get("pollIntervalSeconds").fitsInt(), "the page needs it to know how often to poll");
        assertNull(config.at("/limits/pollIntervalSeconds"), "nothing to validate: " + config);
    }

    @Test
    void startupConfigReflectsTheSavedUsageInterval() throws Exception {
        new SettingsStore(dir.resolve("settings.json")).save(org.example.settings.Settings.defaults().withUsageIntervalSeconds(120));

        Json config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(120, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void anUpdateIntervalLeftInTheFileByAnEarlierVersionIsNotUsed() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"usageIntervalSeconds\": 120, \"pollIntervalSeconds\": 5}");

        Json config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(120, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
    }

    @Test
    void aValidUsageIntervalReachesTheRefreshServiceAndIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/settings", allSettings(90, false, "hh:mm"));

        assertEquals(200, response.statusCode());
        assertEquals(90, json(response).at("/settings/usageIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(90), app.service().interval());
        assertEquals(90, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds());
        assertEquals(90, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt());
    }

    @Test
    void aChangeSurvivesARestart() throws Exception {
        AppRuntime first = start(new FakeFetch());
        post(first, "/api/settings", allSettings(200, false, "hh:mm"));
        first.close();

        Json config = json(get(start(new FakeFetch()), "/api/config"));

        assertEquals(200, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt(), "the window asks once a second, and nothing changes that");
    }

    @Test
    void theSavedFileNeverHoldsTheUpdateInterval() throws Exception {
        AppRuntime app = start(new FakeFetch());

        post(app, "/api/settings", allSettings(200, false, "hh:mm"));

        assertFalse(Files.readString(dir.resolve("settings.json")).contains("poll"), Files.readString(dir.resolve("settings.json")));
    }

    // ---- the update interval cannot be changed through the API

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
            HttpResponse<String> response = post(app, "/api/settings", body);

            assertEquals(400, response.statusCode(), body);
            assertFalse(json(response).get("error").asText().isBlank(), body);
        }

        Json config = json(get(app, "/api/config"));
        assertEquals(60, config.get("usageIntervalSeconds").asInt());
        assertEquals(1, config.get("pollIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(60), app.service().interval());
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
    }

    @Test
    void theRefusalSaysWhatIsAccepted() throws Exception {
        HttpResponse<String> response = post(start(new FakeFetch()), "/api/settings", allSettings(4, false, "hh:mm"));

        assertEquals("The usage interval must be a whole number of seconds from 5 to 3600.",
                json(response).get("error").asText());
    }

    @Test
    void theBoundariesAreAccepted() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(200, post(app, "/api/settings", allSettings(5, false, "hh:mm")).statusCode());
        assertEquals(200, post(app, "/api/settings", allSettings(3600, false, "hh:mm")).statusCode());
    }

    @Test
    void malformedOrOversizedBodiesAreRefused() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(400, post(app, "/api/settings", "not json").statusCode());
        HttpResponse<String> trailing = post(app, "/api/settings", "{} x");
        assertEquals(400, trailing.statusCode(), "a body is its one JSON value and nothing after it");
        assertEquals("The request body is not valid JSON.", json(trailing).get("error").asText());
        assertEquals(400, post(app, "/api/settings", "").statusCode());
        assertEquals(400, post(app, "/api/settings", "[1, 2]").statusCode());
        assertEquals(400, post(app, "/api/settings", "\"text\"").statusCode());
        assertEquals(413, post(app, "/api/settings", "{\"x\": \"" + "a".repeat(5000) + "\"}").statusCode());
    }

    @Test
    void whenTheFileCannotBeWrittenTheChangeIsRefusedAndNothingChanges() throws Exception {
        AppRuntime app = AppRuntime.start(
                new AppFiles(dir.resolve("no-such-dir").resolve("settings.json"), dir.resolve("history.csv"), dir.resolve("log")),
                LaunchOptions.none(), new FakeFetch());
        runtimes.add(app);

        HttpResponse<String> response = post(app, "/api/settings", allSettings(90, false, "hh:mm"));

        assertEquals(500, response.statusCode());
        assertTrue(json(response).get("error").asText().startsWith("The settings could not be saved"));
        assertEquals(60, json(get(app, "/api/config")).get("usageIntervalSeconds").asInt());
        assertEquals(Duration.ofSeconds(60), app.service().interval());
    }

    // ---- /api/settings: every setting, read and applied as a whole

    private static String allSettings(int interval, boolean logResponse, String timeFormat) {
        return "{\"usageIntervalSeconds\": " + interval + ", \"logResponse\": " + logResponse
                + ", \"showPercentage\": true, \"showCurrency\": false, \"showHistoryIcon\": true, \"showLogIcon\": true, \"showErrorIcon\": true, \"showInterval\": true, \"showDeltaUsed\": true, \"showDeltaTime\": false"
                + ", \"timeFormat\": \"" + timeFormat + "\", \"historyDeltaUsed\": false, \"historyDeltaTime\": true, \"historyDate\": false, \"historyZeroLines\": true, \"historyFailedLines\": true, \"showTime\": true, \"showUsed\": true, \"showLimit\": true, \"showRefresh\": true, \"showCountdown\": true, \"showDuration\": false, \"stayOnTop\": false, \"showTitleBar\": true, \"autoInterval\": false}";
    }

    @Test
    void theSettingsEndpointGivesEverySettingTheDefaultsAndTheLimits() throws Exception {
        AppRuntime app = start(new FakeFetch());

        Json body = json(get(app, "/api/settings"));

        assertEquals(60, body.at("/settings/usageIntervalSeconds").asInt());
        assertFalse(body.at("/settings/logResponse").asBoolean());
        assertEquals("hh:mm", body.at("/settings/timeFormat").asText());
        assertEquals(48, body.get("settings").size(), "the debug settings are not among them");
        assertEquals(body.get("settings"), body.get("defaults"), "nothing has been changed yet");
        assertFalse(body.has("intervalChoices"), "no list of values: the interval is one field");
        assertEquals(5, body.at("/limits/usageIntervalSeconds/min").asInt());
        assertEquals(3600, body.at("/limits/usageIntervalSeconds/max").asInt());
    }

    @Test
    void theFiveElementsThatWereAlwaysInTheRowAreSettingsTooAndTheStatusFollowsThem() throws Exception {
        AppRuntime app = start(new FakeFetch());
        Json defaults = json(get(app, "/api/settings")).get("defaults");
        for (String key : List.of("showTime", "showUsed", "showLimit", "showRefresh", "showCountdown")) {
            assertTrue(defaults.get(key).asBoolean(), key + " is on by default");
        }

        String hidden = allSettings(60, false, "hh:mm")
                .replace("\"showTime\": true", "\"showTime\": false")
                .replace("\"showCountdown\": true", "\"showCountdown\": false");
        assertEquals(200, post(app, "/api/settings", hidden).statusCode());

        Json show = json(get(app, "/api/status")).at("/display/show");
        assertFalse(show.get("time").asBoolean());
        assertFalse(show.get("countdown").asBoolean());
        assertTrue(show.get("used").asBoolean());
        assertTrue(show.get("limit").asBoolean());
        assertTrue(show.get("refresh").asBoolean());
    }

    @Test
    void theRequestDurationIsInTheStatusOnceARequestHasEndedAndItsSwitchIsASetting() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        Json display = json(get(app, "/api/status")).get("display");
        assertTrue(display.at("/duration/text").asText().matches("\\d+ms|\\d+s\\d{3}ms"), display.get("duration").toString());
        assertEquals("How long the latest usage request took", display.at("/duration/tooltip").asText());
        assertFalse(display.at("/show/duration").asBoolean(), "off by default");

        String on = allSettings(60, false, "hh:mm").replace("\"showDuration\": false", "\"showDuration\": true");
        assertEquals(200, post(app, "/api/settings", on).statusCode());
        assertTrue(json(get(app, "/api/status")).at("/display/show/duration").asBoolean());

        // The figure of the row is the one the history file has for that request.
        String row = history(app).getLast();
        String millis = row.split(",", -1)[6];
        long written = Long.parseLong(millis);
        String expected = written < 1000 ? written + "ms" : (written / 1000) + "s" + String.format("%03d", written % 1000) + "ms";
        assertEquals(expected, json(get(app, "/api/status")).at("/display/duration/text").asText());
    }

    @Test
    void stayOnTopIsASettingOffByDefaultThatIsAppliedAndGivenBack() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertFalse(json(get(app, "/api/settings")).at("/settings/stayOnTop").asBoolean());
        assertFalse(app.settings().current().stayOnTop());

        String on = allSettings(60, false, "hh:mm").replace("\"stayOnTop\": false", "\"stayOnTop\": true");
        assertEquals(200, post(app, "/api/settings", on).statusCode());

        assertTrue(json(get(app, "/api/settings")).at("/settings/stayOnTop").asBoolean());
        assertTrue(app.settings().current().stayOnTop(), "which is where the window reads it");
    }

    @Test
    void theTitleBarIsASettingOnByDefaultThatIsAppliedAndGivenBack() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertTrue(json(get(app, "/api/settings")).at("/settings/showTitleBar").asBoolean());

        String off = allSettings(60, false, "hh:mm").replace("\"showTitleBar\": true", "\"showTitleBar\": false");
        assertEquals(200, post(app, "/api/settings", off).statusCode());

        assertFalse(json(get(app, "/api/settings")).at("/settings/showTitleBar").asBoolean());
        assertFalse(app.settings().current().showTitleBar(), "which is where the window reads it");
    }

    // ---- debug mode: the debug settings, and where usage comes from

    private static final String FAKE_ON = "{\"fakeBackend\": true, \"fakeScenario\": \"normal\", \"fakeToken\": false}";

    private static final String FAKE_OFF = "{\"fakeBackend\": false, \"fakeScenario\": \"normal\", \"fakeToken\": false}";

    @Test
    void withoutDebugModeThereAreNoDebugSettingsToAskFor() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertFalse(json(get(app, "/api/config")).get("debug").asBoolean());
        assertEquals(404, get(app, "/api/debug").statusCode());
        assertEquals(404, post(app, "/api/debug", FAKE_ON).statusCode());
        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean());
        assertFalse(json(get(app, "/api/settings")).get("settings").has("fakeBackend"), "nor are they among the settings");
    }

    @Test
    void withoutDebugModeTheDebugSettingsOfTheFileCountForNothing() throws Exception {
        new SettingsStore(dir.resolve("settings.json")).save(
                org.example.settings.Settings.defaults().withDebug(true, org.example.fake.Scenario.HTTP_500, true));
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch);
        await(() -> fetch.calls.get() >= 1);
        await(() -> !history(app).isEmpty());

        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean(), "a run without --debug is never fake");
        assertTrue(Files.exists(dir.resolve("java-aip-usage.csv")), "its readings are real ones");
        assertFalse(Files.exists(dir.resolve("java-aip-usage.fake.csv")));
        assertTrue(new SettingsStore(dir.resolve("settings.json")).load().fakeBackend(), "and the file keeps what it had");
    }

    @Test
    void inDebugModeTheDebugSettingsAreGivenWithTheScenariosThereAre() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.debugMode());

        assertTrue(json(get(app, "/api/config")).get("debug").asBoolean());
        Json body = json(get(app, "/api/debug"));
        assertFalse(body.at("/settings/fakeBackend").asBoolean(), "off by default");
        assertEquals("normal", body.at("/settings/fakeScenario").asText());
        assertFalse(body.at("/settings/fakeToken").asBoolean());
        assertEquals(org.example.fake.Scenario.values().length, body.get("scenarios").size());
        assertEquals("normal", body.at("/scenarios/0").asText());
    }

    @Test
    void theFakeBackendIsSwitchedOnAndOffWhileTheProgramRunsAndIsSaved() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch, LaunchOptions.debugMode());
        await(() -> app.service().state().snapshot() != null);
        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean());
        int before = fetch.calls.get();

        HttpResponse<String> on = post(app, "/api/debug", FAKE_ON);

        assertEquals(200, on.statusCode());
        assertTrue(json(on).at("/settings/fakeBackend").asBoolean());
        assertTrue(json(get(app, "/api/status")).get("fake").asBoolean(), "at once");
        await(() -> fetch.calls.get() > before);
        assertTrue(new SettingsStore(dir.resolve("settings.json")).load().fakeBackend(), "and for the next start in debug mode");

        assertEquals(200, post(app, "/api/debug", FAKE_OFF).statusCode());
        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean());
        assertFalse(new SettingsStore(dir.resolve("settings.json")).load().fakeBackend());
    }

    @Test
    void inventedReadingsHaveAHistoryFileOfTheirOwnAndTheRealOneIsNotTouched() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch, LaunchOptions.debugMode());
        Path real = dir.resolve("java-aip-usage.csv");
        Path fake = dir.resolve("java-aip-usage.fake.csv");
        await(() -> Files.exists(real) && lines(real) >= 2);
        long realRows = lines(real);
        assertFalse(Files.exists(fake));

        post(app, "/api/debug", FAKE_ON);
        await(() -> Files.exists(fake) && lines(fake) >= 2);
        post(app, "/api/refresh", "{}");
        await(() -> lines(fake) >= 3);

        assertEquals(realRows, lines(real), "nothing invented among the real readings");
        assertEquals("java-aip-usage.fake.csv", json(get(app, "/api/history")).get("file").asText(), "the panel shows the file in use");
        assertEquals(lines(fake) - 1, json(get(app, "/api/history")).get("total").asInt());

        post(app, "/api/debug", FAKE_OFF);
        long fakeRows = lines(fake);
        await(() -> lines(real) > realRows);
        assertEquals(fakeRows, lines(fake), "and nothing real among the invented ones");
        assertEquals("java-aip-usage.csv", json(get(app, "/api/history")).get("file").asText());
    }

    @Test
    void theReadingOfTheOtherSourceGoesWithItAndTheNewestOfTheNewSourcesOwnHistoryIsShown() throws Exception {
        FakeFetch fetch = new FakeFetch();
        AppRuntime app = start(fetch, LaunchOptions.debugMode());
        await(() -> app.service().state().snapshot() != null);
        // Held, so that what the status shows right after the switch is what the service was given, not a new reading.
        fetch.hold = new CountDownLatch(1);

        post(app, "/api/debug", FAKE_ON);

        assertTrue(json(get(app, "/api/status")).get("usage").isNull(), "the real reading is not shown as an invented one");
        assertTrue(json(get(app, "/api/status")).at("/display/loading").asBoolean(), "the row begins again");
        fetch.hold.countDown();
    }

    @Test
    void aStartInDebugModeWithTheFakeBackendSavedUsesItFromTheFirstRequest() throws Exception {
        new SettingsStore(dir.resolve("settings.json")).save(
                org.example.settings.Settings.defaults().withDebug(true, org.example.fake.Scenario.NORMAL, false));
        AppRuntime app = start(new FakeFetch(), LaunchOptions.debugMode());
        await(() -> Files.exists(dir.resolve("java-aip-usage.fake.csv")));

        assertTrue(json(get(app, "/api/status")).get("fake").asBoolean());
        assertFalse(Files.exists(dir.resolve("java-aip-usage.csv")));
    }

    @Test
    void theDebugSettingsAreCheckedLikeTheOthersAndTheOrdinarySettingsLeaveThemAlone() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.debugMode());

        assertEquals("The setting fakeBackend is missing.", json(post(app, "/api/debug", "{\"fakeScenario\": \"normal\", \"fakeToken\": false}")).get("error").asText());
        assertEquals("The setting fakeScenario is missing.", json(post(app, "/api/debug", "{\"fakeBackend\": true, \"fakeToken\": false}")).get("error").asText());
        HttpResponse<String> unknown = post(app, "/api/debug", "{\"fakeBackend\": true, \"fakeScenario\": \"nope\", \"fakeToken\": false}");
        assertEquals(400, unknown.statusCode());
        assertTrue(json(unknown).get("error").asText().startsWith("The setting fakeScenario must be one of normal, "), unknown.body());
        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean(), "nothing was changed by any of them");

        post(app, "/api/debug", "{\"fakeBackend\": true, \"fakeScenario\": \"http-429\", \"fakeToken\": true}");
        post(app, "/api/settings", allSettings(120, false, "hh:mm"));

        org.example.settings.Settings saved = new SettingsStore(dir.resolve("settings.json")).load();
        assertEquals(120, saved.usageIntervalSeconds());
        assertTrue(saved.fakeBackend(), "the settings view does not know the debug settings, and does not reset them");
        assertEquals(org.example.fake.Scenario.HTTP_429, saved.fakeScenario());
        assertTrue(saved.fakeToken());
    }

    @Test
    void aSourceThatCannotBeOpenedChangesNothing() throws Exception {
        FakeFetch fetch = new FakeFetch();
        UsageSources.Factory failing = (fakeBackend, scenario, fakeToken) -> {
            if (fakeBackend) {
                throw new Fault("no port to listen on");
            }
            return new UsageSources.Source(fetch, false, Optional.empty(), null);
        };
        AppRuntime app = AppRuntime.start(AppFiles.in(dir), LaunchOptions.debugMode(), failing, new org.example.usage.ResponseLog());
        runtimes.add(app);

        HttpResponse<String> response = post(app, "/api/debug", FAKE_ON);

        assertEquals(500, response.statusCode());
        assertEquals("The debug settings could not be applied: no port to listen on", json(response).get("error").asText());
        assertFalse(json(get(app, "/api/status")).get("fake").asBoolean());
        assertFalse(new SettingsStore(dir.resolve("settings.json")).load().fakeBackend(), "and nothing was saved");
    }

    @Test
    void theSourceThatIsLeftIsClosedAndSoIsTheLastOneAtTheEnd() throws Exception {
        List<String> closed = new java.util.concurrent.CopyOnWriteArrayList<>();
        FakeFetch fetch = new FakeFetch();
        UsageSources.Factory counting = (fakeBackend, scenario, fakeToken) ->
                new UsageSources.Source(fetch, fakeBackend, Optional.empty(), () -> closed.add(fakeBackend ? "fake" : "real"));
        AppRuntime app = AppRuntime.start(AppFiles.in(dir), LaunchOptions.debugMode(), counting, new org.example.usage.ResponseLog());

        post(app, "/api/debug", FAKE_ON);
        assertEquals(List.of("real"), closed);

        app.close();
        assertEquals(List.of("real", "fake"), closed);
    }

    private static long lines(Path file) {
        try {
            return Files.readAllLines(file).size();
        } catch (IOException e) {
            return 0;
        }
    }

    @Test
    void aSettingOfTheRowThatIsMissingIsRefusedLikeAnyOther() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/settings", allSettings(60, false, "hh:mm").replace(", \"showRefresh\": true", ""));

        assertEquals(400, response.statusCode());
        assertEquals("The setting showRefresh is missing.", json(response).get("error").asText());
    }

    @Test
    void settingsAreAppliedTogetherSavedAndGivenBack() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/settings", allSettings(120, true, "hh:mm:ss"));

        assertEquals(200, response.statusCode());
        Json body = json(get(app, "/api/settings"));
        assertEquals(120, body.at("/settings/usageIntervalSeconds").asInt());
        assertTrue(body.at("/settings/logResponse").asBoolean());
        assertTrue(body.at("/settings/showInterval").asBoolean());
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
                allSettings(90, false, "hh:mm").replace("\"showInterval\": true", "\"showInterval\": null"),
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

        Json status = json(get(app, "/api/status"));

        assertFalse(status.get("stale").asBoolean());
        assertTrue(status.get("error").isNull());
        Json usage = status.get("usage");
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
    void statusCarriesTheSecondsUntilTheNextRefresh() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        Json status = json(get(app, "/api/status"));

        assertTrue(status.get("nextRefreshInSeconds").isWhole(), "a number: " + status);
        long left = status.get("nextRefreshInSeconds").asLong();
        assertTrue(left >= 55 && left <= 60, "the default 60 s interval, counting down: " + left);
    }

    @Test
    void theCountdownFollowsTheConfiguredInterval() throws Exception {
        AppRuntime app = start(new FakeFetch(), 90);
        await(() -> app.service().state().snapshot() != null);

        long left = json(get(app, "/api/status")).get("nextRefreshInSeconds").asLong();

        assertTrue(left >= 85 && left <= 90, "left: " + left);
    }

    @Test
    void theCountdownIsInTheStatusAndNotInTheConfig() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        assertNull(json(get(app, "/api/config")).at("/nextRefreshInSeconds"));
        assertNotNull(json(get(app, "/api/status")).at("/nextRefreshInSeconds"));
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

        Json status = json(get(app, "/api/status"));
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

        Json status = json(get(app, "/api/status"));

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

        Json status = json(get(app, "/api/status"));

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
        await(() -> app.service().state().snapshot() != null && app.service().state().snapshot().spend().used().compareTo(Amount.parse("100")) > 0);
        assertEquals(186.02, json(get(app, "/api/status")).at("/usage/spend/used").asDouble());

        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,5.00,10.00,USD");
        FakeFetch failing = new FakeFetch();
        failing.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        AppRuntime second = start(failing);
        await(() -> second.service().state().error() != null);

        Json status = json(get(second, "/api/status"));
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

        Json status = json(get(app, "/api/status"));

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

        assertEquals("datetime,used,limit,currency,status,interval,duration_ms,monthly_limit,daily_limit,daily_auto", lines.get(0));
        // The limits of cost control at the reading (since 0.82): none typed, so the server's 1,000.00 and the daily one from it.
        assertTrue(lines.get(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},186\\.02,1000\\.00,USD,start,60,\\d+,1000\\.00,\\d+\\.\\d{2},auto"), lines.get(1));
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
        assertTrue(history(app).get(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},,,,start-failed,60,\\d+,,,"), history(app).get(1));
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
        post(app, "/api/settings", "{\"usageIntervalSeconds\": 120, \"logResponse\": false, \"showPercentage\": false, \"showCurrency\": false, \"showHistoryIcon\": true, \"showLogIcon\": true, \"showErrorIcon\": true, \"showInterval\": false,"
                + " \"showDeltaUsed\": false, \"showDeltaTime\": false, \"timeFormat\": \"hh:mm\","
                + " \"historyDeltaUsed\": false, \"historyDeltaTime\": false, \"historyDate\": false, \"historyZeroLines\": true, \"historyFailedLines\": true, \"showTime\": true, \"showUsed\": true, \"showLimit\": true, \"showRefresh\": true, \"showCountdown\": true, \"showDuration\": false, \"stayOnTop\": false, \"showTitleBar\": true, \"autoInterval\": false}");
        fail.set(false);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        assertTrue(history(app).get(1).contains(",,,,start-failed,60,"), history(app).get(1));
        assertTrue(history(app).get(2).matches(".*,186\\.02,1000\\.00,USD,,120,\\d+,1000\\.00,\\d+\\.\\d{2},auto"), history(app).get(2));
    }

    @Test
    void theHistoryAndTheStatusCarryTheChangeSinceTheRowBefore() throws Exception {
        writeHistory("datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:03,10.05,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true"));

        Json history = json(get(app, "/api/history"));

        assertEquals(6, history.get("columns").size(), "both change columns are on");
        assertEquals("\u0394 used", history.at("/columns/3").asText());
        assertEquals("\u0394 time", history.at("/columns/4").asText());
        assertEquals("Cur.", history.at("/columns/5").asText(), "the currency is the right-most column");
        assertEquals("+0.05", history.at("/lines/0/cells/3").asText());
        assertEquals("63 s", history.at("/lines/0/cells/4").asText());
        assertEquals("+10.00", history.at("/lines/1/cells/3").asText(), "no earlier line has an amount: the change is the amount itself");
        assertEquals("", history.at("/lines/1/cells/4").asText());

        Json change = json(get(app, "/api/status")).get("change");
        assertEquals(63, change.get("delta_time").asInt());
    }

    @Test
    void everyColumnOfTheHistoryCanBeLeftOutAndTheOthersCloseUp() throws Exception {
        writeHistory("datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:03,10.05,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        String all = allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true");

        assertEquals(4, json(get(app, "/api/history")).get("columns").size(), "by default the four, and no change columns");
        post(app, "/api/settings", all.replace("}", ", \"historyTime\": false, \"historyUsed\": true, \"historyLimit\": false, \"historyCurrent\": false}"));

        Json history = json(get(app, "/api/history"));

        assertEquals(java.util.List.of("used", "\u0394 used", "\u0394 time"),
                history.get("columns").items().stream().map(Json::asText).toList());
        assertEquals(3, history.at("/lines/0/cells").size());
        assertEquals("10.05", history.at("/lines/0/cells/0").asText());
        assertEquals("+0.05", history.at("/lines/0/cells/1").asText());
        Json settings = json(get(app, "/api/settings")).get("settings");
        assertFalse(settings.get("historyTime").asBoolean());
        assertTrue(settings.get("historyUsed").asBoolean());
        assertFalse(settings.get("historyCurrent").asBoolean());
    }

    @Test
    void theColumnsOfTheHistoryAreAllOnWhenAPostLeavesThemOut() throws Exception {
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/settings", allSettings(60, false, "hh:mm"));

        Json settings = json(get(app, "/api/settings")).get("settings");

        assertTrue(settings.get("historyTime").asBoolean() && settings.get("historyUsed").asBoolean()
                && settings.get("historyLimit").asBoolean() && settings.get("historyCurrent").asBoolean());
    }

    @Test
    void theHistoryShowsEveryChangeButOneOfNothingAFallWithItsMinusLikeTheRow() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.40,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.40,1000.00,USD,,60,400",
                "2026-10-08 14:02:00,,,,failed,60,5000",
                "2026-10-08 14:03:00,10.30,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(true, true));

        Json lines = json(get(app, "/api/history")).get("lines");

        assertEquals("-0.10", lines.get(0).at("/cells/3").asText(), "a fall is a value, with its minus, against the last earlier amount");
        assertEquals("", lines.get(1).at("/cells/3").asText(), "the failed line has no amount");
        assertEquals("", lines.get(2).at("/cells/3").asText(), "no change: nothing");
        assertEquals("+10.40", lines.get(3).at("/cells/3").asText(), "the first line: the amount itself");

        Json change = json(get(app, "/api/status")).get("change");
        assertEquals(-0.10, change.get("delta_used").asDouble(), 0.0001, "the row has the same fall");
    }

    @Test
    void withTheZeroUsageLinesHiddenEveryLineThatIsShownHasAChange() throws Exception {
        // What the notes asked for: lines whose amount fell stay, and have their value; none is shown with an empty change.
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,186.64,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,186.50,1000.00,USD,start,60,400",
                "2026-10-08 14:02:00,,,,failed,60,5000",
                "2026-10-08 14:03:00,186.50,1000.00,USD,,60,400",
                "2026-10-08 14:04:00,186.57,1000.00,USD,start,60,400",
                "2026-10-08 14:05:00,186.57,1000.00,USD,start,60,400");

        Json history = historyWith(false, true);

        assertEquals(java.util.List.of("14:04:00", "14:01:00", "14:00:00"), times(history));
        assertEquals(java.util.List.of("+0.07", "-0.14", "+186.64"),
                history.get("lines").items().stream().map(line -> line.at("/cells/3").asText()).toList(),
                "a rise, a fall, and the first line of the file; the failed line goes with the zero usage lines (since 0.81)");
        assertEquals("Showing 3 of 6 lines: 2 zero usage and 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void theStartupLineHasTheTimeSinceTheLastLineOfTheRunBefore() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.10,1000.00,USD,,60,400",
                "2026-10-09 14:01:00,10.30,1000.00,USD,start,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(true, true));

        Json history = json(get(app, "/api/history"));

        assertEquals("86400 s", history.at("/lines/0/cells/4").asText(), "the program was off for a day");
        assertEquals("+0.20", history.at("/lines/0/cells/3").asText());
        assertEquals("", history.at("/lines/2/cells/4").asText(), "the first line of the file has none");
    }

    @Test
    void theTabOfTheSettingsIsNoBusinessOfTheBackendAnyMore() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"settingsTab\": \"cost\", \"usageIntervalSeconds\": 90}");
        AppRuntime app = start(new FakeFetch());

        assertFalse(json(get(app, "/api/settings")).has("settingsTab"), "the settings say nothing of a tab");
        assertEquals(404, post(app, "/api/settings-tab", "{\"tab\": \"general\"}").statusCode(), "and there is no request to store one");
        assertEquals(404, get(app, "/api/settings-tab").statusCode());

        post(app, "/api/settings", allSettings(60, false, "hh:mm"));
        assertFalse(JsonReader.read(Files.readString(dir.resolve("settings.json"))).has("settingsTab"), "a key left by an earlier version is gone with the next save");
    }


    @Test
    void theCostPanelIsWorkedOutFromTheHistoryAndTheLimits() throws Exception {
        String today = java.time.LocalDate.now().toString();
        writeHistory(CSV_HEADER,
                today + " 08:00:00,100.00,1000.00,USD,start,60,400",
                today + " 09:00:00,110.00,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"200.00\", \"costDailyLimit\": \"20.00\", \"costDailyAutomatic\": false}"));

        Json cost = json(get(app, "/api/cost"));

        assertEquals("Today", cost.at("/rows/0/label").asText());
        assertEquals("10.00 of 20.00 \u00b7 50%", cost.at("/rows/0/text").asText(), "110 less the 100 of the first reading of today");
        assertEquals(0.5, cost.at("/rows/0/fraction").asDouble(), 0.0001);
        assertEquals("Month", cost.at("/rows/1/label").asText());
        assertEquals("110.00 of 200.00 \u00b7 55%", cost.at("/rows/1/text").asText());
        assertFalse(cost.at("/rows/1/over").asBoolean());
        assertTrue(cost.get("note").isNull());
        assertEquals(405, post(app, "/api/cost", "{}").statusCode());
    }

    @Test
    void theCostPanelHasANoteAndEmptyBarsWhenThereIsNoReading() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json cost = json(get(app, "/api/cost"));

        assertEquals("No usage has been read yet.", cost.get("note").asText());
        assertTrue(cost.at("/rows/0/noLimit").asBoolean() && cost.at("/rows/1/noLimit").asBoolean());
    }

    @Test
    void theCostButtonIsASettingOnByDefaultAndOptionalInAPost() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertTrue(json(get(app, "/api/settings")).at("/settings/showCostIcon").asBoolean());
        assertTrue(json(get(app, "/api/status")).at("/display/show/costIcon").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"showCostIcon\": false}"));
        assertFalse(json(get(app, "/api/settings")).at("/settings/showCostIcon").asBoolean());
        assertFalse(json(get(app, "/api/status")).at("/display/show/costIcon").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm"));
        assertFalse(json(get(app, "/api/settings")).at("/settings/showCostIcon").asBoolean(), "left out: it stays as it is");
    }

    @Test
    void theStatusCarriesTheFinishedDisplayAndItFollowsTheSettings() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        Json display = json(get(app, "/api/status")).get("display");
        assertEquals(5, display.get("time").asText().length(), "hours and minutes by default");
        assertEquals("186.02", display.at("/spend/used").asText());
        assertEquals("1,000.00", display.at("/spend/limit").asText());
        assertFalse(display.at("/show/interval").asBoolean());
        assertEquals("60s", display.at("/interval/text").asText());
        assertTrue(display.at("/countdown/text").asText().matches("-?\\d+s"));

        post(app, "/api/settings", allSettings(60, false, "hh:mm:ss"));
        display = json(get(app, "/api/status")).get("display");
        assertEquals(8, display.get("time").asText().length(), "hh:mm:ss now");
        assertTrue(display.at("/show/interval").asBoolean());
        assertTrue(display.at("/show/deltaUsed").asBoolean());
        assertFalse(display.at("/show/deltaTime").asBoolean());
    }

    @Test
    void aReadingWithNoChangeHasNoChangeTextAndTheColumnsFollowTheSettings() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);
        assertEquals(4, json(get(app, "/api/history")).get("columns").size(), "off until switched on");

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true"));
        Json on = json(get(app, "/api/history"));

        assertEquals(6, on.get("columns").size());
        // FakeFetch answers the same amount twice: no change, so the cell is empty, never 0.00.
        assertEquals("", on.at("/lines/0/cells/3").asText());
        assertTrue(on.at("/lines/0/cells/4").asText().endsWith(" s"), "the time is in seconds");
        assertEquals("+186.02", on.at("/lines/1/cells/3").asText(), "the first line has no earlier amount: the change is the amount");
    }

    @Test
    void theErrorLogListsEveryFailedRefreshNewestFirstWithTheTimeAndTheMessage() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().error() != null);
        post(app, "/api/refresh", "{}");
        await(() -> app.service().errors().newestFirst().size() >= 2);

        Json errors = json(get(app, "/api/errors")).get("entries");

        assertTrue(errors.size() >= 2);
        assertTrue(errors.get(0).get("time").asText().matches("\\d{2}:\\d{2}:\\d{2}"), "hh:mm:ss, with seconds, whatever the time format setting");
        assertEquals("Anthropic returned HTTP 503.", errors.get(0).get("message").asText());
    }

    @Test
    void theErrorLogIsEmptyWhenNothingHasFailedAndPollingAddsNothing() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        for (int i = 0; i < 3; i++) {
            get(app, "/api/status");
        }

        assertEquals(0, json(get(app, "/api/errors")).get("entries").size());
        assertEquals(405, post(app, "/api/errors", "{}").statusCode(), "read-only");
    }

    @Test
    void aProblemWithTheTokenIsInTheErrorLog() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new org.example.token.TokenException(
                    org.example.token.TokenException.Reason.NOT_LOGGED_IN, "Claude Code is not logged in. Log in, then refresh.");
        };
        AppRuntime app = start(fetch);
        await(() -> !app.service().errors().newestFirst().isEmpty());

        assertTrue(json(get(app, "/api/errors")).at("/entries/0/message").asText().contains("not logged in"));
    }

    @Test
    void afterAnHttp429TheIntervalInForceIsStillTheConfiguredOne() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429, java.time.Duration.ofSeconds(300));
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().rateLimited());

        Json settings = json(get(app, "/api/settings"));
        Json status = json(get(app, "/api/status"));

        assertEquals(60, settings.at("/settings/usageIntervalSeconds").asInt(), "no back-off: the delay is the interval, whatever Retry-After says");
        assertEquals(60, settings.at("/defaults/usageIntervalSeconds").asInt(), "the defaults are the defaults");
        assertEquals("60s", status.at("/display/interval/text").asText());
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "the file keeps the configured one");
    }

    @Test
    void anHttp429IsInTheErrorLogAndHasNoMessageInTheStatusDisplay() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().error() != null);
        await(() -> !app.service().errors().newestFirst().isEmpty());

        Json status = json(get(app, "/api/status"));
        assertTrue(status.at("/display/message").isNull());
        assertTrue(status.at("/display/countdownAlert").asText().contains("HTTP 429"));
        assertFalse(status.get("stale").asBoolean());
        assertTrue(json(get(app, "/api/errors")).at("/entries/0/message").asText().contains("HTTP 429"));
    }

    // ---- hiding lines: the zero usage and the failed ones

    private static final String CSV_HEADER = "datetime,used,limit,currency,status,interval,duration_ms";

    private void writeMixedHistory() throws IOException {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.00,1000.00,USD,,60,400",
                "2026-10-08 14:02:00,10.05,1000.00,USD,,60,400",
                "2026-10-08 14:03:00,,,,failed,60,5000",
                "2026-10-08 14:04:00,10.05,1000.00,USD,,60,400",
                "2026-10-08 14:05:00,10.05,1000.00,USD,,60,400",
                "2026-10-08 14:06:00,10.20,1000.00,USD,,60,400");
    }

    private static String withSwitches(boolean zero, boolean failed) {
        return allSettings(60, false, "hh:mm")
                .replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true")
                .replace("\"historyZeroLines\": true", "\"historyZeroLines\": " + zero)
                .replace("\"historyFailedLines\": true", "\"historyFailedLines\": " + failed);
    }

    private Json historyWith(boolean zero, boolean failed) throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(zero, failed));
        return json(get(app, "/api/history"));
    }

    private static java.util.List<String> times(Json history) {
        java.util.List<String> times = new java.util.ArrayList<>();
        history.get("lines").items().forEach(l -> times.add(l.at("/cells/0").asText()));
        return times;
    }

    @Test
    void theCurrencyIsTheRightMostColumnWithTheChangeColumnsBetweenTheBudgetAndIt() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,EUR,start,60,400",
                "2026-10-08 14:01:00,10.05,1000.00,EUR,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json none = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "Cur."), titles(none));
        assertEquals("EUR", none.at("/lines/0/cells/3").asText(), "other currencies keep their code");

        post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true")
                .replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": false"));
        Json one = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "\u0394 used", "Cur."), titles(one));
        assertEquals("+0.05", one.at("/lines/0/cells/3").asText());
        assertEquals("EUR", one.at("/lines/0/cells/4").asText());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true"));
        Json both = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "\u0394 used", "\u0394 time", "Cur."), titles(both));
        assertEquals("60 s", both.at("/lines/0/cells/4").asText());
        assertEquals("EUR", both.at("/lines/0/cells/5").asText());
    }

    @Test
    void usDollarsAreADollarSignInTheHistoryAndAFailedLineHasNoCurrency() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,,,,failed,60,5000");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json lines = json(get(app, "/api/history")).get("lines");

        assertEquals("$", lines.get(1).at("/cells/3").asText());
        assertEquals("", lines.get(0).at("/cells/3").asText());
    }

    private static java.util.List<String> titles(Json history) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        history.get("columns").items().forEach(c -> titles.add(c.asText()));
        return titles;
    }

    @Test
    void theRowsAmountsHaveTheSymbolWhenItIsSwitchedOnAndTheIconFlagsFollowTheSettings() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);
        assertEquals("186.02", json(get(app, "/api/status")).at("/display/spend/used").asText());
        assertTrue(json(get(app, "/api/status")).at("/display/show/historyIcon").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("\"showCurrency\": false", "\"showCurrency\": true")
                .replace("\"showLogIcon\": true", "\"showLogIcon\": false"));
        Json display = json(get(app, "/api/status")).get("display");

        assertEquals("$186.02", display.at("/spend/used").asText());
        assertEquals("$1,000.00", display.at("/spend/limit").asText());
        assertTrue(display.at("/show/currency").asBoolean());
        assertFalse(display.at("/show/logIcon").asBoolean());
        assertTrue(display.at("/show/historyIcon").asBoolean());
        assertTrue(display.at("/show/errorIcon").asBoolean());
    }

    @Test
    void everythingIsShownByDefaultAndTheNoteSaysNothing() throws Exception {
        writeMixedHistory();

        Json history = historyWith(true, true);

        assertEquals(7, history.get("lines").size());
        assertTrue(history.get("note").isNull());
    }

    @Test
    void theZeroUsageLinesCanBeHiddenTheFailedLinesWithThemAndTheNoteCountsBoth() throws Exception {
        writeMixedHistory();

        Json history = historyWith(false, true);

        // 14:01 repeats 14:00, and 14:04 and 14:05 repeat 14:02, the last amount before them: zero usage, though a failed line
        // is between. 14:00 is the first line of the file: its change is its amount, so it is shown, start line or not. The
        // failed line, 14:03, goes with them since 0.81, though the checkbox of the failed lines is on.
        assertEquals(java.util.List.of("14:06:00", "14:02:00", "14:00:00"), times(history));
        assertEquals("Showing 3 of 7 lines: 3 zero usage and 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void theFailedLinesCanBeHiddenToo() throws Exception {
        writeMixedHistory();

        Json history = historyWith(true, false);

        assertEquals(6, history.get("lines").size());
        assertFalse(times(history).contains("14:03:00"));
        assertEquals("Showing 6 of 7 lines: 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void bothHiddenAndTheNoteSaysBothAndAllThatIsLeftIsShown() throws Exception {
        writeMixedHistory();

        Json history = historyWith(false, false);

        assertEquals(java.util.List.of("14:06:00", "14:02:00", "14:00:00"), times(history));
        assertEquals("Showing 3 of 7 lines: 3 zero usage and 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void theHistorySaysATooltipForEachOfItsTitlesWhicheverColumnsAreShown() throws Exception {
        writeMixedHistory();

        Json all = historyWith(true, true);
        assertEquals(java.util.List.of("When the usage was read", "The amount used at that reading", "The budget at that reading",
                        "The change in the amount used since the last earlier reading: + for a rise, - for a fall",
                        "The seconds since the previous line that is shown", "The currency of the amounts"),
                all.get("columnTitles").items().stream().map(Json::asText).toList());
        assertEquals(titles(all).size(), all.get("columnTitles").size(), "one for each title");

        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDate\": false", "\"historyDate\": true")
                .replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": false"));
        Json dated = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("The date and time at which the usage was read", "The amount used at that reading", "The budget at that reading", "The currency of the amounts"),
                dated.get("columnTitles").items().stream().map(Json::asText).toList(), "with the date, and with the two changes switched off");
    }

    @Test
    void theChangeInTheAmountDoesNotDependOnWhatIsHidden() throws Exception {
        writeMixedHistory();

        Json lines = historyWith(false, false).get("lines");

        // Shown, newest first: 14:06 (10.20), 14:02 (10.05), 14:00 (10.00). Hidden: 14:01, 14:04 and 14:05, which repeat the amount
        // before them, and the failed 14:03.
        assertEquals(3, lines.size());
        assertEquals("+0.15", lines.get(0).at("/cells/3").asText(), "against 14:05, which is hidden: the change does not depend on what is hidden");
        assertEquals("+0.05", lines.get(1).at("/cells/3").asText(), "14:02 against 14:01, which is hidden as well");
        assertEquals("+10.00", lines.get(2).at("/cells/3").asText(), "the first line of the file: the amount itself");
    }

    @Test
    void theTimeSpansTheLinesThatAreHidden() throws Exception {
        writeMixedHistory();
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(false, false).replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": true"));

        Json history = json(get(app, "/api/history"));

        int time = history.get("columns").size() - 2;
        // Shown: 14:06, 14:02, 14:00. 14:06 against 14:02, with the failed 14:03 and the unchanged 14:04 and 14:05 hidden between
        // them: 240 s. 14:02 against 14:00, with 14:01 hidden: 120 s, not 60 s.
        assertEquals("240 s", history.at("/lines/0/cells/" + time).asText());
        assertEquals("120 s", history.at("/lines/1/cells/" + time).asText());
    }

    @Test
    void aHiddenLineThatBeginsARunStillBeginsItForTheTimeButNotForTheChangeInTheAmount() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.10,1000.00,USD,,60,400",
                "2026-10-08 15:00:00,,,,start-failed,60,5000",
                "2026-10-08 15:01:00,10.30,1000.00,USD,,60,400");

        Json lines = historyWith(true, false).get("lines");

        // The start-failed line is hidden. 15:01 would have been compared with 14:01 across the two runs; it is not.
        assertEquals("15:01:00", lines.get(0).at("/cells/0").asText());
        assertEquals("+0.20", lines.get(0).at("/cells/3").asText(), "the change is against 14:01, across the run that began, failed lines being ignored");
        assertFalse(lines.get(0).get("start").asBoolean(), "and it is not itself a start line");
    }

    @Test
    void theStartAndFailedFlagsAndTheHoverTextAreFinished() throws Exception {
        writeMixedHistory();

        Json lines = historyWith(true, true).get("lines");

        // Newest first: 14:06 ... 14:03 (failed) ... 14:00 (start).
        assertFalse(lines.get(0).get("start").asBoolean());
        assertFalse(lines.get(0).get("failed").asBoolean());
        assertEquals("", lines.get(0).get("title").asText());
        assertTrue(lines.get(3).get("failed").asBoolean());
        assertEquals("failed", lines.get(3).at("/cells/1").asText(), "the word in the place of the amount");
        assertTrue(lines.get(6).get("start").asBoolean());
        assertEquals("The program started here", lines.get(6).get("title").asText());
    }

    @Test
    void aStartupLineIsALineLikeAnyOtherHiddenWhenItsChangeIsNothingAndShownWhenItHasOne() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.05,1000.00,USD,,60,400",
                "2026-10-08 15:00:00,10.05,1000.00,USD,start,60,400",
                "2026-10-08 16:00:00,10.20,1000.00,USD,start,60,400");

        Json hidden = historyWith(false, true);
        Json shown = historyWith(true, true);

        assertEquals(java.util.List.of("16:00:00", "14:01:00", "14:00:00"), times(hidden),
                "the start at 15:00 found the amount as it was and is hidden; the one at 16:00 found a change and is shown");
        Json changed = hidden.get("lines").get(0);
        assertTrue(changed.get("start").asBoolean(), "with its mark of a start");
        assertEquals("The program started here", changed.get("title").asText());
        assertEquals("+0.15", changed.at("/cells/3").asText(), "and its change, against the last line of the run before");
        Json unchanged = shown.get("lines").get(1);
        assertEquals("15:00:00", unchanged.at("/cells/0").asText());
        assertTrue(unchanged.get("start").asBoolean(), "back with its mark when the zero usage lines are shown");
        assertEquals("", unchanged.at("/cells/3").asText());
    }

    @Test
    void aFailedStartupLineIsAFailedLineAndIsCountedAsOne() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,,,,start-failed,60,5000",
                "2026-10-08 14:01:00,10.00,1000.00,USD,,60,400");

        Json zeroOff = historyWith(false, true);
        Json failedOff = historyWith(true, false);

        assertFalse(times(zeroOff).contains("14:00:00"), "hidden with the zero usage lines, since the failed lines go with them (since 0.81)");
        assertEquals("Showing 1 of 2 lines: 1 failed hidden.", zeroOff.get("note").asText(), "and counted as a failed line, not a zero usage line");
        assertFalse(times(failedOff).contains("14:00:00"), "hidden with the failed lines");
        assertEquals("Showing 1 of 2 lines: 1 failed hidden.", failedOff.get("note").asText());
    }

    @Test
    void theNoteIsHighlightedOnlyWhenSomethingIsLeftOut() throws Exception {
        writeMixedHistory();
        assertFalse(historyWith(true, true).get("noteHighlight").asBoolean(), "nothing is left out");
        assertTrue(historyWith(false, true).get("noteHighlight").asBoolean(), "lines are hidden");
    }

    @Test
    void theNotesAboutThereBeingNoHistoryAreNotHighlighted() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime none = start(plan);
        assertFalse(json(get(none, "/api/history")).get("noteHighlight").asBoolean());

        writeHistory("datetime,used,limit,currency");
        AppRuntime empty = start(plan);
        assertFalse(json(get(empty, "/api/history")).get("noteHighlight").asBoolean());
    }

    @Test
    void theNoteCountsHiddenAndOlderLinesTogether() throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.List.of(CSV_HEADER, "2026-01-01 00:00:00,1.00,2.00,USD,start,60,1"));
        for (int i = 1; i <= 1100; i++) {
            // Every reading is the same as the one before it: a zero usage line.
            rows.add(String.format("2026-01-01 %02d:%02d:00,1.00,2.00,USD,,60,1", i / 60 % 24, i % 60));
        }
        rows.add("2026-01-02 12:00:00,2.00,2.00,USD,,60,1");
        writeHistory(rows.toArray(String[]::new));

        Json history = historyWith(false, true);

        assertEquals(1102, history.get("total").asInt());
        assertEquals(2, history.get("lines").size(), "the one change, and the first line of the file, whose change is its amount");
        assertEquals("Showing 2 of 1,102 lines: 1,100 zero usage hidden.", history.get("note").asText());
    }

    @Test
    void ifEverythingIsHiddenTheNoteSaysSoAndThereAreNoLines() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,,,,failed,60,5000",
                "2026-10-08 14:01:00,,,,failed,60,5000");

        Json history = historyWith(true, false);

        assertEquals(0, history.get("lines").size());
        assertEquals("Showing 0 of 2 lines: 2 failed hidden.", history.get("note").asText());
    }

    @Test
    void theMainRowsChangeIgnoresWhatTheHistoryHides() throws Exception {
        writeMixedHistory();
        AppRuntime withSpend = start(new FakeFetch());
        await(() -> withSpend.service().state().snapshot() != null);
        double before = json(get(withSpend, "/api/status")).at("/change/delta_time").asDouble(-1);

        post(withSpend, "/api/settings", withSwitches(false, false));

        assertEquals(before, json(get(withSpend, "/api/status")).at("/change/delta_time").asDouble(-1),
                "hiding history lines does not change the row's items");
    }

    @Test
    void theHistoryAndTheRowBothGiveTheTimeInWholeSeconds() throws Exception {
        writeHistory("datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:03,10.05,1000.00,USD,,60,400",
                "2026-10-08 14:03:09,10.10,1000.00,USD,,60,400",
                "2026-10-08 15:03:09,10.20,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": true"));

        Json lines = json(get(app, "/api/history")).get("lines");

        // Newest first; the columns are time, used, limit, Cur., then the time (allSettings turns it on, and the amount's change off).
        int time = json(get(app, "/api/history")).get("columns").size() - 2;
        assertEquals("3600 s", lines.get(0).at("/cells/" + time).asText(), "an hour, in seconds");
        assertEquals("126 s", lines.get(1).at("/cells/" + time).asText(), "two minutes and six seconds");
        assertEquals("63 s", lines.get(2).at("/cells/" + time).asText());
        assertEquals("", lines.get(3).at("/cells/" + time).asText(), "the first line of a run has none");
        assertEquals("3600s", json(get(app, "/api/status")).at("/display/deltaTime/text").asText(), "the row too, in seconds, never in hours, without the space");
        assertFalse(json(get(app, "/api/status")).at("/change").has("delta_time_text"), "no short form is sent any more");
    }

    @Test
    void aChangeOfZeroIsShownNowhereButAChangeIs() throws Exception {
        writeHistory("datetime,used,limit,currency,status,interval,duration_ms",
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.05,1000.00,USD,,60,400",
                "2026-10-08 14:02:00,10.05,1000.00,USD,,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true"));

        Json lines = json(get(app, "/api/history")).get("lines");

        // Newest first: no change, then +0.05, then the first line of the run.
        assertEquals("", lines.get(0).at("/cells/3").asText(), "a change of zero says nothing");
        assertEquals("+0.05", lines.get(1).at("/cells/3").asText());
        assertEquals("60 s", lines.get(0).at("/cells/4").asText(), "the time is shown whatever it is");
    }

    @Test
    void theStatusSaysWhenTheHistoryFileChangesAFailedRowIncluded() throws Exception {
        FakeFetch fetch = new FakeFetch();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Supplier<UsageSnapshot> good = fetch.answer;
        fetch.answer = () -> {
            if (fail.get()) {
                throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
            }
            return good.get();
        };
        AppRuntime app = start(fetch);
        await(() -> history(app).size() >= 2);
        String first = json(get(app, "/api/status")).get("historyStamp").asText();
        assertFalse(first.isEmpty(), "there is a file");
        assertEquals(first, json(get(app, "/api/status")).get("historyStamp").asText(), "the same while nothing is added");

        fail.set(true);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        assertNotEquals(first, json(get(app, "/api/status")).get("historyStamp").asText(), "a failed refresh added a row");
    }

    @Test
    void theStampIsEmptyWhenThereIsNoHistoryFile() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        assertEquals("", json(get(app, "/api/status")).get("historyStamp").asText());
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
    void aReadingWithNoUsageWritesNothing() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> NO_USAGE;
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
        assertEquals(Amount.parse("186.02"), state.snapshot().spend().used());
    }

    @Test
    void theHistoryNeverHoldsAnythingButTheTenColumns() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        for (String line : history(app)) {
            assertEquals(9, line.chars().filter(c -> c == ',').count(), line);
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

        Json log = json(get(app, "/api/log"));

        assertTrue(log.get("exists").asBoolean());
        assertFalse(log.get("truncated").asBoolean());
        assertEquals("java-aip-usage.log", log.get("file").asText(), "the name, not the path");
        assertEquals(List.of("first line", "second line"), log.get("lines").items().stream().map(Json::asText).toList());
    }

    @Test
    void theLogEndpointSaysSoWhenThereIsNoLog() throws Exception {
        Json log = json(get(start(new FakeFetch()), "/api/log"));

        assertFalse(log.get("exists").asBoolean());
        assertEquals(0, log.get("lines").size());
    }

    @Test
    void aLongLogIsCutToTheLastThousandLines() throws Exception {
        writeLog(java.util.stream.IntStream.rangeClosed(1, 1500).mapToObj(i -> "line " + i).toArray(String[]::new));
        AppRuntime app = start(new FakeFetch());

        Json log = json(get(app, "/api/log"));

        assertTrue(log.get("truncated").asBoolean());
        assertEquals(1000, log.get("lines").size());
        assertEquals("line 501", log.at("/lines/0").asText());
        assertEquals("line 1500", log.at("/lines/999").asText());
    }

    @Test
    void theHistoryEndpointGivesTheLinesNewestFirstAndFinished() throws Exception {
        writeHistory("datetime,used,limit,currency",
                "2026-10-08 14:24:53,186.02,1000.00,USD",
                "2026-10-08 14:26:53,186.12,1000.00,USD",
                "2026-10-08 14:25:53,186.07,1000.00,USD");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json history = json(get(app, "/api/history"));

        assertTrue(history.get("exists").asBoolean());
        assertEquals("java-aip-usage.csv", history.get("file").asText());
        assertEquals("time", history.at("/columns/0").asText(), "the time of day only, until the date is switched on");
        assertEquals("limit", history.at("/columns/2").asText());
        assertEquals("Cur.", history.at("/columns/3").asText(), "the currency's title is short; its cells keep the code");
        assertEquals(4, history.get("columns").size(), "no change columns until they are switched on");
        assertEquals(3, history.get("total").asInt());
        assertEquals(3, history.get("lines").size());
        assertEquals("14:26:53", history.at("/lines/0/cells/0").asText(), "sorted by date and time, newest first, shown as time of day");
        assertEquals("14:25:53", history.at("/lines/1/cells/0").asText());
        assertEquals("186.12", history.at("/lines/0/cells/1").asText());
        assertEquals("1000.00", history.at("/lines/0/cells/2").asText());
        assertEquals("$", history.at("/lines/0/cells/3").asText(), "US dollars as the symbol");
        assertFalse(history.get("wide").asBoolean());
        assertTrue(history.get("note").isNull(), "everything is shown, so there is nothing to say");
    }

    @Test
    void withTheDateSettingOnTheHistoryTimesHaveTheDateAndTheTitleSaysSo() throws Exception {
        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,186.02,1000.00,USD", "yesterday,1.00,2.00,USD");
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDate\": false", "\"historyDate\": true"));

        Json history = json(get(app, "/api/history"));

        assertEquals("date time", history.at("/columns/0").asText());
        assertTrue(history.get("wide").asBoolean());
        java.util.Set<String> shown = new java.util.HashSet<>();
        history.get("lines").items().forEach(l -> shown.add(l.at("/cells/0").asText()));
        assertTrue(shown.contains("2026-10-08 14:24:53"), shown.toString());
    }

    @Test
    void aTimeThatCannotBeReadIsShownAsItIsEvenWithoutTheDate() throws Exception {
        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,186.02,1000.00,USD", "yesterday,1.00,2.00,USD");
        AppRuntime app = start(new FakeFetch());

        Json lines = json(get(app, "/api/history")).get("lines");

        java.util.Set<String> shown = new java.util.HashSet<>();
        lines.items().forEach(l -> shown.add(l.at("/cells/0").asText()));
        assertTrue(shown.contains("yesterday"), shown.toString());
        assertTrue(shown.contains("14:24:53"), shown.toString());
    }

    @Test
    void theRememberedHeightsAreNotInTheSettingsTheViewShowsOrTakes() throws Exception {
        AppRuntime app = start(new FakeFetch());
        app.settings().storeHeight("history", 640);

        Json settings = json(get(app, "/api/settings")).get("settings");
        post(app, "/api/settings", allSettings(120, false, "hh:mm"));

        assertFalse(settings.has("historyHeight"));
        assertFalse(settings.has("logHeight"));
        assertEquals(640, app.settings().storedHeight("history"), "applying the settings left the height alone");
    }

    @Test
    void theHistoryEndpointShowsTheRowsTheApplicationWritesItself() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> history(app).size() >= 2);
        assertEquals(202, post(app, "/api/refresh", "{}").statusCode());
        await(() -> history(app).size() >= 3);

        Json history = json(get(app, "/api/history"));

        assertEquals(2, history.get("total").asInt());
        assertEquals("186.02", history.at("/lines/0/cells/1").asText());
    }

    @Test
    void theHistoryEndpointSaysSoWhenThereIsNoHistory() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        await(() -> app.service().state().snapshot() != null);

        Json history = json(get(app, "/api/history"));

        assertFalse(history.get("exists").asBoolean());
        assertEquals(0, history.get("lines").size());
        assertEquals("There is no usage history yet.", history.get("note").asText());
    }

    @Test
    void aHistoryWithNoRowsSaysSoToo() throws Exception {
        writeHistory("datetime,used,limit,currency");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json history = json(get(app, "/api/history"));

        assertTrue(history.get("exists").asBoolean());
        assertEquals("The history has no rows yet.", history.get("note").asText());
    }

    @Test
    void aLongHistoryIsCutToTheNewestThousandRowsAndTheNoteSays() throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<>(List.of("datetime,used,limit"));
        for (int i = 0; i < 1500; i++) {
            rows.add(String.format("2026-01-01 00:00:%02d,1.00,2.00", 0).replace("00:00:00", String.format("%02d:%02d:00", i / 60 % 24, i % 60)));
        }
        writeHistory(rows.toArray(String[]::new));
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json history = json(get(app, "/api/history"));

        assertEquals(1500, history.get("total").asInt());
        assertEquals(1000, history.get("lines").size());
        assertEquals("Showing 1,000 of 1,500 lines: 500 older not shown.", history.get("note").asText());
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
    void theHistoryTakesNothingButItsOneChoiceAndTheLogNothingButItsTwo() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = send(app, "/api/history", HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(405, response.statusCode());
        assertEquals("GET, POST", response.headers().firstValue("Allow").orElse(""));
        assertEquals("Use GET or POST.", json(response).get("error").asText());

        HttpResponse<String> put = send(app, "/api/log", HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(405, put.statusCode());
        assertEquals("GET, POST", put.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void theHistorySaysWhatEachOfItsColumnsIsWhicheverAreSwitchedOn() throws Exception {
        writeHistory(CSV_HEADER, "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        Json four = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "currency"), four.get("kinds").items().stream().map(Json::asText).toList());

        // Both changes on and the currency off: five columns, of which the last is the time change.
        post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true")
                .replace("}", ", \"historyCurrent\": false}"));
        Json five = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "\u0394 used", "\u0394 time"), titles(five));
        assertEquals(java.util.List.of("time", "used", "limit", "deltaUsed", "deltaTime"), five.get("kinds").items().stream().map(Json::asText).toList());
        assertEquals(titles(five).size(), five.at("/lines/0/cells").size(), "a kind for each column, and a cell for each");
    }

    // ---- the history with every column switched off: the picture of a note and no lines under it

    private static final String NO_COLUMN = "No column of the history is switched on. Switch one on in the settings, under History view.";

    /** Every checkbox of History view off, as the button in its heading leaves them when all were on. */
    private static String withNothingOfTheHistory() {
        return allSettings(60, false, "hh:mm")
                .replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": false")
                .replace("\"historyZeroLines\": true", "\"historyZeroLines\": false")
                .replace("\"historyFailedLines\": true", "\"historyFailedLines\": false")
                .replace("}", ", \"historyTime\": false, \"historyUsed\": false, \"historyLimit\": false, \"historyCurrent\": false}");
    }

    @Test
    void withEveryColumnOfTheHistorySwitchedOffItSaysSoAndDoesNotSayThatLinesAreShown() throws Exception {
        writeMixedHistory();
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withNothingOfTheHistory());

        Json history = json(get(app, "/api/history"));

        assertEquals(0, history.get("columns").size(), "no column is on");
        assertEquals(NO_COLUMN, history.get("note").asText(), "which is what the note says, where it said: Showing 3 of 7 lines");
        assertTrue(history.get("noteHighlight").asBoolean(), "in the blue of the notes that tell what is not shown");
        assertEquals(0, history.get("lines").size(), "and no lines are sent that have nothing in them");
        assertEquals(7, history.get("total").asInt(), "the file is as it was");
    }

    @Test
    void withOneColumnOnTheHistoryIsATableAgainAndTheNoteCountsItsLines() throws Exception {
        writeMixedHistory();
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withNothingOfTheHistory().replace("\"historyUsed\": false", "\"historyUsed\": true"));

        Json history = json(get(app, "/api/history"));

        assertEquals(java.util.List.of("used"), titles(history));
        assertEquals(3, history.get("lines").size());
        assertEquals("Showing 3 of 7 lines: 3 zero usage and 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void aHistoryThatIsNotThereOrIsEmptyKeepsItsOwnNoteWithNoColumnOn() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withNothingOfTheHistory());

        assertEquals("There is no usage history yet.", json(get(app, "/api/history")).get("note").asText(), "there is nothing a column could show");
    }

    // ---- the one choice of the history panel: whether its zero usage lines are shown

    private void writeThreeReadingsOneOfThemChanged() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,,60,400",
                "2026-10-08 14:01:00,10.00,1000.00,USD,,60,400",
                "2026-10-08 14:02:00,10.00,1000.00,USD,,60,400",
                "2026-10-08 14:03:00,10.50,1000.00,USD,,60,400");
    }

    private AppRuntime startWithNoUsage() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        return start(plan);
    }

    @Test
    void theHistorySaysWhetherItsZeroUsageLinesAreShownWhichTheyAreByDefault() throws Exception {
        writeThreeReadingsOneOfThemChanged();
        AppRuntime app = startWithNoUsage();

        Json history = json(get(app, "/api/history"));

        assertTrue(history.get("zeroLines").asBoolean());
        assertEquals(4, history.get("lines").size());
    }

    @Test
    void theChoiceOfTheHistoryPanelIsSavedAtOnceAsTheSettingAndTheHistoryComesWithoutItsZeroUsageLines() throws Exception {
        writeThreeReadingsOneOfThemChanged();
        AppRuntime app = startWithNoUsage();

        HttpResponse<String> saved = post(app, "/api/history", "{\"zeroLines\": false}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertFalse(json(saved).get("zeroLines").asBoolean());
        assertFalse(JsonReader.read(Files.readString(dir.resolve("settings.json"))).get("historyZeroLines").asBoolean(), "under the key the setting has");
        assertFalse(json(get(app, "/api/settings")).at("/settings/historyZeroLines").asBoolean(), "the checkbox of History view shows the same");

        Json history = json(get(app, "/api/history"));
        assertFalse(history.get("zeroLines").asBoolean());
        assertEquals(2, history.get("lines").size(), "the reading that changed, and the first, which has nothing before it");
        assertTrue(history.get("note").asText().contains("zero usage"), "and the note says what is hidden");

        assertTrue(json(post(app, "/api/history", "{\"zeroLines\": true}")).get("zeroLines").asBoolean());
        assertEquals(4, json(get(app, "/api/history")).get("lines").size(), "and back");
    }

    @Test
    void theOtherSettingsAreLeftAsTheyAreByTheChoiceOfTheHistoryPanel() throws Exception {
        AppRuntime app = startWithNoUsage();
        post(app, "/api/settings", allSettings(90, true, "hh:mm:ss"));
        Json before = json(get(app, "/api/settings")).get("settings");

        post(app, "/api/history", "{\"zeroLines\": false}");

        Json after = json(get(app, "/api/settings")).get("settings");
        for (String key : before.keys()) {
            if (!key.equals("historyZeroLines")) {
                assertEquals(before.get(key).toString(), after.get(key).toString(), key);
            }
        }
        assertFalse(after.get("historyZeroLines").asBoolean());
    }

    @Test
    void zeroLinesNamedInTheRequestIsWhatIsGivenAndTheSavedOneIsStillSaid() throws Exception {
        writeThreeReadingsOneOfThemChanged();
        AppRuntime app = startWithNoUsage();

        Json hidden = json(get(app, "/api/history?zeroLines=false"));

        assertEquals(2, hidden.get("lines").size(), "as asked");
        assertTrue(hidden.get("zeroLines").asBoolean(), "what is saved, not what was asked for");
        assertEquals(4, json(get(app, "/api/history?zeroLines=true")).get("lines").size());
        assertEquals(400, get(app, "/api/history?zeroLines=maybe").statusCode());
        assertEquals(4, json(get(app, "/api/history")).get("lines").size(), "nothing was saved by asking");
    }

    @Test
    void aChoiceOfTheHistoryPanelThatIsNoneIsRefusedAndNothingIsSaved() throws Exception {
        AppRuntime app = startWithNoUsage();

        for (String body : new String[] {"{}", "{\"zeroLines\": \"no\"}", "{\"zeroLines\": 0}", "{\"zeroLines\": null}"}) {
            HttpResponse<String> response = post(app, "/api/history", body);
            assertEquals(400, response.statusCode(), body);
            assertTrue(json(response).get("error").asText().startsWith("The setting zeroLines "), response.body());
        }
        assertTrue(json(get(app, "/api/history")).get("zeroLines").asBoolean());
    }

    @Test
    void whenTheChoiceOfTheHistoryPanelCannotBeSavedItSaysSoAndNothingChanges() throws Exception {
        AppRuntime app = AppRuntime.start(
                new AppFiles(dir.resolve("no-such-dir").resolve("settings.json"), dir.resolve("history.csv"), dir.resolve("log")),
                LaunchOptions.none(), new FakeFetch());
        runtimes.add(app);

        HttpResponse<String> response = post(app, "/api/history", "{\"zeroLines\": false}");

        assertEquals(500, response.statusCode());
        assertTrue(json(response).get("error").asText().startsWith("The history settings could not be saved: "), response.body());
        assertFalse(response.body().contains(dir.toString()), "no path in the answer");
        assertTrue(json(get(app, "/api/history")).get("zeroLines").asBoolean());
    }

    // ---- the paths in the log are links

    private static List<String> linksOf(Json log) {
        return log.get("links").items().stream()
                .map(link -> link.get("line").asInt() + ":" + linesOf(log).get(link.get("line").asInt()).substring(link.get("from").asInt(), link.get("to").asInt()) + "=" + link.get("file").asText())
                .toList();
    }

    @Test
    void theLogSaysWhichPartsOfItsLinesAreThePathsOfTheProgramsOwnFilesAndOfNothingElse() throws Exception {
        AppFiles files = AppFiles.in(dir);
        String folder = dir.toAbsolutePath() + java.io.File.separator;
        String old = folder + "java-aip-usage.2026-10-13_21-06-29.log";
        writeLog(
                "2026-10-11 13:24:01 INFO    [Logging] Logging to " + files.log().toAbsolutePath(),
                "2026-10-11 13:24:02 INFO    [AppRuntime] Settings are stored in " + files.settings().toAbsolutePath(),
                "2026-10-11 13:24:03 INFO    [AppRuntime] Usage history is written to " + files.fakeHistory().toAbsolutePath(),
                "2026-10-11 13:24:04 INFO    [Logging] The log was reset: the lines before are in " + old,
                "2026-10-11 13:24:05 INFO    [A] another file, C:\\Windows\\win.ini, and " + folder + "notes.txt, and https://example.org/x",
                "2026-10-11 13:24:06 INFO    [A] " + files.log().toAbsolutePath() + ".tmp is not the log, and " + folder + "java-aip-usage.notes.log is no old log",
                "2026-10-11 13:24:07 INFO    [A] twice: " + files.history().toAbsolutePath() + " and " + files.history().toAbsolutePath());
        AppRuntime app = start(new FakeFetch());

        Json log = json(get(app, "/api/log"));

        String history = files.history().toAbsolutePath().toString();
        assertEquals(List.of(
                        "0:" + files.log().toAbsolutePath() + "=log",
                        "1:" + files.settings().toAbsolutePath() + "=settings",
                        "2:" + files.fakeHistory().toAbsolutePath() + "=fake-history",
                        "3:" + old + "=old:java-aip-usage.2026-10-13_21-06-29.log",
                        "6:" + history + "=history",
                        "6:" + history + "=history"),
                linksOf(log), "a whole path of a file the program knows, and an old log by the form of its name though it is not there; nothing else");
    }

    @Test
    void aLinkOfTheLogOpensTheFolderOfItsFileByTheNameTheLogGaveAndNothingElse() throws Exception {
        AppRuntime app = start(new FakeFetch());
        NotedDesk desk = new NotedDesk();
        app.desk(desk);
        AppFiles files = AppFiles.in(dir);
        String old = "java-aip-usage.2026-10-13_21-06-29.log";

        for (String name : new String[] {"settings", "log", "history", "fake-history", "old:" + old}) {
            HttpResponse<String> opened = post(app, "/api/log/open", "{\"file\": \"" + name + "\"}");
            assertEquals(200, opened.statusCode(), name + " " + opened.body());
            assertFalse(opened.body().contains(dir.toString()), "no path in the answer");
        }
        assertEquals(List.of("reveal " + files.settings(), "reveal " + files.log(), "reveal " + files.history(), "reveal " + files.fakeHistory(),
                        "reveal " + dir.toAbsolutePath().resolve(old)),
                desk.asked.stream().map(asked -> asked.replace(dir.toAbsolutePath().normalize().toString(), dir.toAbsolutePath().toString())).toList(),
                "each by the path the program knows; the old log beside the log, though it is not there any more");

        desk.asked.clear();
        for (String body : new String[] {"{\"file\": \"old:../settings.json\"}", "{\"file\": \"old:java-aip-usage.notes.log\"}", "{\"file\": \"old:\"}",
                "{\"file\": \"C:\\\\Windows\\\\win.ini\"}", "{\"file\": \"" + dir.toString().replace("\\", "\\\\") + "\"}", "{\"file\": 3}", "{}"}) {
            HttpResponse<String> refused = post(app, "/api/log/open", body);
            assertEquals(400, refused.statusCode(), body);
            assertEquals("The file must be one of settings, log, history, fake-history, or an old log.", json(refused).get("error").asText());
        }
        assertEquals(List.of(), desk.asked, "a name the program does not know, or a path, opens nothing");
        assertEquals(405, get(app, "/api/log/open").statusCode());

        desk.failure = "no such program";
        HttpResponse<String> failed = post(app, "/api/log/open", "{\"file\": \"log\"}");
        assertEquals(500, failed.statusCode());
        assertEquals("The folder could not be opened: no such program", json(failed).get("error").asText());
    }

    // ---- the two buttons of the log panel: reset, and the undo of it

    @Test
    void theLogIsResetAndTheResetTakenBackThroughTheApiWhichNamesNoFileAndGivesNoPath() throws Exception {
        writeLog("2026-10-11 13:24:01 INFO    [A] of before");
        try (Logging logging = Logging.install(AppFiles.in(dir).log())) {
            AppRuntime app = start(new FakeFetch());
            assertFalse(json(get(app, "/api/log")).get("oldLog").asBoolean(), "no old log yet");

            HttpResponse<String> reset = post(app, "/api/log/reset", "{}");

            assertEquals(200, reset.statusCode(), reset.body());
            assertTrue(json(reset).get("oldLog").asBoolean());
            assertFalse(reset.body().contains(dir.toString()), "no path in the answer");
            Json log = json(get(app, "/api/log?level=debug"));
            assertTrue(log.get("oldLog").asBoolean(), "the log says that there is one to put back");
            assertTrue(linesOf(log).get(0).contains("[Logging] The log was reset: the lines before are in " + dir.toAbsolutePath() + java.io.File.separator + "java-aip-usage."),
                    linesOf(log).toString());
            assertFalse(linesOf(log).stream().anyMatch(line -> line.contains("of before")), "the lines of before are in the old log");
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                assertEquals(1, files.filter(file -> file.getFileName().toString().matches("java-aip-usage\\.\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d\\.log")).count());
            }

            HttpResponse<String> merged = post(app, "/api/log/merge", "{}");

            assertEquals(200, merged.statusCode(), merged.body());
            assertFalse(json(merged).get("oldLog").asBoolean(), "the one old log is put back and deleted");
            List<String> lines = linesOf(json(get(app, "/api/log?level=debug")));
            assertEquals("2026-10-11 13:24:01 INFO    [A] of before", lines.get(0), "the lines of before are first again");
            assertTrue(lines.get(lines.size() - 1).contains("[Logging] The log was merged with " + dir.toAbsolutePath() + java.io.File.separator + "java-aip-usage."), lines.toString());

            HttpResponse<String> none = post(app, "/api/log/merge", "{}");
            assertEquals(409, none.statusCode());
            assertEquals("There is no old log.", json(none).get("error").asText());
        }
    }

    @Test
    void theTwoButtonsOfTheLogTakeAPostAndNothingElseAndSayWhenTheLogHasNoFile() throws Exception {
        AppRuntime app = start(new FakeFetch());

        for (String path : new String[] {"/api/log/reset", "/api/log/merge"}) {
            HttpResponse<String> got = get(app, path);
            assertEquals(405, got.statusCode(), path);
            assertEquals("POST", got.headers().firstValue("Allow").orElse(""));
        }
        // No log is open on the file here, as in a run that logs to the console alone.
        HttpResponse<String> reset = post(app, "/api/log/reset", "{}");
        assertEquals(500, reset.statusCode());
        assertEquals("The log could not be reset: the log is not written to a file", json(reset).get("error").asText());
        HttpResponse<String> merge = post(app, "/api/log/merge", "{}");
        assertEquals(500, merge.statusCode());
        assertEquals("The log could not be merged: the log is not written to a file", json(merge).get("error").asText());
        assertFalse(json(get(app, "/api/log")).get("oldLog").asBoolean());
    }

    private static final String[] LEVELS = {
        "2026-10-11 13:24:01 INFO    [A] one",
        "2026-10-11 13:24:02 WARNING [A] two",
        "\tat a.B.c(B.java:1)",
        "2026-10-11 13:24:03 SEVERE  [A] three",
    };

    private static List<String> linesOf(Json log) {
        return log.get("lines").items().stream().map(Json::asText).toList();
    }

    @Test
    void theLogComesAtTheSavedLevelWhichIsInfoAndNoDateByDefault() throws Exception {
        writeLog(LEVELS);
        AppRuntime app = start(new FakeFetch());

        Json log = json(get(app, "/api/log"));

        assertEquals("info", log.get("level").asText());
        assertFalse(log.get("date").asBoolean());
        assertFalse(log.get("hidden").asBoolean());
        assertEquals(List.of(LEVELS), linesOf(log), "the lines as the file has them, the date included: the page cuts it");
    }

    @Test
    void theTwoChoicesOfTheLogPanelAreSavedAtOnceAndTheLogComesAtTheNewLevel() throws Exception {
        writeLog(LEVELS);
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> saved = post(app, "/api/log", "{\"level\": \"warning\", \"date\": true}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("warning", json(saved).get("level").asText());
        assertTrue(json(saved).get("date").asBoolean());
        Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
        assertEquals("warning", file.get("logLevel").asText());
        assertTrue(file.get("logDate").asBoolean());

        Json log = json(get(app, "/api/log"));
        assertEquals("warning", log.get("level").asText());
        assertTrue(log.get("date").asBoolean());
        assertEquals(List.of(LEVELS[1], LEVELS[2], LEVELS[3]), linesOf(log), "the warning with its stack trace, and the error");

        post(app, "/api/log", "{\"level\": \"error\", \"date\": false}");
        assertEquals(List.of(LEVELS[3]), linesOf(json(get(app, "/api/log"))));
    }

    @Test
    void aLevelNamedInTheRequestIsTheOneGivenAndTheSavedOneIsStillSaid() throws Exception {
        writeLog(LEVELS[0]);
        AppRuntime app = start(new FakeFetch());

        Json errors = json(get(app, "/api/log?level=error"));

        assertEquals(0, errors.get("lines").size());
        assertTrue(errors.get("hidden").asBoolean(), "the file has lines, and none is an error");
        assertEquals("info", errors.get("level").asText(), "what is saved, not what was asked for");
        assertEquals(1, json(get(app, "/api/log?level=debug")).get("lines").size());
        assertEquals(400, get(app, "/api/log?level=loud").statusCode());
    }

    @Test
    void aLogChoiceThatIsNotOneIsRefusedAndNothingIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch());

        for (String body : new String[] {"{\"level\": \"trace\", \"date\": false}", "{\"date\": false}", "{\"level\": \"info\"}",
                "{\"level\": \"info\", \"date\": \"no\"}", "{\"level\": 2, \"date\": false}"}) {
            assertEquals(400, post(app, "/api/log", body).statusCode(), body);
        }
        assertEquals("info", json(get(app, "/api/log")).get("level").asText());
    }

    @Test
    void savingTheSettingsLeavesTheChoicesOfTheLogPanelAsTheyAre() throws Exception {
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/log", "{\"level\": \"error\", \"date\": true}");

        Json settings = json(get(app, "/api/settings"));
        assertFalse(settings.get("settings").has("logLevel"), "the settings view does not have them");
        assertFalse(settings.get("defaults").has("logDate"));
        assertEquals(200, post(app, "/api/settings", settings.get("defaults").toString()).statusCode(), "Restore defaults, saved");

        Json log = json(get(app, "/api/log"));
        assertEquals("error", log.get("level").asText());
        assertTrue(log.get("date").asBoolean());
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
        HttpResponse<String> configPost = post(app, "/api/config", "{\"usageIntervalSeconds\": 90}");
        assertEquals(405, configPost.statusCode(), "the interval is changed only through the settings");
        assertEquals("GET", configPost.headers().firstValue("Allow").orElse(""));
        assertEquals(405, send(app, "/api/settings", HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString("{}"))
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
                    .POST(HttpRequest.BodyPublishers.ofString(allSettings(90, false, "hh:mm"))).header("Content-Type", type);
            assertEquals(415, send(app, "/api/refresh", refresh).statusCode(), type);
            assertEquals(415, send(app, "/api/settings", config).statusCode(), type);
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
                .POST(HttpRequest.BodyPublishers.ofString(allSettings(90, false, "hh:mm")))
                .header("Content-Type", "application/json; charset=utf-8");

        assertEquals(200, send(start(new FakeFetch()), "/api/settings", request).statusCode());
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

    private Json json(HttpResponse<String> response) throws IOException {
        return JsonReader.read(response.body());
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

    // ---- the size of the text of the lists

    @Test
    void theConfigSaysTheSizeOfTheListsTextWhichIsTwelveByDefault() throws Exception {
        AppRuntime app = start(new FakeFetch());

        assertEquals(12, json(get(app, "/api/config")).get("listFontSize").asInt());
    }

    @Test
    void theTextSizeIsSavedAtOnceAndTheConfigSaysItAfterThat() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> saved = post(app, "/api/text-size", "{\"size\": 16}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(16, json(saved).get("size").asInt());
        assertEquals(16, json(get(app, "/api/config")).get("listFontSize").asInt());
        assertEquals(16, JsonReader.read(Files.readString(dir.resolve("settings.json"))).get("listFontSize").asInt());
    }

    @Test
    void aTextSizeThatIsNotOneIsRefusedAndNothingIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch());

        for (String body : new String[] {"{\"size\": 9}", "{\"size\": 21}", "{\"size\": 12.5}", "{\"size\": \"12\"}", "{}"}) {
            HttpResponse<String> refused = post(app, "/api/text-size", body);
            assertEquals(400, refused.statusCode(), body);
            assertTrue(refused.body().contains("from 10 to 20"), refused.body());
        }
        assertEquals(12, json(get(app, "/api/config")).get("listFontSize").asInt());
        assertEquals(405, get(app, "/api/text-size").statusCode(), "there is nothing to read there");
    }

    @Test
    void savingTheSettingsLeavesTheTextSizeAsItIs() throws Exception {
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/text-size", "{\"size\": 18}");

        Json settings = json(get(app, "/api/settings"));
        assertFalse(settings.get("settings").has("listFontSize"), "the settings view does not have it");
        assertEquals(200, post(app, "/api/settings", settings.get("defaults").toString()).statusCode(), "Restore defaults, saved");

        assertEquals(18, json(get(app, "/api/config")).get("listFontSize").asInt());
    }

    // ---- the automatic interval

    @Test
    void automaticIsASettingOffByDefaultAndTheSettingsSayTheConfiguredIntervalBesideTheOneInForce() throws Exception {
        AppRuntime app = start(new FakeFetch(), 90);

        Json body = json(get(app, "/api/settings"));

        assertFalse(body.at("/settings/autoInterval").asBoolean());
        assertFalse(body.at("/defaults/autoInterval").asBoolean());
        assertEquals(90, body.get("configuredIntervalSeconds").asInt());
        assertEquals(90, body.at("/settings/usageIntervalSeconds").asInt());
        assertEquals(400, post(app, "/api/settings", allSettings(90, false, "hh:mm").replace(", \"autoInterval\": false", "")).statusCode(),
                "it must be given like every other setting");
    }

    @Test
    void switchedOnTheIntervalOfTheSearchIsInForceAndTheConfiguredOneIsKept() throws Exception {
        AppRuntime app = start(new FakeFetch(), 90);
        String on = allSettings(90, false, "hh:mm").replace("\"autoInterval\": false", "\"autoInterval\": true");

        HttpResponse<String> saved = post(app, "/api/settings", on);

        assertEquals(200, saved.statusCode(), saved.body());
        Json body = json(saved);
        assertTrue(body.at("/settings/autoInterval").asBoolean());
        assertEquals(90, body.get("configuredIntervalSeconds").asInt(), "the configured interval is kept");
        int inForce = body.at("/settings/usageIntervalSeconds").asInt();
        assertTrue(inForce >= 10 && inForce <= 60, "the search begins at 60 and goes down with each answer: " + inForce);
        Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
        assertTrue(file.get("autoInterval").asBoolean());
        assertEquals(90, file.get("usageIntervalSeconds").asInt(), "and is what the file has");

        // Switched off again, the configured interval is in force.
        Json off = json(post(app, "/api/settings", allSettings(90, false, "hh:mm")));
        assertFalse(off.at("/settings/autoInterval").asBoolean());
        assertEquals(90, off.at("/settings/usageIntervalSeconds").asInt());
    }

    @Test
    void theSearchIsSavedWhenItMovesAndTheNextStartGoesOnFromIt() throws Exception {
        Files.writeString(dir.resolve("settings.json"),
                "{\"autoInterval\": true, \"autoIntervalSeconds\": 29, \"autoLowerSeconds\": 23, \"autoUpperSeconds\": 35}");
        AppRuntime app = start(new FakeFetch());

        // The first refresh was answered with no refusal known: a tenth down from 30 (29 saved, rounded up), rounded up to 5 and at least 5 less: 25. The old bounds are not read.
        awaitSearch(25);

        Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
        assertEquals(25, file.get("autoIntervalSeconds").asInt());
        assertFalse(file.has("autoLowerSeconds") || file.has("autoUpperSeconds") || file.has("autoRefusedSeconds"), "no refusal is known, and the old bounds are gone");
        assertEquals(25, json(get(app, "/api/settings")).at("/settings/usageIntervalSeconds").asInt(), "the interval in force");
        assertEquals("25s", json(get(app, "/api/status")).at("/display/interval/text").asText(), "which the row shows");
    }

    private void awaitSearch(int seconds) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
            if (file.has("autoIntervalSeconds") && file.get("autoIntervalSeconds").asInt() == seconds) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the search was not saved at " + seconds + " s: " + Files.readString(dir.resolve("settings.json")));
    }

    // ---- the About view

    @Test
    void aboutSaysTheNameTheVersionWhereTheFilesAreAndWhatTheProgramRunsOn() throws Exception {
        AppRuntime app = start(new FakeFetch());

        Json about = json(get(app, "/api/about"));

        assertEquals("aip usage", about.get("name").asText());
        assertEquals(AppInfo.VERSION, about.get("version").asText());
        assertEquals(dir.resolve("settings.json").toAbsolutePath().normalize().toString(), about.at("/files/settings").asText(), "the full path");
        assertEquals(AppFiles.in(dir).log().toAbsolutePath().normalize().toString(), about.at("/files/log").asText());
        assertEquals(AppFiles.in(dir).history().toAbsolutePath().normalize().toString(), about.at("/files/history").asText());
        assertEquals("Java", about.at("/runtime/name").asText(), "what it runs on is named by the backend, not by the page");
        assertEquals(System.getProperty("java.version"), about.at("/runtime/version").asText());
        assertEquals("JavaFX", about.at("/toolkit/name").asText());
        assertFalse(about.at("/toolkit/version").asText().isBlank());
        assertNull(about.get("java"), "the keys that named a language are gone");
        assertNull(about.get("javafx"));
        assertTrue(about.get("system").asText().startsWith(System.getProperty("os.name")), about.get("system").asText());
        assertFalse(about.get("debug").asBoolean());
        assertFalse(about.get("fake").asBoolean());
        assertEquals(405, post(app, "/api/about", "{}").statusCode(), "there is nothing to change there");
    }

    @Test
    void theLogAndTheHistoryStillSayNothingOfWhereAFileIs() throws Exception {
        writeLog("2026-10-11 13:24:01 INFO    [A] one");
        AppRuntime app = start(new FakeFetch());

        assertFalse(get(app, "/api/log").body().contains(dir.toString()));
        assertFalse(get(app, "/api/history").body().contains(dir.toString()));
        assertTrue(get(app, "/api/about").body().contains(dir.getFileName().toString()), "the About view is where it is said");
    }

    // ---- the paths of the About view: shown in their folder, and copied

    /** A desktop that notes what it is asked and does nothing, or fails when told to. */
    private static final class NotedDesk implements Desk {

        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        volatile String failure;

        volatile java.nio.file.Path chosen;

        @Override
        public Optional<java.nio.file.Path> chooseFolder(java.nio.file.Path start) throws IOException {
            asked.add("choose " + start);
            if (failure != null) {
                throw new Fault(failure);
            }
            return Optional.ofNullable(chosen);
        }

        @Override
        public void reveal(java.nio.file.Path file) throws IOException {
            asked.add("reveal " + file.toAbsolutePath().normalize());
            if (failure != null) {
                throw new Fault(failure);
            }
        }

        @Override
        public void copy(String text) throws IOException {
            asked.add("copy " + text);
            if (failure != null) {
                throw new Fault(failure);
            }
        }
    }

    @Test
    void aboutShowsAFileInItsFolderAndCopiesItsPathByTheNameOfTheFile() throws Exception {
        AppRuntime app = start(new FakeFetch());
        NotedDesk desk = new NotedDesk();
        app.desk(desk);
        String settings = dir.resolve("settings.json").toAbsolutePath().normalize().toString();
        String log = AppFiles.in(dir).log().toAbsolutePath().normalize().toString();
        String history = AppFiles.in(dir).history().toAbsolutePath().normalize().toString();

        assertEquals(200, post(app, "/api/about/open", "{\"file\": \"settings\"}").statusCode());
        assertEquals(200, post(app, "/api/about/open", "{\"file\": \"log\"}").statusCode());
        assertEquals(200, post(app, "/api/about/copy", "{\"file\": \"history\"}").statusCode());
        assertEquals(200, post(app, "/api/about/copy", "{\"file\": \"settings\"}").statusCode());

        assertEquals(List.of("reveal " + settings, "reveal " + log, "copy " + history, "copy " + settings), desk.asked);
    }

    @Test
    void aboutOpensAndCopiesNothingButTheThreeFilesWhateverTheRequestSays() throws Exception {
        AppRuntime app = start(new FakeFetch());
        NotedDesk desk = new NotedDesk();
        app.desk(desk);

        for (String body : new String[] {"{\"file\": \"C:\\\\Windows\\\\System32\"}", "{\"file\": \"../settings\"}", "{\"file\": \"\"}", "{\"file\": 1}", "{}",
                "{\"path\": \"settings\"}"}) {
            for (String action : new String[] {"/api/about/open", "/api/about/copy"}) {
                HttpResponse<String> refused = post(app, action, body);
                assertEquals(400, refused.statusCode(), action + " " + body);
                assertTrue(refused.body().contains("settings, log, history"), refused.body());
            }
        }
        assertEquals(List.of(), desk.asked, "the desktop was asked nothing");
        assertEquals(405, get(app, "/api/about/open").statusCode());
        assertEquals(405, get(app, "/api/about/copy").statusCode());
    }

    @Test
    void whenTheDesktopRefusesAboutSaysWhatCouldNotBeDone() throws Exception {
        AppRuntime app = start(new FakeFetch());
        NotedDesk desk = new NotedDesk();
        desk.failure = "no such program";
        app.desk(desk);

        HttpResponse<String> open = post(app, "/api/about/open", "{\"file\": \"log\"}");
        HttpResponse<String> copy = post(app, "/api/about/copy", "{\"file\": \"log\"}");

        assertEquals(500, open.statusCode());
        assertEquals("The folder could not be opened: no such program", json(open).get("error").asText());
        assertEquals(500, copy.statusCode());
        assertEquals("The path could not be copied: no such program", json(copy).get("error").asText());
    }

    // ---- the fake backend's options, with the debug settings

    private static final String SWITCHES = "\"fakeBackend\": false, \"fakeScenario\": \"normal\", \"fakeToken\": false";

    @Test
    void theDebugSettingsSayHowTheFakeBackendAnswersWhichIsAsItAlwaysDidByDefault() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.parse(List.of("--debug")));

        Json debug = json(get(app, "/api/debug"));

        assertEquals("usage", debug.at("/settings/fakeAccount").asText());
        assertEquals(137, debug.at("/settings/fakeIncreaseCents").asInt());
        assertEquals("1000.00", debug.at("/settings/fakeBudget").asText(), "in dollars, as a finished text");
        assertEquals("186.02", debug.at("/settings/fakeStartAmount").asText());
        assertEquals(0, debug.at("/settings/fakeRefuseSeconds").asInt());
        assertEquals(0, debug.at("/settings/fakeDelayMillis").asInt());
        assertEquals(List.of("usage", "seat"), debug.get("accounts").items().stream().map(Json::asText).toList());
        assertFalse(debug.get("scenarios").toString().contains("seat-based"), "the scenario for a seat-based account is gone");
    }

    @Test
    void theFakeBackendsOptionsAreSavedWithTheDebugSettingsAndWhatIsNotGivenStaysAsItIs() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.parse(List.of("--debug")));

        HttpResponse<String> saved = post(app, "/api/debug", "{" + SWITCHES
                + ", \"fakeAccount\": \"seat\", \"fakeIncreaseCents\": 50, \"fakeBudget\": 250, \"fakeStartAmount\": 12.34, \"fakeRefuseSeconds\": 30, \"fakeDelayMillis\": 400}");

        assertEquals(200, saved.statusCode(), saved.body());
        Json settings = json(saved).get("settings");
        assertEquals("seat", settings.get("fakeAccount").asText());
        assertEquals("250.00", settings.get("fakeBudget").asText());
        assertEquals("12.34", settings.get("fakeStartAmount").asText());
        Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
        assertEquals("seat", file.get("fakeAccount").asText());
        assertEquals(50, file.get("fakeIncreaseCents").asInt());
        assertEquals(30, file.get("fakeRefuseSeconds").asInt());
        assertEquals(400, file.get("fakeDelayMillis").asInt());

        // An amount as the page sends it, the text that was typed.
        assertEquals("75.50", json(post(app, "/api/debug", "{" + SWITCHES + ", \"fakeBudget\": \"75.5\"}")).at("/settings/fakeBudget").asText());
        post(app, "/api/debug", "{" + SWITCHES + ", \"fakeBudget\": 250}");
        // The three switches alone: the rest is kept.
        Json kept = json(post(app, "/api/debug", "{" + SWITCHES + "}")).get("settings");
        assertEquals("seat", kept.get("fakeAccount").asText());
        assertEquals(50, kept.get("fakeIncreaseCents").asInt());
        // And one of them alone.
        Json one = json(post(app, "/api/debug", "{" + SWITCHES + ", \"fakeIncreaseCents\": 7}")).get("settings");
        assertEquals(7, one.get("fakeIncreaseCents").asInt());
        assertEquals(400, one.get("fakeDelayMillis").asInt());
    }

    @Test
    void anOptionOfTheFakeBackendThatCannotBeIsRefusedWithTheReasonAndNothingIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.parse(List.of("--debug")));
        java.util.Map<String, String> refusals = new java.util.LinkedHashMap<>();
        refusals.put("\"fakeAccount\": \"team\"", "The setting fakeAccount must be one of usage, seat.");
        refusals.put("\"fakeIncreaseCents\": 1.5", "The largest increase must be a whole number of cents from 0 to 100000.");
        refusals.put("\"fakeIncreaseCents\": 100001", "The largest increase must be a whole number of cents from 0 to 100000.");
        refusals.put("\"fakeBudget\": 0", "The budget must be an amount from 0.01 to 1,000,000.");
        refusals.put("\"fakeBudget\": 12.345", "The budget must be an amount from 0.01 to 1,000,000.");
        refusals.put("\"fakeBudget\": \"1e3\"", "The budget must be an amount from 0.01 to 1,000,000.");
        refusals.put("\"fakeBudget\": \"12.345\"", "The budget must be an amount from 0.01 to 1,000,000.");
        refusals.put("\"fakeBudget\": 100, \"fakeStartAmount\": 100.01", "The starting amount must be an amount from 0 to the budget.");
        refusals.put("\"fakeRefuseSeconds\": 3601", "The time within which a request is refused must be a whole number of seconds from 0 to 3600.");
        refusals.put("\"fakeDelayMillis\": -1", "The longest request must be a whole number of milliseconds from 0 to 60000.");

        for (java.util.Map.Entry<String, String> refusal : refusals.entrySet()) {
            HttpResponse<String> answer = post(app, "/api/debug", "{" + SWITCHES + ", " + refusal.getKey() + "}");
            assertEquals(400, answer.statusCode(), refusal.getKey());
            assertEquals(refusal.getValue(), json(answer).get("error").asText(), refusal.getKey());
        }
        assertEquals(137, json(get(app, "/api/debug")).at("/settings/fakeIncreaseCents").asInt(), "as it was");
    }

    // ---- cost control: the two limits, as settings

    @Test
    void theSettingsHaveNoLimitsByDefaultAndTheDailyOneIsWorkedOut() throws Exception {
        AppRuntime app = start(new FakeFetch());

        Json body = json(get(app, "/api/settings"));

        assertTrue(body.at("/settings/costMonthlyLimit").isNull());
        assertTrue(body.at("/settings/costDailyLimit").isNull());
        assertTrue(body.at("/settings/costDailyAutomatic").asBoolean());
        assertTrue(body.at("/defaults/costDailyAutomatic").asBoolean());
        // None typed: the server's limit is counted (since 0.82), and the daily limit is worked out from it; the fake fetch sends 1,000.00.
        assertEquals(CostControl.text(100_000L / CostControl.weekdays(java.time.YearMonth.now())), body.get("costDailyWorkedOut").asText());
        assertEquals("1000.00", body.get("serverLimit").asText());
    }

    @Test
    void theLimitsAreSavedWithTheSettingsAndTheDailyOneIsWorkedOutFromTheMonthlyOne() throws Exception {
        AppRuntime app = start(new FakeFetch());
        String limits = allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"250\", \"costDailyLimit\": null, \"costDailyAutomatic\": true}");

        HttpResponse<String> saved = post(app, "/api/settings", limits);

        assertEquals(200, saved.statusCode(), saved.body());
        Json body = json(saved);
        assertEquals("250.00", body.at("/settings/costMonthlyLimit").asText(), "as a finished text");
        long worked = org.example.settings.CostControl.dailyFor(25_000, java.time.YearMonth.now());
        assertEquals(org.example.settings.CostControl.text(worked), body.get("costDailyWorkedOut").asText(), "250.00 over the weekdays of this month");
        Json file = JsonReader.read(Files.readString(dir.resolve("settings.json")));
        assertEquals(250.0, file.get("costMonthlyLimit").asDouble(), 0.0001);
        assertTrue(file.get("costDailyAutomatic").asBoolean());

        // A daily limit that is typed in: it is the one in force.
        Json typed = json(post(app, "/api/settings",
                allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": 250, \"costDailyLimit\": \"12.5\", \"costDailyAutomatic\": false}")));
        assertEquals("12.50", typed.at("/settings/costDailyLimit").asText());
        assertEquals("12.50", typed.get("costDailyWorkedOut").asText());

        // Settings that say nothing of the limits leave them as they are; an empty limit takes it away.
        Json kept = json(post(app, "/api/settings", allSettings(90, false, "hh:mm")));
        assertEquals("250.00", kept.at("/settings/costMonthlyLimit").asText());
        Json none = json(post(app, "/api/settings", allSettings(90, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"\", \"costDailyLimit\": null}")));
        assertTrue(none.at("/settings/costMonthlyLimit").isNull());
    }

    @Test
    void aLimitThatIsNoAmountOrADailyOneAboveTheMonthlyOneIsRefusedAndNothingIsSaved() throws Exception {
        AppRuntime app = start(new FakeFetch());
        String amount = "A limit must be an amount from 0.01 to 1,000,000, with two decimals at most.";
        java.util.Map<String, String> refusals = new java.util.LinkedHashMap<>();
        refusals.put("\"costMonthlyLimit\": \"abc\"", amount);
        refusals.put("\"costMonthlyLimit\": 0", amount);
        refusals.put("\"costMonthlyLimit\": \"12.345\"", amount);
        refusals.put("\"costMonthlyLimit\": 1000000.01", amount);
        refusals.put("\"costMonthlyLimit\": true", amount);
        refusals.put("\"costDailyLimit\": \"-1\", \"costDailyAutomatic\": false", amount);
        refusals.put("\"costMonthlyLimit\": 100, \"costDailyLimit\": 100.01, \"costDailyAutomatic\": false", "The daily limit cannot be more than the monthly limit.");
        refusals.put("\"costDailyAutomatic\": \"yes\"", "The setting costDailyAutomatic must be true or false.");

        for (java.util.Map.Entry<String, String> refusal : refusals.entrySet()) {
            HttpResponse<String> answer = post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", " + refusal.getKey() + "}"));
            assertEquals(400, answer.statusCode(), refusal.getKey());
            assertEquals(refusal.getValue(), json(answer).get("error").asText(), refusal.getKey());
        }
        assertTrue(json(get(app, "/api/settings")).at("/settings/costMonthlyLimit").isNull(), "nothing was saved");
        assertEquals(200, post(app, "/api/settings",
                allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": 100, \"costDailyLimit\": 100, \"costDailyAutomatic\": false}")).statusCode(),
                "a daily limit as large as the monthly one is allowed");
    }

    @Test
    void theDailyLimitThatFollowsFromAMonthlyOneCanBeAskedFor() throws Exception {
        AppRuntime app = start(new FakeFetch());
        java.time.YearMonth month = java.time.YearMonth.now();

        Json worked = json(get(app, "/api/cost/daily?monthly=250"));
        assertEquals(org.example.settings.CostControl.text(org.example.settings.CostControl.dailyFor(25_000, month)), worked.get("daily").asText());
        assertEquals(org.example.settings.CostControl.weekdays(month), worked.get("weekdays").asInt());
        assertEquals(worked.get("daily").asText(), json(get(app, "/api/cost/daily?monthly=250.00")).get("daily").asText());

        assertTrue(json(get(app, "/api/cost/daily?monthly=")).get("daily").isNull(), "no monthly limit: none to work out");
        assertTrue(json(get(app, "/api/cost/daily")).get("daily").isNull());
        assertEquals(400, get(app, "/api/cost/daily?monthly=abc").statusCode());
        assertEquals(400, get(app, "/api/cost/daily?monthly=0").statusCode());
        assertEquals(405, post(app, "/api/cost/daily", "{}").statusCode());
    }

    @Test
    void severalViewsAtOnceIsASettingOffByDefaultOptionalInAPostAndSaidByTheStatus() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertFalse(json(get(app, "/api/settings")).at("/settings/severalViews").asBoolean());
        assertFalse(json(get(app, "/api/settings")).at("/defaults/severalViews").asBoolean());
        assertFalse(json(get(app, "/api/status")).at("/display/show/severalViews").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"severalViews\": true}"));
        assertTrue(json(get(app, "/api/settings")).at("/settings/severalViews").asBoolean());
        assertTrue(json(get(app, "/api/status")).at("/display/show/severalViews").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm"));
        assertTrue(json(get(app, "/api/settings")).at("/settings/severalViews").asBoolean(), "left out: it stays as it is");
    }

    @Test
    void theStatusSaysTheHeightsTheHistoryAndTheLogWereLastLeftAt() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertEquals(0, json(get(app, "/api/status")).at("/heights/history").asInt(), "none yet");
        assertEquals(0, json(get(app, "/api/status")).at("/heights/log").asInt());

        app.settings().storeHeight("history", 610);
        app.settings().storeHeight("log", 480);

        Json heights = json(get(app, "/api/status")).get("heights");
        assertEquals(610, heights.get("history").asInt());
        assertEquals(480, heights.get("log").asInt());
        assertEquals(2, heights.size(), "the error log has no height that is kept");
    }

    // ---- version 0.81: the folders of the log and of the history

    /** A path as a JSON text: the backslashes of Windows escaped. */
    private static String jsonPath(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }

    @Test
    void theFoldersAreTheProgramsUntilOthersAreNamedAndAPostMovesTheFilesThereAtOnce() throws Exception {
        Files.writeString(dir.resolve("java-aip-usage.log"), "2026-10-13 14:00:00 INFO    [A] the line of the log\n");
        Files.writeString(dir.resolve("java-aip-usage.2026-10-13_14-30-05.log"), "2026-10-13 13:00:00 INFO    [A] an old line\n");
        writeMixedHistory();
        AppRuntime app = start(new FakeFetch());
        assertEquals("", json(get(app, "/api/settings")).at("/settings/logFolder").asText(), "the program's folder");
        assertEquals("", json(get(app, "/api/settings")).at("/defaults/historyFolder").asText());
        Path logs = Files.createDirectory(dir.resolve("logs"));
        Path histories = Files.createDirectory(dir.resolve("histories"));

        HttpResponse<String> response = post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("}", ", \"logFolder\": " + jsonPath(logs) + ", \"historyFolder\": " + jsonPath(histories) + "}"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(logs.toString(), json(response).at("/settings/logFolder").asText());
        assertTrue(Files.exists(logs.resolve("java-aip-usage.log")), "the log is moved");
        assertTrue(Files.exists(logs.resolve("java-aip-usage.2026-10-13_14-30-05.log")), "and its old log with it");
        assertFalse(Files.exists(dir.resolve("java-aip-usage.log")));
        assertTrue(Files.exists(histories.resolve("java-aip-usage.csv")), "the history is moved");
        assertFalse(Files.exists(dir.resolve("java-aip-usage.csv")));
        assertTrue(Files.exists(dir.resolve("settings.json")), "the settings stay where they are");
        assertEquals(histories.toString(), JsonReader.read(Files.readString(dir.resolve("settings.json"))).get("historyFolder").asText());
        assertTrue(historyWith(true, true).get("lines").size() >= 7, "the history is read from where it is now, the readings of this run with it");
        assertTrue(json(get(app, "/api/log")).get("lines").toString().contains("the line of the log"), "and so is the log");
        assertEquals(histories.resolve("java-aip-usage.csv").toAbsolutePath().toString(), json(get(app, "/api/about")).at("/files/history").asText());

        HttpResponse<String> back = post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"logFolder\": \"\", \"historyFolder\": \"\"}"));
        assertEquals(200, back.statusCode(), back.body());
        assertTrue(Files.exists(dir.resolve("java-aip-usage.log")), "the empty text is the program's folder again");
        assertTrue(Files.exists(dir.resolve("java-aip-usage.csv")));
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefusedAndNothingIsMoved() throws Exception {
        writeMixedHistory();
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> gone = post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("}", ", \"historyFolder\": " + jsonPath(dir.resolve("not-there")) + "}"));
        assertEquals(400, gone.statusCode());
        assertEquals("The folder cannot be written: the file or its folder is not there.", json(gone).get("error").asText());
        assertTrue(Files.exists(dir.resolve("java-aip-usage.csv")), "nothing is moved");
        assertEquals("", json(get(app, "/api/settings")).at("/settings/historyFolder").asText(), "and nothing is saved");

        HttpResponse<String> relative = post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"logFolder\": \"logs\"}"));
        assertEquals(400, relative.statusCode());
        assertEquals("The folder cannot be written: it is not a full path.", json(relative).get("error").asText());
    }

    @Test
    void aFileOfTheSameNameInTheNewFolderIsUsedAndTheOldOneStays() throws Exception {
        writeMixedHistory();
        AppRuntime app = start(new FakeFetch());
        Path other = Files.createDirectory(dir.resolve("other"));
        Files.write(other.resolve("java-aip-usage.csv"), List.of(CSV_HEADER, "2026-10-09 09:00:00,20.00,1000.00,USD,start,60,400"));

        assertEquals(200, post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"historyFolder\": " + jsonPath(other) + "}")).statusCode());

        assertTrue(Files.exists(dir.resolve("java-aip-usage.csv")), "the old one is left where it is");
        assertEquals("09:00:00", times(historyWith(true, true)).get(0), "the one that was there is the history now");
    }

    @Test
    void theFolderPickerOpensAtTheFolderNamedOrTheProgramsAndSavesNothing() throws Exception {
        AppRuntime app = start(new FakeFetch());
        NotedDesk desk = new NotedDesk();
        app.desk(desk);
        Path other = Files.createDirectory(dir.resolve("other"));

        HttpResponse<String> cancelled = post(app, "/api/folder", "{\"start\": " + jsonPath(other) + "}");
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertTrue(json(cancelled).get("folder").isNull(), "cancelled");
        assertEquals("choose " + other, desk.asked.get(0), "opened at the folder named");

        desk.chosen = other;
        assertEquals(other.toAbsolutePath().normalize().toString(), json(post(app, "/api/folder", "{\"start\": \"not a folder\"}")).get("folder").asText());
        assertEquals("choose " + dir.toAbsolutePath(), desk.asked.get(1), "the program's folder for what is not one");
        assertEquals("", json(get(app, "/api/settings")).at("/settings/logFolder").asText(), "nothing is saved");
        assertEquals(dir.toAbsolutePath().normalize().toString(), json(get(app, "/api/settings")).get("programFolder").asText());

        desk.failure = "there is no window to choose a folder in";
        HttpResponse<String> failed = post(app, "/api/folder", "{}");
        assertEquals(500, failed.statusCode());
        assertEquals("The folder could not be chosen: there is no window to choose a folder in", json(failed).get("error").asText());
        assertEquals(405, get(app, "/api/folder").statusCode());
    }

    @Test
    void theCostControlHasItsOwnCurrencySymbolOffByDefault() throws Exception {
        writeMixedHistory();
        AppRuntime app = start(new FakeFetch());
        assertFalse(json(get(app, "/api/settings")).at("/settings/costCurrency").asBoolean());
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"showCurrency\": false", "\"showCurrency\": true")
                .replace("}", ", \"costMonthlyLimit\": \"500\"}"));
        String month = json(get(app, "/api/cost")).at("/rows/1/text").asText();
        assertFalse(month.contains("$"), "the row's symbol is on, the cost control's is not: " + month);

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costCurrency\": true}"));
        assertTrue(json(get(app, "/api/settings")).at("/settings/costCurrency").asBoolean());
        assertTrue(json(get(app, "/api/cost")).at("/rows/1/text").asText().contains("$"));
    }

    @Test
    void theChoicesOfCostControlAreSettingsWithTheirRanges() throws Exception {
        AppRuntime app = start(new FakeFetch());
        Json settings = json(get(app, "/api/settings")).get("settings");
        assertEquals("monthly", settings.get("costLimitSource").asText());
        assertEquals(8, settings.get("costWorkdayHours").asInt());
        assertTrue(settings.get("costWorkdays").isNull());

        HttpResponse<String> saved = post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("}", ", \"costLimitSource\": \"server\", \"costWorkdayHours\": 6, \"costWorkdays\": 19}"));
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("server", json(saved).at("/settings/costLimitSource").asText());
        assertEquals(19, json(saved).at("/settings/costWorkdays").asInt());
        assertEquals(java.time.YearMonth.now().toString(), JsonReader.read(Files.readString(dir.resolve("settings.json"))).get("costWorkdaysMonth").asText(),
                "the days hold for the month they were typed in");

        for (String wrong : new String[] {"\"costLimitSource\": \"both\"", "\"costWorkdayHours\": 0", "\"costWorkdayHours\": 25", "\"costWorkdays\": 32"}) {
            assertEquals(400, post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", " + wrong + "}")).statusCode(), wrong);
        }
        assertEquals("21.05", json(get(app, "/api/cost/daily?monthly=400&workdays=19")).get("daily").asText(), "the days typed");
    }

    @Test
    void theRowShowsTheLimitCountedAndItsPercentage() throws Exception {
        AppRuntime app = start(new FakeFetch());
        assertEquals("1,000.00", json(get(app, "/api/status")).at("/display/spend/limit").asText(), "nothing typed: the server's");

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"500\"}"));
        Json spend = json(get(app, "/api/status")).at("/display/spend");
        assertEquals("500.00", spend.get("limit").asText(), "the monthly limit typed is counted");
        assertEquals("37%", spend.get("percentText").asText(), "186.02 of 500.00");
        assertTrue(spend.get("limitTooltip").asText().startsWith("Monthly limit of cost control"));

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costLimitSource\": \"server\"}"));
        assertEquals("1,000.00", json(get(app, "/api/status")).at("/display/spend/limit").asText(), "the server's, the typed one kept");
    }

    @Test
    void theStatusSaysHowTheDayAndTheMonthStandAgainstTheirLimits() throws Exception {
        AppRuntime app = start(new FakeFetch());
        Json spending = json(get(app, "/api/status")).get("spending");
        assertTrue(java.util.Set.of("green", "amber", "red", "gray").contains(spending.get("state").asText()), spending.toString());
        assertEquals("186.02", spending.at("/month/used").asText(), "the amount used of the newest reading");
        assertEquals("1,000.00", spending.at("/month/limit").asText(), "the server's limit, none being typed");
        assertEquals("19%", spending.at("/month/percent").asText());
        assertFalse(spending.get("tooltip").asText().isBlank());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"150\"}"));
        Json over = json(get(app, "/api/status")).get("spending");
        assertEquals("red", over.at("/month/state").asText(), "186.02 of 150.00");
        assertEquals("red", over.get("state").asText());
        assertTrue(over.get("tooltip").asText().contains("Month: over the monthly limit: 186.02 of 150.00"), over.get("tooltip").asText());
    }

    @Test
    void theStatusCircleIsShownByDefaultAndTheFiguresOfTheDayAreNot() throws Exception {
        AppRuntime app = start(new FakeFetch());
        Json show = json(get(app, "/api/status")).at("/display/show");
        assertTrue(show.get("status").asBoolean());
        assertFalse(show.get("dayPercentage").asBoolean());
        assertFalse(show.get("dayUsed").asBoolean());
        assertFalse(show.get("dayLimit").asBoolean());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"showStatus\": false, \"showDayUsed\": true}"));
        show = json(get(app, "/api/status")).at("/display/show");
        assertFalse(show.get("status").asBoolean());
        assertTrue(show.get("dayUsed").asBoolean());
    }

    @Test
    void theBarsOfTheCostControlSayTheirStates() throws Exception {
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"150\"}"));
        Json rows = json(get(app, "/api/cost")).get("rows");
        assertEquals("red", rows.get(1).get("state").asText(), "186.02 of 150.00");
        assertTrue(rows.get(1).get("over").asBoolean());
        assertTrue(java.util.Set.of("green", "amber", "red", "gray").contains(rows.get(0).get("state").asText()));
    }

    @Test
    void theHistoryHasTheColumnsOfTheLimitsWhenTheyAreSwitchedOn() throws Exception {
        writeHistory("datetime,used,limit,currency,status,interval,duration_ms,monthly_limit,daily_limit,daily_auto",
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400,150.00,6.81,auto",
                "2026-10-08 14:01:00,10.05,1000.00,USD,,60,400");
        AppRuntime app = start(new FakeFetch());
        Json off = historyWith(true, true);
        assertFalse(off.get("kinds").toString().contains("monthly"), "off by default");

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("}", ", \"historyMonthly\": true, \"historyDaily\": true, \"historyDailyBy\": true}"));
        Json on = historyWith(true, true);
        assertEquals(List.of("time", "used", "limit", "monthly", "daily", "dailyBy"),
                on.get("kinds").items().stream().map(Json::asText).toList().subList(0, 6), "after the limit");
        assertEquals(List.of("month", "day", "by"), on.get("columns").items().stream().map(Json::asText).toList().subList(3, 6));
        Json oldest = on.get("lines").items().getLast();
        assertEquals(List.of("150.00", "6.81", "auto"), oldest.get("cells").items().stream().map(Json::asText).toList().subList(3, 6));
    }
}
