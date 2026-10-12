package org.example;

import org.example.fake.Scenario;
import org.example.settings.IntervalSettings;
import org.example.settings.LaunchOptions;
import org.example.settings.Settings;
import org.example.settings.SettingsException;
import org.example.settings.SettingsStore;
import org.example.usage.ResponseLog;
import org.example.usage.UsageHistory;
import org.example.usage.UsageService;
import org.example.usage.UsageSnapshot;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Everything the application runs besides its window: the settings, the
 * refresh service and the local web server, wired together. Kept apart from
 * {@link Main} so it can be started and checked without JavaFX, and with a
 * stand-in for the network.
 *
 * <p>It also holds where usage comes from. That is fixed for a run that is not in debug mode. In debug
 * mode the debug settings say whether it is the fake backend, and it is opened anew when they change.
 */
final class AppRuntime implements AutoCloseable, UsageSources {

    private static final System.Logger LOG = System.getLogger(AppRuntime.class.getName());

    private final IntervalSettings settings;

    private final boolean debug;

    private final UsageSources.Factory factory;

    /** The history of real readings and that of invented ones, which are never in the same file. */
    private final UsageHistory realHistory;

    private final UsageHistory fakeHistory;

    /** How long the latest request that has ended took, for the row; negative until one has. */
    private final AtomicLong latestDuration = new AtomicLong(-1);

    private volatile Source source;

    private UsageService service;

    private LocalWebServer server;

    private AppRuntime(AppFiles files, IntervalSettings settings, boolean debug, UsageSources.Factory factory) {
        this.settings = settings;
        this.debug = debug;
        this.factory = factory;
        this.realHistory = new UsageHistory(files.history());
        this.fakeHistory = new UsageHistory(files.fakeHistory());
    }

    /**
     * Loads the settings, starts the web server, then begins fetching at once.
     *
     * @param files where the interval choice, the usage history and the log are kept
     * @param options the command line
     * @param fetcher produces one usage reading; a parameter so tests need no network
     */
    static AppRuntime start(AppFiles files, LaunchOptions options, Supplier<UsageSnapshot> fetcher)
            throws IOException {
        return start(files, options, fetcher, new ResponseLog());
    }

    /** @param responseLog switched by the setting to log the response; the client the fetcher uses asks it */
    static AppRuntime start(AppFiles files, LaunchOptions options, Supplier<UsageSnapshot> fetcher, ResponseLog responseLog)
            throws IOException {
        return start(files, options, fetcher, responseLog, Optional.empty());
    }

    /**
     * With one fetcher for every source, as the tests have it: whether the fake backend is "in use" is then only what the
     * debug settings say, which is enough to see what follows from it.
     *
     * @param fetchedFrom the base URL the fetcher fetches from, for the log to name; empty when
     *     the caller fitted a fetcher of its own and so has nowhere to name, as the tests do
     */
    static AppRuntime start(
            AppFiles files,
            LaunchOptions options,
            Supplier<UsageSnapshot> fetcher,
            ResponseLog responseLog,
            Optional<URI> fetchedFrom)
            throws IOException {
        return start(files, options, (fakeBackend, scenario, fakeToken) -> new Source(fetcher, fakeBackend, fetchedFrom, null), responseLog);
    }

    /**
     * @param factory opens the source of usage: once for a run that is not in debug mode, and in debug mode at startup and
     *     again whenever the debug settings change
     */
    /** The desktop asked for the About view; another one in a test, which must not open a file manager. */
    private volatile Desk desk = Desk.SYSTEM;

    @Override
    public Desk desk() {
        return desk;
    }

    void desk(Desk other) {
        this.desk = other;
    }

    /** The search for the automatic interval as it was saved, or from its beginning when what was saved cannot be one. */
    private static org.example.usage.IntervalSearch searchOf(IntervalSettings settings) {
        org.example.settings.SettingsStore.Search stored = settings.storedSearch();
        return org.example.usage.IntervalSearch.of(stored.interval(), stored.lower(), stored.upper());
    }

