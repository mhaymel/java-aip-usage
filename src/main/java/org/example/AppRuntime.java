package org.example;

import org.example.settings.IntervalSettings;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
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
     * @param settingsFile where the UI's interval choices are kept
     * @param options command-line overrides
     * @param fetcher produces one usage reading; a parameter so tests need no network
     */
    static AppRuntime start(Path settingsFile, LaunchOptions options, Supplier<UsageSnapshot> fetcher)
            throws IOException {
        IntervalSettings settings =
                IntervalSettings.load(new SettingsStore(settingsFile), options.usageInterval(), options.pollInterval());
        UsageService service = new UsageService(fetcher, settings.usageInterval());
        settings.onUsageIntervalChange(service::setInterval);

        LocalWebServer server = LocalWebServer.start(new ApiHandler(service, settings));
        service.start();
        return new AppRuntime(settings, service, server);
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
