package org.example;

import org.example.log.Log;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.robot.Robot;
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

    /** The most a window's frame, its title bar and borders, can be in pixels, in either direction: more is a measure taken too soon. */
    private static final double MOST_FRAME = 150;

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

    /** Whether the page shows a panel that keeps the title bar whatever the setting says: the settings (since 0.87; any but the cost control until then). */
    private boolean panelKeepsTitleBar;

    /** Releases the runtime once, whether the window was closed or the process was told to stop. */
    private final RunOnce cleanup = new RunOnce(this::release);

    private Timeline fitTimer;

    private Optional<WindowFit.Size> appliedSize = Optional.empty();

    /** Which panel's height is remembered while it is open ({@code history} or {@code log}), or null. */
    private String rememberedPanel;

    /** What of the height the window was fitted to belongs to the views above the list whose height is remembered. */
    private int rememberedAbove;

    /** How many pixels the row was away by when the window was fitted, which a dragged height is without (since 0.85). */
    private int rememberedAway;

    /** Whether the pointer has been outside the window for a moment, for the row to go (since 0.85). */
    private final PointerAway pointer = new PointerAway();

    /** What the page was last told of the pointer, so that it is told only when that changes; null until it has been told. */
    private Boolean awayTold;
    /** Whether the page was last told the window is minimized; null until it has taken it. */
    private Boolean minimizedTold;

    /** Draws where the window is when it comes to the front (since 0.89). */
    private final Locator locator = new Locator();

    /** Whether the window had the focus at the last tick. */
    private boolean focusedBefore;

    /** When the mouse was last pressed in the window, in milliseconds of {@link System#currentTimeMillis()}; 0 before. */
    private volatile long pressedAt;

    /** How near to a press of the mouse the window may come to the front and still have been clicked into, in milliseconds. */
    private static final long CLICK_MILLIS = 500;

    /** Where the pointer is on the screen; made on the toolkit's thread the first time it is needed. */
    private Robot robot;

    /** The least height of the content the person may drag the window to while its height can be dragged. */
    private double leastHeight = Double.MAX_VALUE;

    /** True while this class sets the window's size, so that its own changes are not taken for the person's drag. */
    private boolean applying;

    /** Fires when the person has stopped changing the window's height for a moment. */
    private final PauseTransition heightSettled = new PauseTransition(Duration.millis(500));

    /** Fires when the person has stopped changing the width of the log shown alone for a moment (since 0.93). */
    private final PauseTransition widthSettled = new PauseTransition(Duration.millis(500));

    /**
     * The size last applied, for where the window goes (since 0.93). Unlike {@link #appliedSize} it is kept when the title bar
     * comes or goes, so that the views opened before the window was exchanged are still known after it.
     */
    private Optional<WindowFit.Size> placedSize = Optional.empty();

    /**
     * Where the window of the row alone was on the screen when a view was opened, its seen top-left corner (see {@link
     * StageSwap#corner}), so that closing the views puts it back there (since 0.93); null with no view open. The corner and not
     * the content's place since 0.98, as the title bar coming or going keeps the corner and moves the content.
     */
    private double[] rowPlace;

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
        // The version of Claude Code, at every start, whatever the source of the readings.
        logClaudeVersionOnce();
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
        keepTooltipsAsSet(webView);
        // What the window draws outside the page, the tooltips, has its style here. The scene is the same one when the
        // window is exchanged for the title bar, so it is given once.
        scene.getStylesheets().add(WINDOW_STYLE);
        stage.setScene(scene);
        // The window is as big as the page needs and no bigger, so there is nothing to drag,
        // except while a panel is shown; see resize, which sets the limits that say what may be dragged.
        stage.setResizable(true);
        restorePlace(stage);
        heightSettled.setOnFinished(event -> rememberDraggedHeight(this.stage));
        widthSettled.setOnFinished(event -> rememberDraggedWidth(this.stage));
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
     * The resize by an edge that is under way: where the mouse was at the press (down the screen for the bottom edge, since 0.89,
     * across it for the right one, since 0.91), the height or the width the window had then, and 0 for the bottom edge or 1 for
     * the right one; {@code null} when there is none.
     */
    private double[] edgeDrag;

    /**
     * Lets a window with no title bar be moved by dragging it: the button pressed anywhere in it but on a button, and the
     * mouse moved with the button held. A window with its title bar is moved by that, and a drag inside it moves nothing.
     * The page says whether a press is on something of its own to press, so a button does what it does and begins no move.
     * The moves are kept from the page while the window follows them, so that it does not select its text meanwhile; the
     * press and the release reach it as always.
     */
    private void letTheWindowBeDragged(WebView webView) {
        webView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            // A window clicked into shows no rectangle of where it is (since 0.90): the time of the press says it was.
            pressedAt = System.currentTimeMillis();
            drag = null;
            edgeDrag = null;
            if (event.getButton() != MouseButton.PRIMARY || StageSwap.hasTitleBar(stage)) {
                return;
            }
            String edge = edgeAt(webView, event.getX(), event.getY());
            if ("s".equals(edge)) {
                // The bottom edge makes the lowest list taller and shorter: the window is resized, not moved (since 0.89). A
                // height the person settles on is remembered as one dragged by the frame is.
                edgeDrag = new double[] {event.getScreenY(), stage.getHeight(), 0};
            } else if ("e".equals(edge)) {
                // The right edge makes the window wider and narrower while the log (since 0.91) or the burndown (since 0.99) is
                // shown; the width is kept as one
                // dragged by the frame is.
                edgeDrag = new double[] {event.getScreenX(), stage.getWidth(), 1};
            } else if (beginsADrag(webView, event.getX(), event.getY())) {
                drag = new WindowDrag(event.getScreenX(), event.getScreenY(), stage.getX(), stage.getY());
            }
        });
        webView.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (edgeDrag != null) {
                if (StageSwap.hasTitleBar(stage)) {
                    edgeDrag = null;
                    return;
                }
                if (edgeDrag[2] == 1) {
                    stage.setWidth(edgeWidth(edgeDrag[1] + event.getScreenX() - edgeDrag[0]));
                } else {
                    stage.setHeight(edgeHeight(edgeDrag[1] + event.getScreenY() - edgeDrag[0]));
                }
                event.consume();
                return;
            }
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
        webView.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            drag = null;
            edgeDrag = null;
        });
    }

    /**
     * The height the bottom edge may give the window: never less than the window may be (the list's opening height, which
     * the limits of the last fit say) and never more than they allow, the most the program makes a window, or the screen has
     * room for below the window's top.
     */
    private double edgeHeight(double wanted) {
        Rectangle2D screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        // No more than the screen has room for below the window's top (since 0.92 no fixed most of its own).
        double most = Math.min(stage.getMaxHeight(), screen.getMaxY() - stage.getY());
        return Math.max(stage.getMinHeight(), Math.min(most, wanted));
    }

    /** The width the right edge may give the window: within the limits of the last fit and the screen's room at the right. */
    private double edgeWidth(double wanted) {
        Rectangle2D screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        double most = Math.min(stage.getMaxWidth(), screen.getMaxX() - stage.getX());
        return Math.max(stage.getMinWidth(), Math.min(most, wanted));
    }

    /**
     * Which edge of the page a press at this place is on, which resizes the window: {@code s}, the bottom (since 0.89), {@code e},
     * the right (since 0.91), or the empty text.
     */
    private static String edgeAt(WebView webView, double x, double y) {
        try {
            Object edge = webView.getEngine().executeScript(
                    "typeof window.edgeAt === 'function' ? window.edgeAt(" + x + ", " + y + ") : ''");
            return edge == null ? "" : edge.toString();
        } catch (RuntimeException e) {
            return "";
        }
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

    /** The key under which the toolkit keeps the tooltip it installs on a node, the page's own included. */
    private static final String TOOLTIP_KEY = "javafx.scene.control.Tooltip";

    /**
     * Keeps every tooltip of the page for as long as the setting says (since 0.91): the toolkit shows a page's titles with a
     * tooltip it installs on the view, each time with the text of the element under the pointer, and hides it after 5 seconds
     * unless told otherwise. 0 keeps it until the pointer leaves the element, when the page takes it away.
     */
    private void keepTooltipsAsSet(WebView webView) {
        webView.getProperties().addListener((javafx.collections.MapChangeListener<Object, Object>) change -> {
            if (change.wasAdded() && TOOLTIP_KEY.equals(change.getKey()) && change.getValueAdded() instanceof javafx.scene.control.Tooltip tip) {
                int millis = runtime == null ? 0 : runtime.settings().current().tooltipMillis();
                tip.setShowDuration(millis == 0 ? Duration.INDEFINITE : Duration.millis(millis));
            }
        });
    }

    /** Whether the version of Claude Code has been asked for in this run. */
    private static final java.util.concurrent.atomic.AtomicBoolean claudeVersionAsked = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Logs the version of Claude Code, once in a run, at the start (since 0.90), with the fake backend and the placeholder token
     * too, so that the log always says which version is installed. In the background, so that the window does not wait for it.
     */
    private static void logClaudeVersionOnce() {
        if (!claudeVersionAsked.compareAndSet(false, true)) {
            return;
        }
        Thread asking = new Thread(() -> LOG.info(org.example.token.ClaudeVersion.line(
                org.example.token.ClaudeVersion.COMMAND, org.example.token.ClaudeVersion.LIMIT)), "claude-version");
        asking.setDaemon(true);
        asking.start();
    }

    /**
     * Notices when the person drags the height of an open history or log, so that it can be remembered, and the width of the
     * log (since 0.93) or the burndown (since 0.99) shown alone.
     */
    private void watchHeight(Stage window) {
        window.heightProperty().addListener((observable, before, after) -> {
            if (!applying && rememberedPanel != null && window == stage) {
                heightSettled.playFromStart();
            }
        });
        window.widthProperty().addListener((observable, before, after) -> {
            if (!applying && window == stage && widthAlone(appliedSize) != null) {
                widthSettled.playFromStart();
            }
        });
    }

    /** The panel shown alone whose width is remembered, the log or the burndown, or null for any other. */
    private static String widthAlone(Optional<WindowFit.Size> size) {
        if (size.isEmpty()) {
            return null;
        }
        String panel = size.get().panel();
        return "log".equals(panel) || "burndown".equals(panel) ? panel : null;
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
        widthSettled.stop();
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
            if (exitAsked(webView)) {
                return;
            }
            followPointer(webView);
            followMinimized(webView);
            followFocus();
            fit(webView, stage);
            followTitleBar();
            followStayOnTop();
        }));
        fitTimer.setCycleCount(Animation.INDEFINITE);
        fitTimer.play();
    }

    /**
     * Ends the program when the cross of the row was pressed (since 0.94): the window is closed, as by its own cross, so that
     * the last window closing ends the toolkit and {@link #stop()} stores the window and releases the rest, the same way.
     *
     * @return whether the window was closed
     */
    private boolean exitAsked(WebView webView) {
        try {
            if (!Boolean.TRUE.equals(webView.getEngine().executeScript("typeof window.exitAsked === 'function' && window.exitAsked()"))) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        LOG.info("The program is ended by its cross");
        fitTimer.stop();
        stage.close();
        // Should another window of the program still be there, the outline of where the window is among them, the toolkit is ended too.
        Platform.exit();
        return true;
    }

    /** How long after the outline or the folder picker is gone the focus it gives back is no activation, in milliseconds. */
    private static final long FOCUS_QUIET_MILLIS = 400;

    /**
     * Zooms an outline onto the window whenever it becomes the active window (since 0.89), while the setting says so (since
     * 0.90, off by default): from the taskbar or the Dock, restored from minimized, by Alt-Tab, and at the start. Not when the
     * window is clicked into (since 0.90), nor when the focus comes back from the folder picker of the settings, nor from the
     * outline's own window. Read on the tick, since the stage may be another one
     * from one tick to the next.
     */
    private void followFocus() {
        boolean focused = stage.isFocused();
        boolean rose = focused && !focusedBefore;
        focusedBefore = focused;
        // Only when the setting says so (on by default since 0.91), and not for a window that was clicked into (0.90).
        boolean wanted = runtime != null && runtime.settings().current().locateWindow();
        boolean clicked = Math.abs(System.currentTimeMillis() - pressedAt) < CLICK_MILLIS;
        // Nor for a click on the title bar, which the program is not told of (since 0.91): after the first rise of the run, the start,
        // only while the pointer is outside the window, as it is on the taskbar or the Dock.
        boolean first = !roseBefore;
        if (rose) {
            roseBefore = true;
        }
        if (rose && wanted && !clicked && (first || !pointerOnWindow()) && !locator.busy(FOCUS_QUIET_MILLIS) && !Desk.pickerBusy(FOCUS_QUIET_MILLIS)) {
            locator.zoomOnto(stage);
        }
    }

    /** Whether the window has come to the front before in this run. */
    private boolean roseBefore;

    /** Whether the pointer is on the window, its title bar and frame included; false when it cannot be read. */
    private boolean pointerOnWindow() {
        try {
            if (robot == null) {
                robot = new Robot();
            }
            Point2D at = robot.getMousePosition();
            return PointerAway.inside(at.getX(), at.getY(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Tells the page when the window is minimized and when it is shown again (since 0.88), so that the status circle stands still
     * while it cannot be seen. Read on the tick, not listened for, since the stage may be another one from one tick to the next;
     * a page that is not ready yet is told on a later tick.
     */
    private void followMinimized(WebView webView) {
        boolean minimized = stage.isIconified();
        if (minimizedTold != null && minimizedTold == minimized) {
            return;
        }
        try {
            Object told = webView.getEngine().executeScript("typeof window.windowHidden === 'function' ? window.windowHidden(" + minimized + ") : false");
            if (Boolean.TRUE.equals(told)) {
                minimizedTold = minimized;
            }
        } catch (RuntimeException e) {
            // The page is not ready: the next tick tries again.
        }
    }

    /**
     * Tells the page when the pointer has been outside the window for a moment, and when it is back (since 0.85): the page then
     * lets the row go, when the settings say it may, and asks for a window less tall by it. The window is the whole of it, its
     * title bar and frame included, so that the row stays while the window is moved or dragged larger by them.
     */
    private void followPointer(WebView webView) {
        try {
            if (robot == null) {
                robot = new Robot();
            }
            Point2D at = robot.getMousePosition();
            boolean away = pointer.away(PointerAway.inside(at.getX(), at.getY(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()),
                    System.nanoTime() / 1_000_000);
            if (awayTold != null && awayTold == away) {
                return;
            }
            Object told = webView.getEngine().executeScript("typeof window.pointerAway === 'function' ? window.pointerAway(" + away + ") : false");
            if (Boolean.TRUE.equals(told)) {
                awayTold = away;
            }
        } catch (RuntimeException e) {
            // The page is not ready, or the pointer cannot be read: the row stays, and the next tick tries again.
        }
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
            if (width > MOST_FRAME || height > MOST_FRAME) {
                // No frame is that large: the stage and its scene do not agree yet, as at a start whose window is being restored,
                // when the stage can have its size before the scene has the page's. Measured so, the screen's room would be told
                // the page far too small (since 0.82). The next tick tries again.
                return;
            }
            decorationWidth = width;
            decorationHeight = height;
        }
        Optional<WindowFit.Size> before = appliedSize;
        // A width the person had just dragged the log to, and that was not remembered yet, is remembered first.
        if (widthSettled.getStatus() == Animation.Status.RUNNING) {
            widthSettled.stop();
            rememberDraggedWidth(stage);
        }
        // Where the window was, and on which screen, before it changes: what it grows from (since 0.93).
        WindowPlace.Rect was = new WindowPlace.Rect(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        Rectangle2D area = screenArea();
        appliedSize = Optional.of(size);
        // A panel that stays open keeps the width the person gave the window: a new line, or any other change of what the page
        // asks for, applies the page's own width only when the panel was opened or replaced.
        // The same holds for the tab of the settings that can be dragged wider: its height follows its content at the width it has.
        boolean keepWidth = size.widthIsThePersons() && before.isPresent() && size.panel() != null
                && size.panel().equals(before.get().panel()) && before.get().resize() == size.resize();
        int contentHeight = size.height();
        // With several views the height that is the person's is that of the lowest list: the views above it keep theirs.
        String list = size.lowestList();
        // With several views that share the screen the page says no height alone: nothing is remembered of a shared height.
        boolean remembered = WindowFit.Size.remembersHeight(list) && !(size.several() && size.alone() == 0);
        // What of the height is not the list's own: nothing with a list alone, whose height is the window's, row included.
        int above = RememberedHeights.above(size.height(), size.several() ? size.alone() : size.height());
        if (remembered) {
            // The history, the log and the burndown open at the height they were last left at, never less than the page's own.
            RememberedHeights.Opening opening =
                    RememberedHeights.open(size.height() - above, runtime.settings().storedHeight(list));
            contentHeight = above + opening.height();
            if (opening.store()) {
                runtime.settings().storeHeight(list, size.height() - above);
            }
        }
        // Never taller than the screen the window is on (since 0.82).
        contentHeight = Math.min(contentHeight, screenRoom());
        // While the pointer is outside the window the row is away, and the window less tall by it (since 0.85). A height the
        // person had just dragged to, and that was not remembered yet, is remembered first, as it was with the row there.
        if (before.isPresent() && before.get().away() != size.away() && heightSettled.getStatus() == Animation.Status.RUNNING) {
            rememberDraggedHeight(stage);
        }
        contentHeight = Math.max(WindowFit.MIN.height(), contentHeight - size.away());
        rememberedPanel = remembered ? list : null;
        rememberedAbove = above;
        rememberedAway = size.away();
        // A list that has views above it can be dragged down to one line of its own, and no further.
        leastHeight = size.several() && list != null ? above + closedHeight : closedHeight;
        heightSettled.stop();
        // Rounded up: a frame is not always a whole number of pixels wide, and a window a fraction too narrow shows a pixel
        // less of the page than it asked for, which is a pixel of the red border when there is one.
        double width = keepWidth ? stage.getWidth() : Math.ceil(size.width() + decorationWidth);
        // The log (since 0.93) or the burndown (since 0.99) alone opens at the width it was last left at, never narrower than the
        // page asks for.
        String widthPanel = widthAlone(Optional.of(size));
        int storedWidth = widthPanel == null ? 0 : runtime.settings().storedWidth(widthPanel);
        if (!keepWidth && storedWidth > 0) {
            width = Math.max(width, Math.ceil(storedWidth + decorationWidth));
        }
        // Never wider than the screen the window is on either (since 0.92; the program held it to 2,400 pixels until then).
        width = Math.min(width, area.getWidth());
        double height = Math.ceil(contentHeight + decorationHeight);
        if (size.resize() == WindowFit.Resize.NONE) {
            // The content as it is with nothing open: what the person may shrink an open one back to.
            closedWidth = size.width();
            closedHeight = contentHeight;
        }
        applying = true;
        try {
            applySize(stage, size, width, height);
            place(stage, size, was, new WindowPlace.Rect(area.getMinX(), area.getMinY(), area.getWidth(), area.getHeight()));
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
        // The height is the list's with the row there, also while the row is away.
        int pixels = (int) Math.round(stage.getHeight() - decorationHeight) - rememberedAbove + rememberedAway;
        if (RememberedHeights.shouldStore(pixels, runtime.settings().storedHeight(rememberedPanel))) {
            runtime.settings().storeHeight(rememberedPanel, pixels);
        }
    }

    /** The person has settled on a width for the log (since 0.93) or the burndown (since 0.99) shown alone: remember it. */
    private void rememberDraggedWidth(Stage stage) {
        String panel = widthAlone(appliedSize);
        if (panel == null || Double.isNaN(decorationWidth)) {
            return;
        }
        int pixels = (int) Math.round(stage.getWidth() - decorationWidth);
        if (pixels > 0) {
            runtime.settings().storeWidth(panel, pixels);
        }
    }

    /**
     * Puts the window where the room is after the program has changed its size (since 0.93): opening a view keeps the corner of
     * the window nearest the screen's corner where it was, and remembers where the row was; closing the views puts the row back
     * there, also when the person has moved the window meanwhile (since 0.94); a change while a view stays shown keeps the corner as opening
     * does. What would still lie off the screen is moved onto it. The row alone, the row away and back, and the place the start
     * restores are left where they are.
     */
    private void place(Stage stage, WindowFit.Size size, WindowPlace.Rect was, WindowPlace.Rect screen) {
        Optional<WindowFit.Size> previous = placedSize;
        placedSize = Optional.of(size);
        if (previous.isEmpty() || restoring != null || sameButAway(previous.get(), size)) {
            return;
        }
        boolean viewBefore = previous.get().resize() != WindowFit.Resize.NONE;
        boolean viewNow = size.resize() != WindowFit.Resize.NONE;
        if (!viewBefore && !viewNow) {
            return;
        }
        // The seen corner's left is the content's: on Windows a stage with a title bar is wider by borders that cannot be seen.
        double sceneX = stage.getScene().getX();
        WindowPlace.Rect to;
        if (!viewBefore) {
            rowPlace = new double[] {was.x() + sceneX, was.y()};
            to = WindowPlace.anchored(was, stage.getWidth(), stage.getHeight(), screen);
        } else if (!viewNow && rowPlace != null) {
            to = WindowPlace.onScreen(
                    new WindowPlace.Rect(rowPlace[0] - sceneX, rowPlace[1], stage.getWidth(), stage.getHeight()), screen);
        } else {
            to = WindowPlace.anchored(was, stage.getWidth(), stage.getHeight(), screen);
        }
        if (!viewNow) {
            rowPlace = null;
        }
        if (to.x() != stage.getX()) {
            stage.setX(to.x());
        }
        if (to.y() != stage.getY()) {
            stage.setY(to.y());
        }
    }

    /** Whether two sizes differ in how far the row is away at most, which moves the window nowhere. */
    private static boolean sameButAway(WindowFit.Size a, WindowFit.Size b) {
        return new WindowFit.Size(a.width(), a.height(), a.resize(), a.panel(), a.alone(), b.away()).equals(b);
    }

    private void applySize(Stage stage, WindowFit.Size size, double width, double height) {
        // The limits are opened wide first, so that they never forbid the size set next.
        stage.setMinWidth(0);
        stage.setMaxWidth(Double.MAX_VALUE);
        stage.setMinHeight(0);
        stage.setMaxHeight(Double.MAX_VALUE);
        stage.setWidth(width);
        stage.setHeight(height);
        // The largest the person may drag it to by its frame is the screen (since 0.92); the fixed sizes below are within that.
        Rectangle2D area = screenArea();
        stage.setMaxWidth(Math.max(width, area.getWidth()));
        stage.setMaxHeight(Math.max(height, area.getHeight()));
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
                // The row's width, or for the burndown the 200 pixels its chart needs (since 0.99).
                stage.setMinWidth(Math.min(Math.ceil(size.leastWidth(closedWidth) + decorationWidth), width));
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

    /** The visual bounds of the screen the window is on (without the taskbar or the Dock): the largest the window is ever made. */
    private Rectangle2D screenArea() {
        return Screen.getScreensForRectangle(stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
    }

    /** How tall the content of the window may be on the screen it is on: the screen's visual height less the frame. */
    private int screenRoom() {
        Rectangle2D screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        double frame = Double.isNaN(decorationHeight) ? 0 : decorationHeight;
        return (int) Math.max(WindowFit.MIN.height(), Math.floor(screen.getHeight() - frame));
    }

    /** The room last told to the page, so that it is told only when it changes. */
    private int roomTold = -1;

    private void fit(WebView webView, Stage stage) {
        try {
            int room = screenRoom();
            if (room != roomTold) {
                Object told = webView.getEngine().executeScript("typeof window.screenRoom === 'function' ? window.screenRoom(" + room + ") : false");
                if (Boolean.TRUE.equals(told)) {
                    roomTold = room;
                }
            }
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
            // As tall as the window is with the row there, also while the row is away (since 0.85).
            lastContentHeight = stage.getHeight() - decorationHeight + appliedSize.map(WindowFit.Size::away).orElse(0);
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
            LOG.fine("The window is restored: " + restoring.panel() + ", " + restoring.width() + " x " + restoring.height()
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
        // Several views are stored with commas between them, as the settings file names them: cost,history.
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
