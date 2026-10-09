package org.example;

import org.example.settings.IntervalSettings;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
import org.example.usage.UsageHistory;
import org.example.usage.UsageService;
import org.example.usage.UsageSnapshot;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Everything the application runs besides its window: the settings, the
 * refresh service and the local web server, wired together. Kept apart from
 * {@link Main} so it can be started and checked without JavaFX, and with a
 * stand-in for the network.
 */
final class AppRuntime implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AppRuntime.class.getName());

    private final IntervalSettings settings;

    private final UsageService service;

    private final LocalWebServer server;

    private AppRuntime(IntervalSettings settings, UsageService service, LocalWebServer server) {
        this.settings = settings;
        this.service = service;
        this.server = server;
    }

    /**
     * Loads the settings, starts the web server, then begins fetching at once.
     *
     * @param files where the interval choice, the usage history and the log are kept
     * @param options command-line overrides
     * @param fetcher produces one usage reading; a parameter so tests need no network
     */
    static AppRuntime start(AppFiles files, LaunchOptions options, Supplier<UsageSnapshot> fetcher)
            throws IOException {
        LOG.log(System.Logger.Level.INFO, "Usage history is written to " + files.history());
        LOG.log(System.Logger.Level.INFO, "Settings are stored in " + files.settings());
        IntervalSettings settings =
                IntervalSettings.load(new SettingsStore(files.settings()), options.usageInterval(), options.pollInterval());
        UsageHistory history = new UsageHistory(files.history());
        UsageService service = new UsageService(recording(fetcher, history, settings::usageSeconds), settings.usageInterval());
        history.latest().ifPresent(earlier -> {
            service.restore(earlier);
            LOG.log(System.Logger.Level.INFO, "Showing the newest reading in the usage history until the first refresh is done");
        });
        settings.onUsageIntervalChange(service::setInterval);

        LocalWebServer server = LocalWebServer.start(new ApiHandler(service, settings, files));
        service.start();
        return new AppRuntime(settings, service, server);
    }

    /**
     * The fetcher, with each query it makes added to the history: the reading it returns, or a row for
     * the failure, which is then thrown on as it was. The row says how long the query took and what
     * the interval was. A history that cannot be written is logged and nothing more: the reading is
     * good, and the refresh did succeed. A query cut short because the program is stopping is not a failure to record.
     */
    private static Supplier<UsageSnapshot> recording(
            Supplier<UsageSnapshot> fetcher, UsageHistory history, IntSupplier intervalSeconds) {
        return () -> {
            int interval = intervalSeconds.getAsInt();
            long started = System.nanoTime();
            UsageSnapshot snapshot;
            try {
                snapshot = fetcher.get();
            } catch (RuntimeException failure) {
                if (!Thread.currentThread().isInterrupted()) {
                    record(history, () -> history.appendFailure(Instant.now(), interval, millisSince(started)));
                }
                throw failure;
            }
            record(history, () -> history.append(snapshot, interval, millisSince(started)));
            return snapshot;
        };
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

    URI baseUri() {
        return server.baseUri();
    }

    IntervalSettings settings() {
        return settings;
    }

    UsageService service() {
        return service;
    }

    /** Stops fetching first, interrupting a request in flight, then the server. */
    @Override
    public void close() {
        service.close();
        server.close();
    }
}
