package com.parsel;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AppIconTest {

    private static final int ACCENT = 0x0b8575;
    private static final int ON_ACCENT = 0xffffff;

    /** Reads the picture of the entry with this size out of the .ico. */
    private static BufferedImage entry(byte[] ico, int size) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        int count = in.getShort(4);
        for (int i = 0; i < count; i++) {
            int at = 6 + 16 * i;
            int width = in.get(at) & 0xFF;
            if ((width == 0 ? 256 : width) == size) {
                byte[] png = new byte[in.getInt(at + 8)];
                in.position(in.getInt(at + 12));
                in.get(png);
                return ImageIO.read(new ByteArrayInputStream(png));
            }
        }
        throw new AssertionError("no " + size + " px entry");
    }

    @Test
    void holdsEverySizeAsPng() throws IOException {
        byte[] ico = AppIcon.ico(ACCENT, ON_ACCENT);
        assertEquals(7, ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            BufferedImage image = entry(ico, size);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
        }
    }

    @Test
    void usesTheGivenColours() throws IOException {
        BufferedImage image = entry(AppIcon.ico(ACCENT, ON_ACCENT), 256);
        assertEquals(0xff000000 | ACCENT, image.getRGB(4, 128), "tile, left edge");
        assertEquals(0xff000000 | ON_ACCENT, image.getRGB(128, 176), "envelope, below the flap");
        assertEquals(0xff000000 | ACCENT, image.getRGB(128, 136), "flap");
        assertEquals(0, image.getRGB(0, 0), "the tile's corner is rounded, so transparent");
    }

    @Test
    void theSmallTaskbarSizeIsItsOwnDrawing() throws IOException {
        BufferedImage image = entry(AppIcon.ico(ACCENT, ON_ACCENT), 24);
        assertEquals(0xff000000 | ACCENT, image.getRGB(1, 12), "tile, left edge");
        assertEquals(0xff000000 | ON_ACCENT, image.getRGB(12, 16), "envelope, below the flap");
    }

    @Test
    void anotherAccentGivesAnotherPicture() throws IOException {
        assertNotEquals(
                entry(AppIcon.ico(ACCENT, ON_ACCENT), 64).getRGB(2, 32),
                entry(AppIcon.ico(0x6a4bc4, ON_ACCENT), 64).getRGB(2, 32));
    }
}