    static AppRuntime start(AppFiles files, LaunchOptions options, UsageSources.Factory factory, ResponseLog responseLog)
            throws IOException {
        LOG.log(System.Logger.Level.INFO, "Settings are stored in " + files.settings());
        IntervalSettings settings = IntervalSettings.load(new SettingsStore(files.settings()), OptionalInt.empty(), OptionalInt.empty());
        AppRuntime runtime = new AppRuntime(files, settings, options.debug(), factory);
        Settings saved = settings.current();
        // Without --debug the debug settings count for nothing: usage is real, with a real token, whatever the file says.
        runtime.source = options.debug()
                ? runtime.open(saved.fakeBackend(), saved.fakeScenario(), saved.fakeToken(), settings.fakeOptions())
                : runtime.open(false, Scenario.NORMAL, false, org.example.fake.FakeOptions.DEFAULTS);
        if (options.debug()) {
            LOG.log(System.Logger.Level.INFO, "Debug mode (--debug)");
        }
        runtime.logSource(saved);

        UsageService service = new UsageService(runtime::fetchAndRecord, settings.usageInterval());
        runtime.service = service;
        runtime.historyInUse().latest().ifPresent(earlier -> {
            service.restore(earlier);
            LOG.log(System.Logger.Level.INFO, "Showing the newest reading in the usage history until the first refresh is done");
        });
        settings.onUsageIntervalChange(service::setInterval);
        responseLog.set(saved.logResponse());
        // The automatic interval: the search goes on from where it was saved, and is saved again whenever it moves.
        service.onSearchMoved(moved -> settings.storeSearch(moved.interval(), moved.lower(), moved.upper()));
        if (saved.autoInterval()) {
            service.setAutomatic(searchOf(settings));
        }
        settings.onChange(changed -> {
            responseLog.set(changed.logResponse());
            if (changed.autoInterval() != service.automatic()) {
                service.setAutomatic(changed.autoInterval() ? searchOf(settings) : null);
            }
        });

        runtime.server = LocalWebServer.start(new ApiHandler(service, settings, files, runtime));
        service.start();
        return runtime;
    }

