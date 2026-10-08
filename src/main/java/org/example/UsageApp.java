package org.example;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.example.settings.LaunchOptions;
import org.example.token.ClaudeTokenProvider;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetcher;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The JavaFX window of the usage monitor: a small window hosting a {@link WebView}
 * that loads the frontend from a loopback-only HTTP server. The window is sized to
 * fit what the page shows, and follows it as it changes.
 *
 * <p>Started by {@link Main}, which is deliberately not an {@code Application}
 * itself: a main class that is cannot be launched from the plain classpath, as an
 * IDE does, and stops with "JavaFX runtime components are missing".
 */
public class UsageApp extends Application {

    private static final Logger LOG = System.getLogger(UsageApp.class.getName());

    /** The size before the page has said what it needs: a single row, roughly. */
    private static final int INITIAL_WIDTH = 420;

    private static final int INITIAL_HEIGHT = 50;

    /** How often the page is asked how big it needs to be. Cheap, and quick enough to feel instant. */
    private static final Duration FIT_INTERVAL = Duration.millis(150);

    /** The page's own answer to "how big do you need to be?", see docs/api.md. */
    private static final String ASK_CONTENT_SIZE = "window.contentSize ? window.contentSize() : null";

    private static final String SETTINGS_FILE_NAME = "settings.json";

    /** Handed from {@link Main} to the instance JavaFX creates. */
    private static volatile LaunchOptions options = LaunchOptions.none();

    private AppRuntime runtime;

    private Timeline fitTimer;

    private Optional<WindowFit.Size> appliedSize = Optional.empty();

    /** How much bigger the stage is than its scene: the title bar and borders. NaN until measured. */
    private double decorationWidth = Double.NaN;

    private double decorationHeight = Double.NaN;

    private boolean fitFailureReported;

    /** Sets what the command line asked for; call before {@link Application#launch}. */
    static void configure(LaunchOptions launchOptions) {
        options = launchOptions;
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
        // The window is as big as the page needs and no bigger, so there is nothing to drag.
        stage.setResizable(false);
        stage.show();
        keepFitting(webView, stage);
    }

    /** Keeps the window the size the page asks for, as messages and the config fields come and go. */
    private void keepFitting(WebView webView, Stage stage) {
        fitTimer = new Timeline(new KeyFrame(FIT_INTERVAL, event -> fit(webView, stage)));
        fitTimer.setCycleCount(Animation.INDEFINITE);
        fitTimer.play();
    }

    /**
     * Sets the window so that its content area is {@code size}. The stage is bigger
     * than its scene by the title bar and borders, so that difference is added.
     * {@code Stage.sizeToScene()} is not used: it keeps a size the scene was created
     * with, so it never followed the page.
     */
    private void resize(Stage stage, WindowFit.Size size) {
        if (Double.isNaN(decorationWidth)) {
            // Measured once, while the stage and its scene still agree.
            double width = stage.getWidth() - stage.getScene().getWidth();
            double height = stage.getHeight() - stage.getScene().getHeight();
            if (Double.isNaN(width) || Double.isNaN(height) || width < 0 || height < 0) {
                return; // Not laid out yet; the next tick tries again.
            }
            decorationWidth = width;
            decorationHeight = height;
        }
        appliedSize = Optional.of(size);
        stage.setWidth(size.width() + decorationWidth);
        stage.setHeight(size.height() + decorationHeight);
        LOG.log(Level.INFO, "Window fitted to " + size.width() + "x" + size.height()
                + " (window " + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight()) + ")");
    }

    private void fit(WebView webView, Stage stage) {
        try {
            Object reported = webView.getEngine().executeScript(ASK_CONTENT_SIZE);
            WindowFit.next(reported, appliedSize).ifPresent(size -> resize(stage, size));
        } catch (RuntimeException e) {
            // The page is not ready, or is being replaced. Say so once, not every tick.
            if (!fitFailureReported) {
                fitFailureReported = true;
                LOG.log(Level.WARNING, "Could not ask the page for its size: " + e.getMessage());
            }
        }
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
        if (fitTimer != null) {
            fitTimer.stop();
        }
        if (runtime != null) {
            runtime.close();
        }
        Platform.exit();
    }
}
