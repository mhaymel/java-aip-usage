package org.example;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.log.Log;

/**
 * Shows where the window is when it comes to the front (since 0.89): an outline that starts round the middle of the screen, 60 %
 * of its width and height, and in 0.6 seconds shrinks and moves until it lies on the window's edges, then fades in 0.15 seconds
 * and is gone. It is drawn by a window of its own, transparent and above all others, owned by the program's window so that it
 * has no place in the taskbar; the program's window is not moved or changed by it.
 */
final class Locator {

    private static final Log LOG = Log.of(Locator.class);

    /** What the window of the outline is marked with, so that it is never taken for the program's window. */
    static final String MARK = "locator";

    /** How long the outline takes to reach the window, and to fade there. */
    static final Duration ZOOM = Duration.millis(600);

    static final Duration FADE = Duration.millis(150);

    /** The share of the screen's width and height the outline starts at. */
    static final double START_SHARE = 0.6;

    /** The blue of the notes, on every theme: the outline is drawn over other programs, whose theme is not known. */
    private static final Color BLUE = Color.web("#0969da");

    private Stage shown;

    private SequentialTransition running;

    /** When the last outline was gone, in milliseconds of {@link System#currentTimeMillis()}; 0 before the first. */
    private long goneAt;

    /** Whether a transparent window could not be made: then nothing is drawn, and that is logged once. */
    private boolean failed;

    /** Whether an outline is drawn now, or was gone less than {@code quietMillis} ago: the focus it moved is no activation. */
    boolean busy(long quietMillis) {
        return shown != null || System.currentTimeMillis() - goneAt < quietMillis;
    }

    /** Whether this window is the outline's, which no one is to take for the program's window. */
    static boolean isLocator(Window window) {
        return MARK.equals(window.getUserData());
    }

    /** Zooms onto the window; one that is drawn already starts again from the beginning. */
    void zoomOnto(Stage window) {
        if (failed || window == null || !window.isShowing()) {
            return;
        }
        stop();
        try {
            Rectangle2D screen = Screen.getScreensForRectangle(window.getX(), window.getY(), Math.max(1, window.getWidth()), Math.max(1, window.getHeight()))
                    .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
            Stage stage = new Stage(StageStyle.TRANSPARENT);
            stage.setUserData(MARK);
            stage.initOwner(window);
            stage.setAlwaysOnTop(true);
            stage.setX(screen.getMinX());
            stage.setY(screen.getMinY());
            stage.setWidth(screen.getWidth());
            stage.setHeight(screen.getHeight());

            double startWidth = screen.getWidth() * START_SHARE;
            double startHeight = screen.getHeight() * START_SHARE;
            Rectangle frame = new Rectangle((screen.getWidth() - startWidth) / 2, (screen.getHeight() - startHeight) / 2, startWidth, startHeight);
            frame.setFill(Color.TRANSPARENT);
            frame.setStroke(BLUE);
            frame.setStrokeWidth(3);
            frame.setArcWidth(12);
            frame.setArcHeight(12);
            frame.setMouseTransparent(true);
            Pane pane = new Pane(frame);
            pane.setMouseTransparent(true);
            pane.setStyle("-fx-background-color: transparent;");
            Scene scene = new Scene(pane, screen.getWidth(), screen.getHeight(), Color.TRANSPARENT);
            stage.setScene(scene);

            double endX = window.getX() - screen.getMinX();
            double endY = window.getY() - screen.getMinY();
            Timeline zoom = new Timeline(new KeyFrame(ZOOM,
                    new KeyValue(frame.xProperty(), endX, Interpolator.EASE_OUT),
                    new KeyValue(frame.yProperty(), endY, Interpolator.EASE_OUT),
                    new KeyValue(frame.widthProperty(), window.getWidth(), Interpolator.EASE_OUT),
                    new KeyValue(frame.heightProperty(), window.getHeight(), Interpolator.EASE_OUT)));
            FadeTransition fade = new FadeTransition(FADE, frame);
            fade.setFromValue(1);
            fade.setToValue(0);
            running = new SequentialTransition(zoom, fade);
            running.setOnFinished(event -> stop());
            shown = stage;
            stage.show();
            // The outline takes no focus of its own: the program's window keeps it.
            window.requestFocus();
            running.play();
        } catch (RuntimeException e) {
            failed = true;
            shown = null;
            LOG.debug("No outline can be drawn to show where the window is: " + Failure.of(e));
        }
    }

    /** Takes the outline away, if one is drawn. */
    void stop() {
        if (running != null) {
            running.setOnFinished(null);
            running.stop();
            running = null;
        }
        if (shown != null) {
            shown.hide();
            shown = null;
            goneAt = System.currentTimeMillis();
        }
    }
}
