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
        IntervalSettings settings =
                IntervalSettings.load(new SettingsStore(files.settings()), options.usageInterval(), options.pollInterval());
        UsageHistory history = new UsageHistory(files.history());
        UsageService service = new UsageService(recording(fetcher, history), settings.usageInterval());
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
     * The fetcher, with each reading it returns added to the history. A history that cannot be
     * written is logged and nothing more: the reading is good, and the refresh did succeed.
     */
    private static Supplier<UsageSnapshot> recording(Supplier<UsageSnapshot> fetcher, UsageHistory history) {
        return () -> {
            UsageSnapshot snapshot = fetcher.get();
            try {
                history.append(snapshot);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not add the reading to " + history.file() + ": " + e.getMessage());
            }
            return snapshot;
        };
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