    private Source open(boolean fakeBackend, Scenario scenario, boolean fakeToken, org.example.fake.FakeOptions fake) throws IOException {
        try {
            return factory.open(fakeBackend, scenario, fakeToken, fake);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Says where the readings come from and where they are written, at startup and at every change of the source. A fake
     * run says so in as many words: a reading it invented must not be taken for a real one by anybody reading the log
     * afterwards.
     */
    private void logSource(Settings saved) {
        Source now = source;
        LOG.log(System.Logger.Level.INFO, "Usage history is written to " + historyInUse().file());
        if (now.fake()) {
            LOG.log(System.Logger.Level.INFO,
                    "The fake backend is in use: these readings are invented (debug settings, scenario "
                            + saved.fakeScenario().optionName() + ")");
        }
        now.base().ifPresent(uri -> LOG.log(System.Logger.Level.INFO, "Usage is fetched from " + uri));
        if (now.fake() || (debug && saved.fakeToken())) {
            LOG.log(System.Logger.Level.INFO,
                    "No token is obtained: a placeholder is sent instead (debug settings, "
                            + (now.fake() ? "the fake backend" : "the placeholder token") + ")");
        }
    }

    private UsageHistory historyInUse() {
        return source.fake() ? fakeHistory : realHistory;
    }

    /**
     * One query of the source in use, added to its history: the reading it returns, or a row for the failure, which is
     * then thrown on as it was. The row says how long the query took and what the interval was, and the row of the window
     * is told the same duration. A history that cannot be written is logged and nothing more: the reading is good, and the
     * refresh did succeed. A query cut short because the program is stopping is not a failure to record, and neither is
     * one whose source was left while it was under way: its answer belongs to neither file.
     */
    private UsageSnapshot fetchAndRecord() {
        Source asked = source;
        UsageHistory history = asked.fake() ? fakeHistory : realHistory;
        // The interval in force: the configured one, or the one the program has found while it searches for it itself.
        int interval = service != null && service.automatic() ? service.effectiveIntervalSeconds() : settings.usageSeconds();
        long started = System.nanoTime();
        UsageSnapshot snapshot;
        try {
            snapshot = asked.fetcher().get();
        } catch (RuntimeException failure) {
            if (!Thread.currentThread().isInterrupted() && asked == source) {
                long took = millisSince(started);
                latestDuration.set(took);
                record(history, () -> history.appendFailure(Instant.now(), interval, took));
            }
            throw failure;
        }
        if (asked == source) {
            long took = millisSince(started);
            latestDuration.set(took);
            record(history, () -> history.append(snapshot, interval, took));
        }
        return snapshot;
    }

    private interface Write {
        void run() throws IOException;
    }

    private static void record(UsageHistory history, Write write) {
        try {
            write.run();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not add the reading to " + history.file() + ": " + e.getMessage());
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    // ---- what the API asks about the source

    @Override
    public boolean debug() {
        return debug;
    }

    @Override
    public boolean fake() {
        return source.fake();
    }

    @Override
    public Path history() {
        return historyInUse().file();
    }

    @Override
    public long latestDurationMillis() {
        return latestDuration.get();
    }

    /**
     * The new source is opened first, so that one that cannot be opened changes nothing; then the settings are saved; then
     * the old source is left and closed. The reading on show belongs to the old source, so the newest of the new source's
     * own history takes its place, or nothing, and a refresh follows at once. A request under way is given up.
     */
    @Override
    public org.example.fake.FakeOptions fakeOptions() {
        return settings.fakeOptions();
    }

    @Override
    public synchronized void applyDebug(boolean fakeBackend, Scenario scenario, boolean fakeToken) {
        applyDebug(fakeBackend, scenario, fakeToken, settings.fakeOptions());
    }

    @Override
    public synchronized void applyDebug(boolean fakeBackend, Scenario scenario, boolean fakeToken, org.example.fake.FakeOptions fake) {
        if (!debug) {
            throw new IllegalStateException("not in debug mode");
        }
        Settings before = settings.current();
        Settings wanted = before.withDebug(fakeBackend, scenario, fakeToken);
        org.example.fake.FakeOptions fakeBefore = settings.fakeOptions();
        if (wanted.equals(before) && fake.equals(fakeBefore)) {
            return;
        }
        if (wanted.equals(before)) {
            // Only how the fake backend answers has changed: it is saved, and the backend that runs is told, so that the
            // amount it has reached goes on from where it is. A refresh follows, as after any change of the debug settings.
            settings.storeFake(fake);
            if (source != null && source.resource() instanceof org.example.fake.FakeBackend running) {
                running.configure(fake);
            }
            service.refreshNow();
            return;
        }
        Source fresh;
        try {
            fresh = open(fakeBackend, scenario, fakeToken, fake);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "The debug settings could not be applied: " + e.getMessage());
            throw new SettingsException("The debug settings could not be applied: " + e.getMessage(), e);
        }
        try {
            settings.apply(wanted);
            if (!fake.equals(fakeBefore)) {
                settings.storeFake(fake);
            }
        } catch (RuntimeException e) {
            close(fresh);
            throw e;
        }
        Source old = source;
        source = fresh;
        close(old);
        latestDuration.set(-1);
        logSource(wanted);
        service.restart(historyInUse().latest().orElse(null));
    }

    private static void close(Source left) {
        if (left != null && left.resource() != null) {
            try {
                left.resource().close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Could not close what the usage came from: " + e.getMessage());
            }
        }
    }

    URI baseUri() {
        return server.baseUri();
    }

    IntervalSettings settings() {
        return settings;
    }

    UsageService service() {
        return service;
    }

    /** Stops fetching first, interrupting a request in flight, then the server, then what the usage came from. */
    @Override
    public void close() {
        service.close();
        server.close();
        close(source);
    }
}
