package org.example;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.example.fake.FakeBackend;
import org.example.fake.Scenario;
import org.example.settings.LaunchOptions;
import org.example.token.ClaudeTokenProvider;
import org.example.token.PlaceholderTokenProvider;
import org.example.token.TokenProvider;
import org.example.usage.ResponseLog;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetcher;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
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


    /** The window that is running, so that a shutdown hook can release what it holds. */
    private static volatile UsageApp running;

    private AppRuntime runtime;

    /** Only ever set by {@code --fake-backend}; null on every other run. */
    private FakeBackend fakeBackend;

    /** Releases the runtime once, whether the window was closed or the process was told to stop. */
    private final RunOnce cleanup = new RunOnce(this::release);

    private Timeline fitTimer;

    private Optional<WindowFit.Size> appliedSize = Optional.empty();

    /** Which panel's height is remembered while it is open ({@code history} or {@code log}), or null. */
    private String rememberedPanel;

    /** True while this class sets the window's size, so that its own changes are not taken for the person's drag. */
    private boolean applying;

    /** Fires when the person has stopped changing the window's height for a moment. */
    private final PauseTransition heightSettled = new PauseTransition(Duration.millis(500));

    /** How much bigger the stage is than its scene: the title bar and borders. NaN until measured. */
    private double decorationWidth = Double.NaN;

    private double decorationHeight = Double.NaN;

    /** The size of the window with no panel open, as decorated; what an open one may be shrunk to. */
    private double closedWidth = Double.MAX_VALUE;

    private double closedHeight = Double.MAX_VALUE;

    private boolean fitFailureReported;

    @Override
    public void start(Stage stage) throws Exception {
        LOG.log(Level.INFO, "Starting java-aip-usage v" + AppInfo.VERSION);
        // Main has already checked these, so they are fine. They are read here, from the launch
        // arguments, so that Main hands this class nothing before logging is set up: merely
        // loading this class starts the logging system.
        LaunchOptions options = LaunchOptions.parse(getParameters().getRaw());
        try {
            ResponseLog responseLog = new ResponseLog();
            // Started before the fetcher, which needs the address it chose.
            if (options.fakeBackend()) {
                fakeBackend = FakeBackend.start(options.fakeScenario().orElse(Scenario.NORMAL));
            }
            URI base = fakeBackend != null
                    ? fakeBackend.baseUrl()
                    : options.baseUrl().orElse(UsageClient.DEFAULT_BASE_URL);
            TokenProvider tokens = options.placeholderToken()
                    ? new PlaceholderTokenProvider()
                    : ClaudeTokenProvider.create();
            runtime = AppRuntime.start(
                    AppFiles.inWorkingDirectory(),
                    options,
                    new UsageFetcher(tokens, UsageClient.create(UsageClient.usageUri(base), responseLog)),
                    responseLog,
                    Optional.of(base));
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Could not start", e);
            throw e;
        }
        running = this;

        WebView webView = new WebView();
        logLoadResult(webView);
        webView.getEngine().load(runtime.baseUri().toString());

        stage.setTitle(AppInfo.windowTitle());
        stage.setScene(new Scene(webView, INITIAL_WIDTH, INITIAL_HEIGHT));
        // The window is as big as the page needs and no bigger, so there is nothing to drag,
        // except while a panel is shown; see resize, which sets the limits that say what may be dragged.
        stage.setResizable(true);
        heightSettled.setOnFinished(event -> rememberDraggedHeight(stage));
        stage.heightProperty().addListener((observable, before, after) -> {
            if (!applying && rememberedPanel != null) {
                heightSettled.playFromStart();
            }
        });
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
        int contentHeight = size.height();
        boolean remembered = "history".equals(size.panel()) || "log".equals(size.panel());
        if (remembered) {
            // The history and the log open at the height they were last left at, never less than the page's own.
            RememberedHeights.Opening opening =
                    RememberedHeights.open(size.height(), runtime.settings().storedHeight(size.panel()));
            contentHeight = Math.min(opening.height(), WindowFit.MAX.height());
            if (opening.store()) {
                runtime.settings().storeHeight(size.panel(), size.height());
            }
        }
        rememberedPanel = remembered ? size.panel() : null;
        heightSettled.stop();
        double width = size.width() + decorationWidth;
        double height = contentHeight + decorationHeight;
        if (size.resize() == WindowFit.Resize.NONE) {
            // The window as it is with nothing open: what the person may shrink an open one back to.
            closedWidth = width;
            closedHeight = height;
        }
        applying = true;
        try {
            applySize(stage, size, width, height);
        } finally {
            applying = false;
        }
        LOG.log(Level.INFO, "Window fitted to " + size.width() + "x" + contentHeight + (size.resizable() ? " (resizable: " + size.resize().name().toLowerCase() + ")" : "")
                + " (window " + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight()) + ")");
    }

    /** The person has settled on a height for the history or the log: remember it. */
    private void rememberDraggedHeight(Stage stage) {
        if (rememberedPanel == null || Double.isNaN(decorationHeight)) {
            return;
        }
        int pixels = (int) Math.round(stage.getHeight() - decorationHeight);
        if (RememberedHeights.shouldStore(pixels, runtime.settings().storedHeight(rememberedPanel))) {
            runtime.settings().storeHeight(rememberedPanel, pixels);
        }
    }

    private void applySize(Stage stage, WindowFit.Size size, double width, double height) {
        // The limits are opened wide first, so that they never forbid the size set next.
        stage.setMinWidth(0);
        stage.setMaxWidth(Double.MAX_VALUE);
        stage.setMinHeight(0);
        stage.setMaxHeight(Double.MAX_VALUE);
        stage.setWidth(width);
        stage.setHeight(height);
        // The window stays resizable and the limits say what of it the person may change; toggling
        // the window's resizable flag instead is not something every desktop follows.
        switch (size.resize()) {
            case NONE, FIXED -> {
                stage.setMinWidth(width);
                stage.setMaxWidth(width);
                stage.setMinHeight(height);
                stage.setMaxHeight(height);
            }
            case HEIGHT -> {
                // The width is fixed; the height may be anything from the closed window's up.
                stage.setMinWidth(width);
                stage.setMaxWidth(width);
                stage.setMinHeight(Math.min(closedHeight, height));
            }
            case BOTH -> {
                stage.setMinWidth(Math.min(closedWidth, width));
                stage.setMinHeight(Math.min(closedHeight, height));
            }
        }
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
        if (fitTimer != null) {
            fitTimer.stop();
        }
        cleanup.run();
        Platform.exit();
    }

    /**
     * Stops the refresh service, the web server and the fake backend if one was started,
     * logging that it does. Run once, by {@link #stop()} or by {@link #shutDown()},
     * whichever comes first, so a run that ends either way leaves nothing listening.
     */
    private void release() {
        LOG.log(Level.INFO, "Shutting down");
        if (runtime != null) {
            runtime.close();
        }
        if (fakeBackend != null) {
            fakeBackend.close();
        }
    }

    /**
     * What the shutdown hook calls when the process is told to stop without the window being
     * closed. Does nothing if the window was never opened or has already been cleaned up.
     */
    static void shutDown() {
        UsageApp app = running;
        if (app != null) {
            app.cleanup.run();
        }
    }
}
