package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;

/**
 * Desktop shell for the usage monitor: a minimal JavaFX window hosting a
 * {@link WebView} that loads the frontend from a loopback-only HTTP server.
 */
public class Main extends Application {

    private static final Logger LOG = System.getLogger(Main.class.getName());

    private static final int INITIAL_WIDTH = 480;

    private static final int INITIAL_HEIGHT = 320;

    private LocalWebServer server;

    public static void main(String[] args) {
        // Relative to the working directory, which is the project root for
        // `./gradlew run` and for IDE run configurations.
        try (Logging logging = Logging.install(Path.of(Logging.LOG_FILE_NAME).toAbsolutePath())) {
            launch(args);
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        LOG.log(Level.INFO, "Starting java-aip-usage");
        server = LocalWebServer.start();

        WebView webView = new WebView();
        logLoadResult(webView);
        webView.getEngine().load(server.baseUri().toString());

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
        if (server != null) {
            server.close();
        }
        Platform.exit();
    }
}
