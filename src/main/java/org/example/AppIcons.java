package org.example;

import javafx.scene.image.Image;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The icon of the program, a gauge, in the sizes it is kept in. A window is given all of them and the system takes the
 * one that fits where it shows it: the title bar, the taskbar, the list of windows.
 *
 * <p>The files are in the resources, drawn by {@code src/main/icons/draw-icon.cjs}.
 */
final class AppIcons {

    /** The sizes the icon is kept in, in pixels, each drawn for itself. */
    static final List<Integer> SIZES = List.of(16, 32, 48, 256);

    private AppIcons() {
    }

    /** Where the icon of a size is among the resources. */
    static String resource(int size) {
        return "/icons/icon-" + size + ".png";
    }

    /**
     * The icon in every size, smallest first.
     *
     * @throws IllegalStateException if a file is not there, which is a fault of the build and not of the run
     */
    static List<Image> load() {
        List<Image> icons = new ArrayList<>();
        for (int size : SIZES) {
            try (InputStream in = AppIcons.class.getResourceAsStream(resource(size))) {
                if (in == null) {
                    throw new IllegalStateException("The program's icon is missing: " + resource(size));
                }
                icons.add(new Image(in));
            } catch (IOException e) {
                throw new IllegalStateException("The program's icon could not be read: " + resource(size), e);
            }
        }
        return icons;
    }
}
