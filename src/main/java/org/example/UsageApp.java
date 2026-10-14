package org.example;

import org.example.log.Log;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import org.example.fake.FakeBackend;
import org.example.fake.Scenario;
import org.example.settings.LaunchOptions;
import org.example.settings.SettingsStore;
import org.example.token.ClaudeTokenProvider;
import org.example.token.PlaceholderTokenProvider;
import org.example.token.TokenProvider;
import org.example.usage.ResponseLog;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetcher;

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

    private static final Log LOG = Log.of(UsageApp.class);

    /** The size before the page has said what it needs: a single row, roughly. */
    private static final int INITIAL_WIDTH = 420;

    private static final int INITIAL_HEIGHT = 50;

    /** How often the page is asked how big it needs to be. Cheap, and quick enough to feel instant. */
    private static final Duration FIT_INTERVAL = Duration.millis(150);

    /** The page's own answer to "how big do you need to be?", see docs/api.md. */
    private static final String ASK_CONTENT_SIZE = "window.contentSize ? window.contentSize() : null";


    /** The window that is running, so that a shutdown hook can release what it holds. */
    private static volatile UsageApp running;

    /**
     * The toolkit makes this object on its own thread and, as soon as it is made, names its application after this class,
     * which is what macOS then shows in the menu beside the apple. The toolkit takes a name only while it has none, so the
     * program's name is given here, where it is the first.
     */
    public UsageApp() {
        MacName.nameTheApplication();
    }

    private AppRuntime runtime;

    /** The window that is showing. It is another one after the title bar has come or gone; see {@link StageSwap}. */
    private Stage stage;

    /** Whether the page shows a panel that keeps the title bar whatever the setting says: any but the cost control. */
    private boolean panelKeepsTitleBar;

    /** Releases the runtime once, whether the window was closed or the process was told to stop. */
    private final RunOnce cleanup = new RunOnce(this::release);

    private Timeline fitTimer;

    private Optional<WindowFit.Size> appliedSize = Optional.empty();

    /** Which panel's height is remembered while it is open ({@code history} or {@code log}), or null. */
    private String rememberedPanel;

    /** What of the height the window was fitted to belongs to the views above the list whose height is remembered. */
    private int rememberedAbove;

    /** The least height of the content the person may drag the window to while its height can be dragged. */
    private double leastHeight = Double.MAX_VALUE;

    /** True while this class sets the window's size, so that its own changes are not taken for the person's drag. */
    private boolean applying;

    /** Fires when the person has stopped changing the window's height for a moment. */
    private final PauseTransition heightSettled = new PauseTransition(Duration.millis(500));

    /** How much bigger the stage is than its scene: the title bar and borders. NaN until measured. */
    private double decorationWidth = Double.NaN;

    private double decorationHeight = Double.NaN;

    /**
     * The size of the window's content with no panel open; with the frame added, what an open one may be shrunk to. Kept
     * without the frame, which is another when the title bar has come or gone.
     */
    private double closedWidth = Double.MAX_VALUE;

    private double closedHeight = Double.MAX_VALUE;

    private boolean fitFailureReported;

    private WebView page;

    /** What the window is to be restored to once the page can show it; {@code null} when nothing is, or it has been. */
    private SettingsStore.LastWindow restoring;

    /** Whether the page has opened the panel that is being restored, after which its size is applied once. */
    private boolean restoredViewOpen;

    /** What the window showed at the last tick, kept so that it can be stored when the program stops, from any thread. */
    private volatile String lastView = "";

    private volatile double lastX = Double.NaN;

    private volatile double lastY = Double.NaN;

    private volatile double lastContentWidth = Double.NaN;

    private volatile double lastContentHeight = Double.NaN;

    /** True for the moment at startup in which the window is held on top to bring it to the front; the setting waits for it. */
    private boolean raising;

    /** The style sheet of the window, for what it draws outside the page: the size of the tooltips. */
    static final String WINDOW_STYLE = UsageApp.class.getResource("/window.css").toExternalForm();

    @Override
    public void start(Stage stage) throws Exception {
        LOG.info("Starting java-aip-usage v" + AppInfo.VERSION);
        // Main has already checked these, so they are fine. They are read here, from the launch
        // arguments, which is how JavaFX hands a window what the command line said.
        LaunchOptions options = LaunchOptions.parse(getParameters().getRaw());
        try {
            ResponseLog responseLog = new ResponseLog();
            runtime = AppRuntime.start(AppFiles.inWorkingDirectory(), options, sources(options, responseLog), responseLog);
        } catch (Exception e) {
            LOG.error("Could not start", e);
            throw e;
        }
        running = this;

        WebView webView = new WebView();
        this.page = webView;
        logLoadResult(webView);
        webView.getEngine().load(runtime.baseUri().toString());

        this.stage = stage;
        stage.setTitle(AppInfo.windowTitle());
        // The program's own icon, in the place of the one Java gives every program. A window that takes this one's place,
        // for the title bar, is given the same icons (see StageSwap).
        stage.getIcons().setAll(AppIcons.load());
        if (!runtime.settings().current().showTitleBar()) {
            // The row alone, as configured; the style of a window is fixed before it is first shown.
            stage.initStyle(StageStyle.UNDECORATED);
        }
        Scene scene = new Scene(webView, INITIAL_WIDTH, INITIAL_HEIGHT);
        // What the window draws outside the page, the tooltips, has its style here. The scene is the same one when the
        // window is exchanged for the title bar, so it is given once.
        scene.getStylesheets().add(WINDOW_STYLE);
        stage.setScene(scene);
        // The window is as big as the page needs and no bigger, so there is nothing to drag,
        // except while a panel is shown; see resize, which sets the limits that say what may be dragged.
        stage.setResizable(true);
        restorePlace(stage);
        heightSettled.setOnFinished(event -> rememberDraggedHeight(this.stage));
        watchHeight(stage);
        stage.show();
        bringToFront();
        // On macOS the Dock would show the icon of a program that has none.
        MacName.showTheIconInTheDock();
        keepFitting(webView);
        letTheWindowBeDragged(webView);
    }

    /** The move that is under way, from the press of the mouse button that began it; {@code null} when there is none. */
    private WindowDrag drag;

    /**
     * Lets a window with no title bar be moved by dragging it: the button pressed anywhere in it but on a button, and the
     * mouse moved with the button held. A window with its title bar is moved by that, and a drag inside it moves nothing.
     * The page says whether a press is on something of its own to press, so a button does what it does and begins no move.
     * The moves are kept from the page while the window follows them, so that it does not select its text meanwhile; the
     * press and the release reach it as always.
     */
    private void letTheWindowBeDragged(WebView webView) {
        webView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            drag = null;
            if (event.getButton() == MouseButton.PRIMARY && !StageSwap.hasTitleBar(stage) && beginsADrag(webView, event.getX(), event.getY())) {
                drag = new WindowDrag(event.getScreenX(), event.getScreenY(), stage.getX(), stage.getY());
            }
        });
        webView.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            // The title bar may have come meanwhile, with a panel: the move ends there.
            if (drag == null || StageSwap.hasTitleBar(stage)) {
                drag = null;
                return;
            }
            WindowDrag.Place place = drag.at(event.getScreenX(), event.getScreenY());
            stage.setX(place.x());
            stage.setY(place.y());
            event.consume();
        });
        webView.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> drag = null);
    }

    /** Whether a press at this place of the page begins a move of the window; it does not where the page has something to press. */
    private static boolean beginsADrag(WebView webView, double x, double y) {
        try {
            return Boolean.TRUE.equals(webView.getEngine().executeScript("window.dragStartsAt(" + x + ", " + y + ")"));
        } catch (RuntimeException e) {
            // The page is not there yet, or not the one expected: nothing to move by.
            return false;
        }
    }

    /**
     * Opens a source of usage as the debug settings say: the fake backend, started here and stopped when the source is
     * left, which needs no token; or the real endpoint, at Anthropic's address or the one the command line names, with a
     * real token or, when the debug settings ask for it, a placeholder.
     */
    private static UsageSources.Factory sources(LaunchOptions options, ResponseLog responseLog) {
        return new UsageSources.Factory() {

            @Override
            public UsageSources.Source open(boolean fakeBackend, Scenario scenario, boolean fakeToken) throws Exception {
                return open(fakeBackend, scenario, fakeToken, org.example.fake.FakeOptions.DEFAULTS);
            }

            @Override
            public UsageSources.Source open(boolean fakeBackend, Scenario scenario, boolean fakeToken, org.example.fake.FakeOptions fake)
                    throws Exception {
            if (fakeBackend) {
                FakeBackend backend = FakeBackend.start(scenario, fake);
                return new UsageSources.Source(
                        new UsageFetcher(new PlaceholderTokenProvider(), UsageClient.create(UsageClient.usageUri(backend.baseUrl()), responseLog)),
                        true, Optional.of(backend.baseUrl()), backend);
            }
            URI base = options.baseUrl().orElse(UsageClient.DEFAULT_BASE_URL);
            TokenProvider tokens = fakeToken ? new PlaceholderTokenProvider() : ClaudeTokenProvider.create();
            return new UsageSources.Source(
                    new UsageFetcher(tokens, UsageClient.create(UsageClient.usageUri(base), responseLog)), false, Optional.of(base), null);
            }
        };
    }

    /** Notices when the person drags the height of an open history or log, so that it can be remembered. */
    private void watchHeight(Stage window) {
        window.heightProperty().addListener((observable, before, after) -> {
            if (!applying && rememberedPanel != null && window == stage) {
                heightSettled.playFromStart();
            }
        });
    }

    /**
     * Gives the window its title bar or takes it away, as the setting says, and gives it whatever the setting says for as
     * long as a panel is open that can be resized: a window with no title bar has no frame, so a panel in it could not be
     * dragged larger. The cost control, which cannot be resized, has none; it is moved by dragging it, like the row alone.
     * The window is exchanged for another to do it, with the same page in it at the same place.
     */
    private void followTitleBar() {
        if (runtime == null || raising) {
            return;
        }
        boolean wanted = runtime.settings().current().showTitleBar() || panelKeepsTitleBar;
        if (StageSwap.hasTitleBar(stage) == wanted) {
            return;
        }
        heightSettled.stop();
        applying = true;
        try {
            stage = StageSwap.replace(stage, wanted);
        } finally {
            applying = false;
        }
        watchHeight(stage);
        // The frame is another, so what the window is bigger by than its content is measured again: not here, where the
        // new window has not been laid out yet, but by resize on a later tick, as for the first window. The size the page
        // asked for is then applied again to the new window, with its limits.
        decorationWidth = Double.NaN;
        decorationHeight = Double.NaN;
        appliedSize = Optional.empty();
        LOG.debug(wanted ? "The window has its title bar" : "The window has no title bar");
    }

    /**
     * Brings the window to the front at startup. A program is not always let to the front by asking, on Windows in
     * particular when it was not started by the person's last click, but a window that is on top is in front: so it is
     * put on top for a moment and then left to the setting again.
     */
    private void bringToFront() {
        raising = true;
        stage.setAlwaysOnTop(true);
        stage.toFront();
        stage.requestFocus();
        PauseTransition settled = new PauseTransition(Duration.millis(400));
        settled.setOnFinished(event -> {
            raising = false;
            followStayOnTop();
        });
        settled.play();
    }

    /** Keeps the window above the others for as long as the setting says so, and lets it go when it no longer does. */
    private void followStayOnTop() {
        if (raising || runtime == null) {
            return;
        }
        boolean wanted = runtime.settings().current().stayOnTop();
        if (stage.isAlwaysOnTop() != wanted) {
            stage.setAlwaysOnTop(wanted);
            LOG.info(wanted ? "The window stays on top" : "The window no longer stays on top");
        }
    }

    /** Keeps the window the size the page asks for, as messages and the config fields come and go. */
    private void keepFitting(WebView webView) {
        // The same tick follows the setting that keeps the window on top: it is read, not listened for, since it changes rarely.
        // The title bar and the stage are looked at on the same tick: the window may be another one from one tick to the next.
        fitTimer = new Timeline(new KeyFrame(FIT_INTERVAL, event -> {
            fit(webView, stage);
            followTitleBar();
            followStayOnTop();
        }));
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
        Optional<WindowFit.Size> before = appliedSize;
        appliedSize = Optional.of(size);
        // A panel that stays open keeps the width the person gave the window: a new line, or any other change of what the page
        // asks for, applies the page's own width only when the panel was opened or replaced.
        // The same holds for the tab of the settings that can be dragged wider: its height follows its content at the width it has.
        boolean keepWidth = size.widthIsThePersons() && before.isPresent() && size.panel() != null
                && size.panel().equals(before.get().panel()) && before.get().resize() == size.resize();
        int contentHeight = size.height();
        // With several views the height that is the person's is that of the lowest list: the views above it keep theirs.
        String list = size.lowestList();
        boolean remembered = "history".equals(list) || "log".equals(list);
        // What of the height is not the list's own: nothing with a list alone, whose height is the window's, row included.
        int above = RememberedHeights.above(size.height(), size.several() ? size.alone() : size.height());
        if (remembered) {
            // The history and the log open at the height they were last left at, never less than the page's own.
            RememberedHeights.Opening opening =
                    RememberedHeights.open(size.height() - above, runtime.settings().storedHeight(list));
            contentHeight = Math.min(above + opening.height(), WindowFit.MAX.height());
            if (opening.store()) {
                runtime.settings().storeHeight(list, size.height() - above);
            }
        }
        rememberedPanel = remembered ? list : null;
        rememberedAbove = above;
        // A list that has views above it can be dragged down to one line of its own, and no further.
        leastHeight = size.several() && list != null ? above + closedHeight : closedHeight;
        heightSettled.stop();
        // Rounded up: a frame is not always a whole number of pixels wide, and a window a fraction too narrow shows a pixel
        // less of the page than it asked for, which is a pixel of the red border when there is one.
        double width = keepWidth ? stage.getWidth() : Math.ceil(size.width() + decorationWidth);
        double height = Math.ceil(contentHeight + decorationHeight);
        if (size.resize() == WindowFit.Resize.NONE) {
            // The content as it is with nothing open: what the person may shrink an open one back to.
            closedWidth = size.width();
            closedHeight = contentHeight;
        }
        applying = true;
        try {
            applySize(stage, size, width, height);
        } finally {
            applying = false;
        }
        LOG.debug("Window fitted to " + size.width() + "x" + contentHeight + (size.resizable() ? " (resizable: " + size.resize().name().toLowerCase() + ")" : "")
                + " (window " + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight()) + ")");
    }

    /** The person has settled on a height for the history or the log: remember it. */
    private void rememberDraggedHeight(Stage stage) {
        if (rememberedPanel == null || Double.isNaN(decorationHeight)) {
            return;
        }
        int pixels = (int) Math.round(stage.getHeight() - decorationHeight) - rememberedAbove;
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
                stage.setMinHeight(Math.min(leastHeight + decorationHeight, height));
            }
            case BOTH -> {
                stage.setMinWidth(Math.min(closedWidth + decorationWidth, width));
                stage.setMinHeight(Math.min(leastHeight + decorationHeight, height));
            }
            case WIDTH -> {
                // Wider than the page asked for, never narrower; the height is the content's, and not the person's.
                stage.setMinWidth(Math.min(Math.ceil(size.width() + decorationWidth), width));
                stage.setMinHeight(height);
                stage.setMaxHeight(height);
            }
        }
    }

    private void fit(WebView webView, Stage stage) {
        try {
            Object reported = webView.getEngine().executeScript(ASK_CONTENT_SIZE);
            WindowFit.parse(reported).ifPresent(size -> panelKeepsTitleBar = size.keepsTitleBar());
            WindowFit.next(reported, appliedSize).ifPresent(size -> resize(stage, size));
            followView(webView, reported);
        } catch (RuntimeException e) {
            // The page is not ready, or is being replaced. Say so once, not every tick.
            if (!fitFailureReported) {
                fitFailureReported = true;
                LOG.warning("Could not ask the page for its size", e);
            }
        }
    }

    /**
     * Puts the window where it was when the program last stopped, when that is asked for and the place is on a screen that
     * is there. The size and the panel follow with the page (see {@link #followView}).
     */
    private void restorePlace(Stage window) {
        SettingsStore.LastWindow last = runtime.settings().current().restoreWindow() ? runtime.settings().lastWindow() : null;
        restoring = last == null || (last.panel() == null && last.x() == null) ? null : last;
        if (restoring == null || restoring.x() == null || restoring.y() == null) {
            return;
        }
        double width = restoring.width() == null ? INITIAL_WIDTH : restoring.width();
        double height = restoring.height() == null ? INITIAL_HEIGHT : restoring.height();
        if (Screen.getScreensForRectangle(restoring.x(), restoring.y(), width, height).isEmpty()) {
            LOG.debug("The stored place of the window is on no screen: " + restoring.x() + ", " + restoring.y());
            return;
        }
        window.setX(restoring.x());
        window.setY(restoring.y());
    }

    /**
     * Each tick: remembers what the window shows, for storing when the program stops, and, while a restore is waiting, asks
     * the page to open the panel that was open and then applies the size once, when the window has the panel's size.
     */
    private void followView(WebView webView, Object reported) {
        try {
            Object view = webView.getEngine().executeScript(
                    "typeof window.currentView === 'function' ? String(window.currentView()) : ''");
            lastView = view == null ? "" : view.toString();
        } catch (RuntimeException e) {
            lastView = "";
        }
        if (!Double.isNaN(decorationWidth) && !Double.isNaN(decorationHeight)) {
            lastContentWidth = stage.getWidth() - decorationWidth;
            lastContentHeight = stage.getHeight() - decorationHeight;
        }
        lastX = stage.getX();
        lastY = stage.getY();
        if (restoring == null) {
            return;
        }
        if (restoring.panel() == null) {
            restoring = null;
            return;
        }
        if (!restoredViewOpen) {
            // The names are known names and commas, and nothing else: see SettingsStore.loadLast.
            Object done = webView.getEngine().executeScript("typeof window.restoreView === 'function' ? window.restoreView('"
                    + restoring.panel() + "', '" + (restoring.tab() == null ? "" : restoring.tab()) + "') : false");
            restoredViewOpen = Boolean.TRUE.equals(done);
            return;
        }
        Optional<WindowFit.Size> size = WindowFit.parse(reported);
        if (size.isPresent() && SettingsStore.LastWindow.shows(restoring.panel(), size.get().panels()) && appliedSize.isPresent()
                && !Double.isNaN(decorationWidth)) {
            applyRestoredSize(size.get());
            LOG.debug("The window is restored: " + restoring.panel() + ", " + restoring.width() + " x " + restoring.height()
                    + " at " + restoring.x() + ", " + restoring.y());
            restoring = null;
        }
    }

    /** The size the window had, where the panel can be sized: the height for the history and the log, the width too for the log; never over the screen. */
    private void applyRestoredSize(WindowFit.Size size) {
        Rectangle2D screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        applying = true;
        try {
            if (restoring.height() != null && size.resize() != WindowFit.Resize.NONE && size.resize() != WindowFit.Resize.FIXED) {
                double height = Math.min(restoring.height() + decorationHeight, screen.getHeight());
                stage.setHeight(Math.max(height, stage.getMinHeight()));
            }
            if (restoring.width() != null && size.resize() == WindowFit.Resize.BOTH) {
                double width = Math.min(restoring.width() + decorationWidth, screen.getWidth());
                stage.setWidth(Math.max(width, stage.getMinWidth()));
            }
        } finally {
            applying = false;
        }
    }

    /**
     * Stores what the window showed at the last tick, when the setting asks for it: the panel and tab, the size of the content and
     * the place of the window. Called when the program stops, by either way of stopping, so it reads nothing of the window itself.
     */
    private void storeWindow() {
        if (runtime == null || !runtime.settings().current().restoreWindow()) {
            return;
        }
        String[] parts = lastView.split(",", -1);
        // Several views are stored with commas between them, as the settings file names them: history,cost.
        String panel = parts[0].isEmpty() ? null : parts[0].replace('+', ',');
        String tab = parts.length > 1 && !parts[1].isEmpty() && panel != null ? parts[1] : null;
        runtime.settings().storeLastWindow(new SettingsStore.LastWindow(panel, tab,
                whole(lastContentWidth), whole(lastContentHeight), whole(lastX), whole(lastY)));
    }

    private static Integer whole(double value) {
        return Double.isNaN(value) ? null : (int) Math.round(value);
    }

    /** Reports whether the WebView could reach the local server. */
    private void logLoadResult(WebView webView) {
        Worker<Void> loadWorker = webView.getEngine().getLoadWorker();
        loadWorker.stateProperty().addListener((observable, previous, current) -> {
            switch (current) {
                case SUCCEEDED -> LOG.info("Frontend loaded");
                case FAILED -> LOG.error("Frontend failed to load", loadWorker.getException());
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
     * Stops the refresh service, the web server and the fake backend if one is in use,
     * logging that it does. Run once, by {@link #stop()} or by {@link #shutDown()},
     * whichever comes first, so a run that ends either way leaves nothing listening.
     */
    private void release() {
        LOG.info("Shutting down");
        storeWindow();
        if (runtime != null) {
            // The runtime closes what the usage came from as well, a fake backend among it.
            runtime.close();
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
