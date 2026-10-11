package org.example;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The files of the program's icon: that each is there, is of its size, and is the gauge on its dark square. */
class AppIconsTest {

    private static final int SQUARE = 0x0d1117;

    private static final int ARC = 0x1a7f37;

    private static final int NEEDLE = 0x3fb950;

    private static BufferedImage read(int size) throws IOException {
        try (InputStream in = AppIconsTest.class.getResourceAsStream(AppIcons.resource(size))) {
            assertNotNull(in, "the icon of " + size + " pixels is a file of the program");
            return ImageIO.read(in);
        }
    }

    @Test
    void theIconIsKeptInFourSizes() {
        assertEquals(List.of(16, 32, 48, 256), AppIcons.SIZES);
    }

    @Test
    void eachFileIsASquareOfItsSize() throws IOException {
        for (int size : AppIcons.SIZES) {
            BufferedImage icon = read(size);

            assertEquals(size, icon.getWidth(), "width of " + size);
            assertEquals(size, icon.getHeight(), "height of " + size);
            assertTrue(icon.getColorModel().hasAlpha(), size + ": the corners are see-through");
        }
    }

    @Test
    void eachSizeIsTheGaugeOnItsDarkRoundedSquare() throws IOException {
        for (int size : AppIcons.SIZES) {
            BufferedImage icon = read(size);

            // At 16 pixels the rounding leaves a sliver of the corner pixel; it is all but see-through.
            assertTrue(icon.getRGB(0, 0) >>> 24 < 16, size + ": next to nothing at the very corner, which is rounded off");
            assertTrue(icon.getRGB(size - 1, size - 1) >>> 24 < 16, size + ": nor at the opposite one");
            // The middle of the lower edge is the dark ground, fully there.
            int ground = icon.getRGB(size / 2, size - 1 - size / 16);
            assertEquals(0xff, ground >>> 24, size + ": the ground is solid");
            assertEquals(SQUARE, ground & 0xffffff, size + ": and dark");
            // The hub of the needle, at the centre of the gauge.
            assertNear(NEEDLE, icon.getRGB(size / 2, Math.round(size * 0.66f)), size + ": the hub of the needle");
            // The left end of the arc, level with the hub.
            assertNear(ARC, icon.getRGB(Math.round(size * 0.18f), Math.round(size * 0.64f)), size + ": the arc of the gauge");
        }
    }

    /**
     * The colour of a pixel is that of a shape, give or take what the smoothing of its edge mixes in: at 16 pixels a
     * pixel is rarely all inside one shape.
     */
    private static void assertNear(int expected, int pixel, String what) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int difference = Math.abs(((expected >> shift) & 0xff) - ((pixel >> shift) & 0xff));
            assertTrue(difference <= 24, what + ": expected about " + Integer.toHexString(expected) + " but was " + Integer.toHexString(pixel & 0xffffff));
        }
    }

    @Test
    void theNeedlePointsToTheUpperRight() throws IOException {
        BufferedImage icon = read(256);

        // Two thirds of the way round from the left: 60 degrees above the right. A point half way along the needle.
        int x = (int) Math.round(256 * (0.5 + 0.16 * Math.cos(Math.PI / 3)));
        int y = (int) Math.round(256 * (0.66 - 0.16 * Math.sin(Math.PI / 3)));
        assertEquals(NEEDLE, icon.getRGB(x, y) & 0xffffff);
        // The same place mirrored to the left is the ground: there is one needle.
        assertEquals(SQUARE, icon.getRGB(256 - x, y) & 0xffffff);
    }
}
