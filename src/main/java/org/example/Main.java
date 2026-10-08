package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import org.example.settings.LaunchOptions;
import org.example.token.ClaudeTokenProvider;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetcher;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;

/**
 * Desktop shell for the usage monitor: a minimal JavaFX window hosting a
 * {@link WebView} that loads the frontend from a loopback-only HTTP server.
 */
public class Main extends Application {

    private static final Logger LOG = System.getLogger(Main.class.getName());

    private static final int INITIAL_WIDTH = 560;

    private static final int INITIAL_HEIGHT = 560;

    private static final String SETTINGS_FILE_NAME = "settings.json";

    /** Handed from {@link #main} to the instance JavaFX creates. */
    private static volatile LaunchOptions options = LaunchOptions.none();

    private AppRuntime runtime;

    public static void main(String[] args) {
        LaunchOptions parsed;
        try {
            parsed = LaunchOptions.parse(List.of(args));
        } catch (LaunchOptions.InvalidOptionsException e) {
            System.err.println(e.getMessage());
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (parsed.help()) {
            System.out.println(LaunchOptions.USAGE);
            // Returning is not enough: on macOS JavaFX has already started a
            // non-daemon keep-alive thread, which would leave the JVM running.
            System.exit(0);
            return;
        }
        options = parsed;

        // Relative to the working directory, which is the project root for
        // `./gradlew run` and for IDE run configurations.
        try (Logging logging = Logging.install(Path.of(Logging.LOG_FILE_NAME).toAbsolutePath())) {
            launch(args);
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        LOG.log(Level.INFO, "Starting java-aip-usage");
        try {
            runtime = AppRuntime.start(
                    Path.of(SETTINGS_FILE_NAME).toAbsolutePath(),
                    options,
                    new UsageFetcher(ClaudeTokenProvider.create(), UsageClient.create()));
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Could not start", e);
            throw e;
        }

        WebView webView = new WebView();
        logLoadResult(webView);
        webView.getEngine().load(runtime.baseUri().toString());

        stage.setTitle("java-aip usage");
        stage.setScene(new Scene(webView, INITIAL_WIDTH, INITIAL_HEIGHT));
        stage.show();
    }

    /** Reports whether the WebView could reach the local server. */
    private void logLoadResult(WebView webView) {
        Worker<Void> loadWorker = webView.getEngine().getLoadWorker();
        loadWorker.stateProperty().addListener((observable, previous, current) -> {
            switch (current) {
                case SUCCEEDED -> LOG.log(Level.INFO, "Frontend loaded");
                case FAILED -> LOG.log(Level.ERROR, "Frontend failed to load", loadWorker.getException());
                default -> { // RUNNING and the other transient states are not worth logging.
                }
            }
        });
    }

    /**
     * Closing the window must terminate the program, so release the server and
     * its background work here rather than leaving it to JVM shutdown.
     */
    @Override
    public void stop() {
        LOG.log(Level.INFO, "Shutting down");
        if (runtime != null) {
            runtime.close();
        }
        Platform.exit();
    }
}
