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
     * @param old the window that is showing
     * @param titleBar whether the new one has a title bar
     * @return the new window, showing the same scene at the same place on the screen: the content does
     *     not move, a title bar appears above it or goes from there
     */
    static Stage replace(Stage old, boolean titleBar) {
        Scene scene = old.getScene();
        // Where the content is on the screen, which is what must stay put; the stage's own corner is the frame's.
        double contentX = old.getX() + scene.getX();
        double contentY = old.getY() + scene.getY();
        double contentWidth = scene.getWidth();
        double contentHeight = scene.getHeight();

        Stage fresh = new Stage(titleBar ? StageStyle.DECORATED : StageStyle.UNDECORATED);
        fresh.setTitle(old.getTitle());
        fresh.getIcons().setAll(old.getIcons());
        fresh.setAlwaysOnTop(old.isAlwaysOnTop());
        fresh.setResizable(old.isResizable());
        // Unseen until it is where it belongs, so that it is not seen to jump.
        fresh.setOpacity(0);
        fresh.setX(contentX);
        fresh.setY(contentY);
        // Setting the scene on the new stage takes it from the old one.
        fresh.setScene(scene);
        fresh.show();
        // Only a window that is shown knows how thick its frame is.
        fresh.setX(contentX - scene.getX());
        fresh.setY(contentY - scene.getY());
        fresh.setWidth(contentWidth + (fresh.getWidth() - scene.getWidth()));
        fresh.setHeight(contentHeight + (fresh.getHeight() - scene.getHeight()));
        fresh.setOpacity(1);
        // The new window is showing, so this is not the last window closing and the program goes on.
        old.close();
        return fresh;
    }
}
