package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application as a whole, minus JavaFX and the network: real settings, real
 * service, real HTTP server, with a stand-in for the usage fetch.
 */
class ApiTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-10-08T12:00:00Z");

    private static final UsageSnapshot SPEND = new UsageSnapshot(
            FETCHED_AT, new Spend(186.02, 1000.0, "USD", 19, "normal"));

    /** A reading with no amounts: an account that reports no usage. Nothing of it is written to the history. */
    private static final UsageSnapshot NO_USAGE = new UsageSnapshot(FETCHED_AT, null);

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

        JsonNode config = json(get(start(new FakeFetch()), "/api/config"));

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

        JsonNode config = json(get(app, "/api/config"));
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

        JsonNode body = json(get(app, "/api/settings"));

        assertEquals(60, body.at("/settings/usageIntervalSeconds").asInt());
        assertFalse(body.at("/settings/logResponse").asBoolean());
        assertEquals("hh:mm", body.at("/settings/timeFormat").asText());
        assertEquals(28, body.get("settings").size(), "the debug settings are not among them");
        assertEquals(body.get("settings"), body.get("defaults"), "nothing has been changed yet");
        assertFalse(body.has("intervalChoices"), "no list of values: the interval is one field");
        assertEquals(5, body.at("/limits/usageIntervalSeconds/min").asInt());
        assertEquals(3600, body.at("/limits/usageIntervalSeconds/max").asInt());
    }

    @Test
    void theFiveElementsThatWereAlwaysInTheRowAreSettingsTooAndTheStatusFollowsThem() throws Exception {
        AppRuntime app = start(new FakeFetch());
        JsonNode defaults = json(get(app, "/api/settings")).get("defaults");
        for (String key : List.of("showTime", "showUsed", "showLimit", "showRefresh", "showCountdown")) {
            assertTrue(defaults.get(key).asBoolean(), key + " is on by default");
        }

        String hidden = allSettings(60, false, "hh:mm")
                .replace("\"showTime\": true", "\"showTime\": false")
                .replace("\"showCountdown\": true", "\"showCountdown\": false");
        assertEquals(200, post(app, "/api/settings", hidden).statusCode());

        JsonNode show = json(get(app, "/api/status")).at("/display/show");
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

        JsonNode display = json(get(app, "/api/status")).get("display");
        assertTrue(display.at("/duration/text").asText().matches("\\d+ms|\\d+s\\d{3}ms"), display.get("duration").toString());
        assertEquals("How long the latest usage request took", display.at("/duration/tooltip").asText());
        assertFalse(display.at("/show/duration").asBoolean(), "off by default");

        String on = allSettings(60, false, "hh:mm").replace("\"showDuration\": false", "\"showDuration\": true");
        assertEquals(200, post(app, "/api/settings", on).statusCode());
        assertTrue(json(get(app, "/api/status")).at("/display/show/duration").asBoolean());

        // The figure of the row is the one the history file has for that request.
        String row = history(app).getLast();
        String millis = row.substring(row.lastIndexOf(',') + 1);
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
        JsonNode body = json(get(app, "/api/debug"));
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
                throw new IOException("no port to listen on");
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
        JsonNode body = json(get(app, "/api/settings"));
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
        AppRuntime app = start(new FakeFetch(), 90);
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
        post(app, "/api/settings", "{\"usageIntervalSeconds\": 120, \"logResponse\": false, \"showPercentage\": false, \"showCurrency\": false, \"showHistoryIcon\": true, \"showLogIcon\": true, \"showErrorIcon\": true, \"showInterval\": false,"
                + " \"showDeltaUsed\": false, \"showDeltaTime\": false, \"timeFormat\": \"hh:mm\","
                + " \"historyDeltaUsed\": false, \"historyDeltaTime\": false, \"historyDate\": false, \"historyZeroLines\": true, \"historyFailedLines\": true, \"showTime\": true, \"showUsed\": true, \"showLimit\": true, \"showRefresh\": true, \"showCountdown\": true, \"showDuration\": false, \"stayOnTop\": false, \"showTitleBar\": true, \"autoInterval\": false}");
        fail.set(false);
        post(app, "/api/refresh", "{}");
        await(() -> history(app).size() >= 3);

        assertTrue(history(app).get(1).contains(",,,,start-failed,60,"), history(app).get(1));
        assertTrue(history(app).get(2).matches(".*,186\\.02,1000\\.00,USD,,120,\\d+"), history(app).get(2));
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

        JsonNode history = json(get(app, "/api/history"));

        assertEquals(6, history.get("columns").size(), "both change columns are on");
        assertEquals("\u0394 used", history.at("/columns/3").asText());
        assertEquals("\u0394 time", history.at("/columns/4").asText());
        assertEquals("Cur.", history.at("/columns/5").asText(), "the currency is the right-most column");
        assertEquals("+0.05", history.at("/lines/0/cells/3").asText());
        assertEquals("63 s", history.at("/lines/0/cells/4").asText());
        assertEquals("", history.at("/lines/1/cells/3").asText(), "the first line of the run has none");
        assertEquals("", history.at("/lines/1/cells/4").asText());

        JsonNode change = json(get(app, "/api/status")).get("change");
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

        assertEquals(5, json(get(app, "/api/history")).get("columns").size(), "by default the four and the change of time");
        post(app, "/api/settings", all.replace("}", ", \"historyTime\": false, \"historyUsed\": true, \"historyLimit\": false, \"historyCurrent\": false}"));

        JsonNode history = json(get(app, "/api/history"));

        assertEquals(java.util.List.of("used", "\u0394 used", "\u0394 time"),
                java.util.stream.StreamSupport.stream(history.get("columns").spliterator(), false).map(JsonNode::asText).toList());
        assertEquals(3, history.at("/lines/0/cells").size());
        assertEquals("10.05", history.at("/lines/0/cells/0").asText());
        assertEquals("+0.05", history.at("/lines/0/cells/1").asText());
        JsonNode settings = json(get(app, "/api/settings")).get("settings");
        assertFalse(settings.get("historyTime").asBoolean());
        assertTrue(settings.get("historyUsed").asBoolean());
        assertFalse(settings.get("historyCurrent").asBoolean());
    }

    @Test
    void theColumnsOfTheHistoryAreAllOnWhenAPostLeavesThemOut() throws Exception {
        AppRuntime app = start(new FakeFetch());
        post(app, "/api/settings", allSettings(60, false, "hh:mm"));

        JsonNode settings = json(get(app, "/api/settings")).get("settings");

        assertTrue(settings.get("historyTime").asBoolean() && settings.get("historyUsed").asBoolean()
                && settings.get("historyLimit").asBoolean() && settings.get("historyCurrent").asBoolean());
    }

    @Test
    void theStatusCarriesTheFinishedDisplayAndItFollowsTheSettings() throws Exception {
        AppRuntime app = start(new FakeFetch());
        await(() -> app.service().state().snapshot() != null);

        JsonNode display = json(get(app, "/api/status")).get("display");
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
        JsonNode on = json(get(app, "/api/history"));

        assertEquals(6, on.get("columns").size());
        // FakeFetch answers the same amount twice: no change, so the cell is empty, never 0.00.
        assertEquals("", on.at("/lines/0/cells/3").asText());
        assertTrue(on.at("/lines/0/cells/4").asText().endsWith(" s"), "the time is in seconds");
        assertEquals("", on.at("/lines/1/cells/3").asText(), "the first line of the run");
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

        JsonNode errors = json(get(app, "/api/errors")).get("entries");

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
    void afterAnHttp429TheLongerWaitIsTheIntervalTheSettingsAndTheRowShow() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().rateLimited());

        JsonNode settings = json(get(app, "/api/settings"));
        JsonNode status = json(get(app, "/api/status"));

        assertEquals(120, settings.at("/settings/usageIntervalSeconds").asInt(), "twice the interval, which is what the service waits");
        assertEquals(60, settings.at("/defaults/usageIntervalSeconds").asInt(), "the defaults are the defaults");
        assertEquals("120s", status.at("/display/interval/text").asText());
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "the file keeps the configured one");
    }

    @Test
    void applyingWhileBackingOffSavesWhatTheBoxShowsAsTheInterval() throws Exception {
        FakeFetch fetch = new FakeFetch();
        fetch.answer = () -> {
            throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429);
        };
        AppRuntime app = start(fetch);
        await(() -> app.service().state().rateLimited());
        int shown = json(get(app, "/api/settings")).at("/settings/usageIntervalSeconds").asInt();

        assertEquals(200, post(app, "/api/settings", allSettings(shown, false, "hh:mm")).statusCode());

        assertEquals(shown, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds());
        assertEquals(Duration.ofSeconds(shown), app.service().interval());
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

        JsonNode status = json(get(app, "/api/status"));
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

    private JsonNode historyWith(boolean zero, boolean failed) throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(zero, failed));
        return json(get(app, "/api/history"));
    }

    private static java.util.List<String> times(JsonNode history) {
        java.util.List<String> times = new java.util.ArrayList<>();
        history.get("lines").forEach(l -> times.add(l.at("/cells/0").asText()));
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

        JsonNode none = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "Cur."), titles(none));
        assertEquals("EUR", none.at("/lines/0/cells/3").asText(), "other currencies keep their code");

        post(app, "/api/settings", allSettings(60, false, "hh:mm")
                .replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true")
                .replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": false"));
        JsonNode one = json(get(app, "/api/history"));
        assertEquals(java.util.List.of("time", "used", "limit", "\u0394 used", "Cur."), titles(one));
        assertEquals("+0.05", one.at("/lines/0/cells/3").asText());
        assertEquals("EUR", one.at("/lines/0/cells/4").asText());

        post(app, "/api/settings", allSettings(60, false, "hh:mm").replace("\"historyDeltaUsed\": false", "\"historyDeltaUsed\": true"));
        JsonNode both = json(get(app, "/api/history"));
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

        JsonNode lines = json(get(app, "/api/history")).get("lines");

        assertEquals("$", lines.get(1).at("/cells/3").asText());
        assertEquals("", lines.get(0).at("/cells/3").asText());
    }

    private static java.util.List<String> titles(JsonNode history) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        history.get("columns").forEach(c -> titles.add(c.asText()));
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
        JsonNode display = json(get(app, "/api/status")).get("display");

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

        JsonNode history = historyWith(true, true);

        assertEquals(7, history.get("lines").size());
        assertTrue(history.get("note").isNull());
    }

    @Test
    void theZeroUsageLinesCanBeHiddenAndTheNoteCountsThem() throws Exception {
        writeMixedHistory();

        JsonNode history = historyWith(false, true);

        // 14:01 repeats 14:00 and 14:05 repeats 14:04: zero usage. 14:04 follows a failed row, so it has no change to be zero. 14:00 is the startup line,
        // which counts as a zero usage line too.
        assertEquals(java.util.List.of("14:06:00", "14:04:00", "14:03:00", "14:02:00"), times(history));
        assertEquals("Showing 4 of 7 lines: 3 zero usage hidden.", history.get("note").asText());
    }

    @Test
    void theFailedLinesCanBeHiddenToo() throws Exception {
        writeMixedHistory();

        JsonNode history = historyWith(true, false);

        assertEquals(6, history.get("lines").size());
        assertFalse(times(history).contains("14:03:00"));
        assertEquals("Showing 6 of 7 lines: 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void bothHiddenAndTheNoteSaysBothAndAllThatIsLeftIsShown() throws Exception {
        writeMixedHistory();

        JsonNode history = historyWith(false, false);

        assertEquals(java.util.List.of("14:06:00", "14:04:00", "14:02:00"), times(history));
        assertEquals("Showing 3 of 7 lines: 3 zero usage and 1 failed hidden.", history.get("note").asText());
    }

    @Test
    void theChangesAreWorkedOutOnTheLinesThatAreShown() throws Exception {
        writeMixedHistory();

        JsonNode lines = historyWith(false, false).get("lines");

        // Shown, newest first: 14:06 (10.20), 14:04 (10.05), 14:02 (10.05). The startup line 14:00 is hidden with the zero usage lines.
        assertEquals(3, lines.size());
        assertEquals("+0.15", lines.get(0).at("/cells/3").asText(), "against 14:04, the previous line shown");
        assertEquals("", lines.get(1).at("/cells/3").asText(), "14:04 is the same as 14:02 shown before it: no change to show");
        assertEquals("", lines.get(2).at("/cells/3").asText(), "14:02 has no line shown before it in its run: the startup line is hidden, so it has no change either");
    }

    @Test
    void theTimeSpansTheLinesThatAreHidden() throws Exception {
        writeMixedHistory();
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        post(app, "/api/settings", withSwitches(false, false).replace("\"historyDeltaTime\": true", "\"historyDeltaTime\": true"));

        JsonNode history = json(get(app, "/api/history"));

        int time = history.get("columns").size() - 2;
        // 14:06 against 14:04: 120 s. 14:04 against 14:02, the failed 14:03 being hidden between them: 120 s, not 60 s.
        assertEquals("120 s", history.at("/lines/0/cells/" + time).asText());
        assertEquals("120 s", history.at("/lines/1/cells/" + time).asText());
    }

    @Test
    void aHiddenLineThatBeginsARunStillBeginsItSoTheNextLineShownHasNoChange() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,10.00,1000.00,USD,start,60,400",
                "2026-10-08 14:01:00,10.10,1000.00,USD,,60,400",
                "2026-10-08 15:00:00,,,,start-failed,60,5000",
                "2026-10-08 15:01:00,10.30,1000.00,USD,,60,400");

        JsonNode lines = historyWith(true, false).get("lines");

        // The start-failed line is hidden. 15:01 would have been compared with 14:01 across the two runs; it is not.
        assertEquals("15:01:00", lines.get(0).at("/cells/0").asText());
        assertEquals("", lines.get(0).at("/cells/3").asText(), "no change across a run that began, hidden or not");
        assertFalse(lines.get(0).get("start").asBoolean(), "and it is not itself a start line");
    }

    @Test
    void theStartAndFailedFlagsAndTheHoverTextAreFinished() throws Exception {
        writeMixedHistory();

        JsonNode lines = historyWith(true, true).get("lines");

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
    void theStartupLinesCountAsZeroUsageLinesAndComeBackWithTheirMarkerWhenTheyAreShown() throws Exception {
        writeMixedHistory();

        JsonNode hidden = historyWith(false, true);
        JsonNode shown = historyWith(true, true);

        assertFalse(times(hidden).contains("14:00:00"), "hidden with the zero usage lines");
        JsonNode first = shown.get("lines").get(6);
        assertEquals("14:00:00", first.at("/cells/0").asText());
        assertTrue(first.get("start").asBoolean(), "back with its gray flag");
        assertEquals("The program started here", first.get("title").asText());
    }

    @Test
    void aFailedStartupLineIsAFailedLineAndGoesWithThoseNotWithTheZeroUsageLines() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,,,,start-failed,60,5000",
                "2026-10-08 14:01:00,10.00,1000.00,USD,,60,400");

        JsonNode zeroOff = historyWith(false, true);
        JsonNode failedOff = historyWith(true, false);

        assertTrue(times(zeroOff).contains("14:00:00"), "not a zero usage line");
        assertEquals(2, zeroOff.get("lines").size(), "the failed startup line and the reading after it");
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

        JsonNode history = historyWith(false, true);

        assertEquals(1102, history.get("total").asInt());
        assertEquals(1, history.get("lines").size(), "the one change: the startup line is a zero usage line too");
        assertEquals("Showing 1 of 1,102 lines: 1,101 zero usage hidden.", history.get("note").asText());
    }

    @Test
    void ifEverythingIsHiddenTheNoteSaysSoAndThereAreNoLines() throws Exception {
        writeHistory(CSV_HEADER,
                "2026-10-08 14:00:00,,,,failed,60,5000",
                "2026-10-08 14:01:00,,,,failed,60,5000");

        JsonNode history = historyWith(true, false);

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

        JsonNode lines = json(get(app, "/api/history")).get("lines");

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

        JsonNode lines = json(get(app, "/api/history")).get("lines");

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
    void theHistoryEndpointGivesTheLinesNewestFirstAndFinished() throws Exception {
        writeHistory("datetime,used,limit,currency",
                "2026-10-08 14:24:53,186.02,1000.00,USD",
                "2026-10-08 14:26:53,186.12,1000.00,USD",
                "2026-10-08 14:25:53,186.07,1000.00,USD");
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);

        JsonNode history = json(get(app, "/api/history"));

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

        JsonNode history = json(get(app, "/api/history"));

        assertEquals("date time", history.at("/columns/0").asText());
        assertTrue(history.get("wide").asBoolean());
        java.util.Set<String> shown = new java.util.HashSet<>();
        history.get("lines").forEach(l -> shown.add(l.at("/cells/0").asText()));
        assertTrue(shown.contains("2026-10-08 14:24:53"), shown.toString());
    }

    @Test
    void aTimeThatCannotBeReadIsShownAsItIsEvenWithoutTheDate() throws Exception {
        writeHistory("datetime,used,limit,currency", "2026-10-08 14:24:53,186.02,1000.00,USD", "yesterday,1.00,2.00,USD");
        AppRuntime app = start(new FakeFetch());

        JsonNode lines = json(get(app, "/api/history")).get("lines");

        java.util.Set<String> shown = new java.util.HashSet<>();
        lines.forEach(l -> shown.add(l.at("/cells/0").asText()));
        assertTrue(shown.contains("yesterday"), shown.toString());
        assertTrue(shown.contains("14:24:53"), shown.toString());
    }

    @Test
    void theRememberedHeightsAreNotInTheSettingsTheViewShowsOrTakes() throws Exception {
        AppRuntime app = start(new FakeFetch());
        app.settings().storeHeight("history", 640);

        JsonNode settings = json(get(app, "/api/settings")).get("settings");
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

        JsonNode history = json(get(app, "/api/history"));

        assertEquals(2, history.get("total").asInt());
        assertEquals("186.02", history.at("/lines/0/cells/1").asText());
    }

    @Test
    void theHistoryEndpointSaysSoWhenThereIsNoHistory() throws Exception {
        FakeFetch plan = new FakeFetch();
        plan.answer = () -> NO_USAGE;
        AppRuntime app = start(plan);
        await(() -> app.service().state().snapshot() != null);

        JsonNode history = json(get(app, "/api/history"));

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

        JsonNode history = json(get(app, "/api/history"));

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

        JsonNode history = json(get(app, "/api/history"));

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
    void theHistoryIsReadOnlyAndTheLogTakesNothingButItsTwoChoices() throws Exception {
        AppRuntime app = start(new FakeFetch());

        HttpResponse<String> response = post(app, "/api/history", "{}");
        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElse(""));

        HttpResponse<String> put = send(app, "/api/log", HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(405, put.statusCode());
        assertEquals("GET, POST", put.headers().firstValue("Allow").orElse(""));
    }

    private static final String[] LEVELS = {
        "2026-10-11 13:24:01 INFO    [A] one",
        "2026-10-11 13:24:02 WARNING [A] two",
        "\tat a.B.c(B.java:1)",
        "2026-10-11 13:24:03 SEVERE  [A] three",
    };

    private static List<String> linesOf(JsonNode log) {
        return java.util.stream.StreamSupport.stream(log.get("lines").spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void theLogComesAtTheSavedLevelWhichIsInfoAndNoDateByDefault() throws Exception {
        writeLog(LEVELS);
        AppRuntime app = start(new FakeFetch());

        JsonNode log = json(get(app, "/api/log"));

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
        JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
        assertEquals("warning", file.get("logLevel").asText());
        assertTrue(file.get("logDate").asBoolean());

        JsonNode log = json(get(app, "/api/log"));
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

        JsonNode errors = json(get(app, "/api/log?level=error"));

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

        JsonNode settings = json(get(app, "/api/settings"));
        assertFalse(settings.get("settings").has("logLevel"), "the settings view does not have them");
        assertFalse(settings.get("defaults").has("logDate"));
        assertEquals(200, post(app, "/api/settings", settings.get("defaults").toString()).statusCode(), "Restore defaults, saved");

        JsonNode log = json(get(app, "/api/log"));
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
        assertEquals(16, new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json"))).get("listFontSize").asInt());
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

        JsonNode settings = json(get(app, "/api/settings"));
        assertFalse(settings.get("settings").has("listFontSize"), "the settings view does not have it");
        assertEquals(200, post(app, "/api/settings", settings.get("defaults").toString()).statusCode(), "Restore defaults, saved");

        assertEquals(18, json(get(app, "/api/config")).get("listFontSize").asInt());
    }

    // ---- the automatic interval

    @Test
    void automaticIsASettingOffByDefaultAndTheSettingsSayTheConfiguredIntervalBesideTheOneInForce() throws Exception {
        AppRuntime app = start(new FakeFetch(), 90);

        JsonNode body = json(get(app, "/api/settings"));

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
        JsonNode body = json(saved);
        assertTrue(body.at("/settings/autoInterval").asBoolean());
        assertEquals(90, body.get("configuredIntervalSeconds").asInt(), "the configured interval is kept");
        int inForce = body.at("/settings/usageIntervalSeconds").asInt();
        assertTrue(inForce >= 10 && inForce <= 60, "the search begins at 60 and goes down with each answer: " + inForce);
        JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
        assertTrue(file.get("autoInterval").asBoolean());
        assertEquals(90, file.get("usageIntervalSeconds").asInt(), "and is what the file has");

        // Switched off again, the configured interval is in force.
        JsonNode off = json(post(app, "/api/settings", allSettings(90, false, "hh:mm")));
        assertFalse(off.at("/settings/autoInterval").asBoolean());
        assertEquals(90, off.at("/settings/usageIntervalSeconds").asInt());
    }

    @Test
    void theSearchIsSavedWhenItMovesAndTheNextStartGoesOnFromIt() throws Exception {
        Files.writeString(dir.resolve("settings.json"),
                "{\"autoInterval\": true, \"autoIntervalSeconds\": 29, \"autoLowerSeconds\": 23, \"autoUpperSeconds\": 35}");
        AppRuntime app = start(new FakeFetch());

        // The first refresh was answered: (23 + 29) / 2 = 26, and the upper bound is 29.
        awaitSearch(26);

        JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
        assertEquals(26, file.get("autoIntervalSeconds").asInt());
        assertEquals(23, file.get("autoLowerSeconds").asInt());
        assertEquals(29, file.get("autoUpperSeconds").asInt());
        assertEquals(26, json(get(app, "/api/settings")).at("/settings/usageIntervalSeconds").asInt(), "the interval in force");
        assertEquals("26s", json(get(app, "/api/status")).at("/display/interval/text").asText(), "which the row shows");
    }

    private void awaitSearch(int seconds) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
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

        JsonNode about = json(get(app, "/api/about"));

        assertEquals("aip usage", about.get("name").asText());
        assertEquals(AppInfo.VERSION, about.get("version").asText());
        assertEquals(dir.resolve("settings.json").toAbsolutePath().normalize().toString(), about.at("/files/settings").asText(), "the full path");
        assertEquals(AppFiles.in(dir).log().toAbsolutePath().normalize().toString(), about.at("/files/log").asText());
        assertEquals(AppFiles.in(dir).history().toAbsolutePath().normalize().toString(), about.at("/files/history").asText());
        assertEquals(System.getProperty("java.version"), about.get("java").asText());
        assertFalse(about.get("javafx").asText().isBlank());
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

        @Override
        public void reveal(java.nio.file.Path file) throws IOException {
            asked.add("reveal " + file.toAbsolutePath().normalize());
            if (failure != null) {
                throw new IOException(failure);
            }
        }

        @Override
        public void copy(String text) throws IOException {
            asked.add("copy " + text);
            if (failure != null) {
                throw new IOException(failure);
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

        JsonNode debug = json(get(app, "/api/debug"));

        assertEquals("usage", debug.at("/settings/fakeAccount").asText());
        assertEquals(137, debug.at("/settings/fakeIncreaseCents").asInt());
        assertEquals("1000.00", debug.at("/settings/fakeBudget").asText(), "in dollars, as a finished text");
        assertEquals("186.02", debug.at("/settings/fakeStartAmount").asText());
        assertEquals(0, debug.at("/settings/fakeRefuseSeconds").asInt());
        assertEquals(0, debug.at("/settings/fakeDelayMillis").asInt());
        assertEquals(List.of("usage", "seat"), java.util.stream.StreamSupport.stream(debug.get("accounts").spliterator(), false).map(JsonNode::asText).toList());
        assertFalse(debug.get("scenarios").toString().contains("seat-based"), "the scenario for a seat-based account is gone");
    }

    @Test
    void theFakeBackendsOptionsAreSavedWithTheDebugSettingsAndWhatIsNotGivenStaysAsItIs() throws Exception {
        AppRuntime app = start(new FakeFetch(), LaunchOptions.parse(List.of("--debug")));

        HttpResponse<String> saved = post(app, "/api/debug", "{" + SWITCHES
                + ", \"fakeAccount\": \"seat\", \"fakeIncreaseCents\": 50, \"fakeBudget\": 250, \"fakeStartAmount\": 12.34, \"fakeRefuseSeconds\": 30, \"fakeDelayMillis\": 400}");

        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode settings = json(saved).get("settings");
        assertEquals("seat", settings.get("fakeAccount").asText());
        assertEquals("250.00", settings.get("fakeBudget").asText());
        assertEquals("12.34", settings.get("fakeStartAmount").asText());
        JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
        assertEquals("seat", file.get("fakeAccount").asText());
        assertEquals(50, file.get("fakeIncreaseCents").asInt());
        assertEquals(30, file.get("fakeRefuseSeconds").asInt());
        assertEquals(400, file.get("fakeDelayMillis").asInt());

        // An amount as the page sends it, the text that was typed.
        assertEquals("75.50", json(post(app, "/api/debug", "{" + SWITCHES + ", \"fakeBudget\": \"75.5\"}")).at("/settings/fakeBudget").asText());
        post(app, "/api/debug", "{" + SWITCHES + ", \"fakeBudget\": 250}");
        // The three switches alone: the rest is kept.
        JsonNode kept = json(post(app, "/api/debug", "{" + SWITCHES + "}")).get("settings");
        assertEquals("seat", kept.get("fakeAccount").asText());
        assertEquals(50, kept.get("fakeIncreaseCents").asInt());
        // And one of them alone.
        JsonNode one = json(post(app, "/api/debug", "{" + SWITCHES + ", \"fakeIncreaseCents\": 7}")).get("settings");
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

        JsonNode body = json(get(app, "/api/settings"));

        assertTrue(body.at("/settings/costMonthlyLimit").isNull());
        assertTrue(body.at("/settings/costDailyLimit").isNull());
        assertTrue(body.at("/settings/costDailyAutomatic").asBoolean());
        assertTrue(body.at("/defaults/costDailyAutomatic").asBoolean());
        assertTrue(body.get("costDailyWorkedOut").isNull(), "nothing to work it out from");
    }

    @Test
    void theLimitsAreSavedWithTheSettingsAndTheDailyOneIsWorkedOutFromTheMonthlyOne() throws Exception {
        AppRuntime app = start(new FakeFetch());
        String limits = allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"250\", \"costDailyLimit\": null, \"costDailyAutomatic\": true}");

        HttpResponse<String> saved = post(app, "/api/settings", limits);

        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode body = json(saved);
        assertEquals("250.00", body.at("/settings/costMonthlyLimit").asText(), "as a finished text");
        long worked = org.example.settings.CostControl.dailyFor(25_000, java.time.YearMonth.now());
        assertEquals(org.example.settings.CostControl.text(worked), body.get("costDailyWorkedOut").asText(), "250.00 over the weekdays of this month");
        JsonNode file = new ObjectMapper().readTree(Files.readString(dir.resolve("settings.json")));
        assertEquals(250.0, file.get("costMonthlyLimit").asDouble(), 0.0001);
        assertTrue(file.get("costDailyAutomatic").asBoolean());

        // A daily limit that is typed in: it is the one in force.
        JsonNode typed = json(post(app, "/api/settings",
                allSettings(60, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": 250, \"costDailyLimit\": \"12.5\", \"costDailyAutomatic\": false}")));
        assertEquals("12.50", typed.at("/settings/costDailyLimit").asText());
        assertEquals("12.50", typed.get("costDailyWorkedOut").asText());

        // Settings that say nothing of the limits leave them as they are; an empty limit takes it away.
        JsonNode kept = json(post(app, "/api/settings", allSettings(90, false, "hh:mm")));
        assertEquals("250.00", kept.at("/settings/costMonthlyLimit").asText());
        JsonNode none = json(post(app, "/api/settings", allSettings(90, false, "hh:mm").replace("}", ", \"costMonthlyLimit\": \"\", \"costDailyLimit\": null}")));
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

        JsonNode worked = json(get(app, "/api/cost/daily?monthly=250"));
        assertEquals(org.example.settings.CostControl.text(org.example.settings.CostControl.dailyFor(25_000, month)), worked.get("daily").asText());
        assertEquals(org.example.settings.CostControl.weekdays(month), worked.get("weekdays").asInt());
        assertEquals(worked.get("daily").asText(), json(get(app, "/api/cost/daily?monthly=250.00")).get("daily").asText());

        assertTrue(json(get(app, "/api/cost/daily?monthly=")).get("daily").isNull(), "no monthly limit: none to work out");
        assertTrue(json(get(app, "/api/cost/daily")).get("daily").isNull());
        assertEquals(400, get(app, "/api/cost/daily?monthly=abc").statusCode());
        assertEquals(400, get(app, "/api/cost/daily?monthly=0").statusCode());
        assertEquals(405, post(app, "/api/cost/daily", "{}").statusCode());
    }
}
