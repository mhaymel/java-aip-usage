package org.example;

import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Gives the window a title bar, or takes it away, while the program runs. JavaFX fixes the style of
 * a stage before it is first shown, so the window that is there cannot be changed: a new one is made
 * in the other style, the scene with the page in it is moved over as it is, and the old window is
 * closed. The page is not loaded again; it does not notice.
 */
final class StageSwap {

    private StageSwap() {
    }

    static boolean hasTitleBar(Stage stage) {
        return stage.getStyle() == StageStyle.DECORATED;
    }

    /**
     * The window's top-left corner as it is seen (since 0.98): the left edge of its content, since the sides of a frame are
     * no more than a pixel to be seen (on Windows the stage is wider by borders that cannot be seen, to be dragged by), and
     * the top of the window, which is its title bar's when it has one.
     */
    static double[] corner(Stage stage) {
        return new double[] {stage.getX() + stage.getScene().getX(), stage.getY()};
    }

    /** Puts the window's seen top-left corner at this place, see {@link #corner}. */
    static void placeCorner(Stage stage, double x, double y) {
        stage.setX(x - stage.getScene().getX());
        stage.setY(y);
    }

    /**
     * @param old the window that is showing
     * @param titleBar whether the new one has a title bar
     * @return the new window, showing the same scene with its top-left corner where the old one's was (since 0.98): a title
     *     bar that comes takes the place of the top of the content, which moves down by it, and one that goes leaves its place
     *     to the content, which moves up. Until 0.97 the content stayed where it was and the window's corner moved.
     */
    static Stage replace(Stage old, boolean titleBar) {
        Scene scene = old.getScene();
        double[] corner = corner(old);
        double contentWidth = scene.getWidth();
        double contentHeight = scene.getHeight();

        Stage fresh = new Stage(titleBar ? StageStyle.DECORATED : StageStyle.UNDECORATED);
        fresh.setTitle(old.getTitle());
        fresh.getIcons().setAll(old.getIcons());
        fresh.setAlwaysOnTop(old.isAlwaysOnTop());
        fresh.setResizable(old.isResizable());
        // Unseen until it is where it belongs, so that it is not seen to jump.
        fresh.setOpacity(0);
        fresh.setX(corner[0]);
        fresh.setY(corner[1]);
        // Setting the scene on the new stage takes it from the old one.
        fresh.setScene(scene);
        fresh.show();
        // Only a window that is shown knows how thick its frame is.
        placeCorner(fresh, corner[0], corner[1]);
        fresh.setWidth(contentWidth + (fresh.getWidth() - scene.getWidth()));
        fresh.setHeight(contentHeight + (fresh.getHeight() - scene.getHeight()));
        fresh.setOpacity(1);
        // The new window is showing, so this is not the last window closing and the program goes on.
        old.close();
        return fresh;
    }
}
