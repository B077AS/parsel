package io.github.b077as.parsel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The app icon drawn in the theme's colours, so the title bar and taskbar icon can follow the accent while the app runs.
 * The shapes are the ones in {@code docs/icon}: the 16-unit master for every size that is a multiple of 16, and the
 * separate 24 px drawing for the usual taskbar size, so each size is drawn on whole pixels and never shrunk from a bigger one.
 * The {@code icon.ico} resource stays the fixed brand blue (it is what the window starts with and what the exe would show).
 */
public final class AppIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** One drawing: the grid it is drawn on, then the tile radius, the flap, the paper and the sheet peeking out above it. */
    private record Grid(int units, double tileRadius, double[] top, double[] paper, double[] flap) {
    }

    // x, y, width, height, corner radius
    private static final Grid MASTER = new Grid(16, 4,
            new double[]{5, 3, 6, 3, 1}, new double[]{3, 5, 10, 7, 1.5},
            new double[]{3, 5, 8, 7.5, 13, 5, 13, 7, 8, 9.5, 3, 7});
    private static final Grid SMALL = new Grid(24, 6,
            new double[]{7, 5, 10, 6, 1.5}, new double[]{4, 8, 16, 10, 2},
            new double[]{4, 8, 12, 12, 20, 8, 20, 11, 12, 15, 4, 11});

    private AppIcon() {
    }

    /**
     * @param accent   tile and flap colour as 0xRRGGBB
     * @param onAccent envelope colour as 0xRRGGBB (the page's text colour on the accent)
     * @return an .ico holding the icon at 16, 24, 32, 48, 64, 128 and 256 px, each as PNG
     */
    public static byte[] ico(int accent, int onAccent) {
        byte[][] pngs = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            pngs[i] = png(draw(SIZES[i], accent, onAccent));
        }
        int offset = 6 + 16 * SIZES.length;
        int total = offset;
        for (byte[] png : pngs) {
            total += png.length;
        }
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            out.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size));
            out.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            out.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        for (byte[] png : pngs) {
            out.put(png);
        }
        return out.array();
    }

    static BufferedImage draw(int px, int accent, int onAccent) {
        Grid grid = px % 16 == 0 ? MASTER : SMALL;
        Color tile = new Color(accent);
        Color paper = new Color(onAccent);
        Color top = new Color(mix(onAccent, accent, 0.7)); // the page paints the same blend: 70% on-accent, 30% accent
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double scale = (double) px / grid.units();
            g.scale(scale, scale);
            g.setColor(tile);
            g.fill(new RoundRectangle2D.Double(0, 0, grid.units(), grid.units(), grid.tileRadius() * 2, grid.tileRadius() * 2));
            g.setColor(top);
            g.fill(rounded(grid.top()));
            g.setColor(paper);
            g.fill(rounded(grid.paper()));
            g.setColor(tile);
            Path2D flap = new Path2D.Double();
            double[] p = grid.flap();
            flap.moveTo(p[0], p[1]);
            for (int i = 2; i < p.length; i += 2) {
                flap.lineTo(p[i], p[i + 1]);
            }
            flap.closePath();
            g.fill(flap);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static RoundRectangle2D rounded(double[] r) {
        return new RoundRectangle2D.Double(r[0], r[1], r[2], r[3], r[4] * 2, r[4] * 2);
    }

    /** {@code share} of colour a plus the rest of colour b, per channel. */
    private static int mix(int a, int b, double share) {
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int channel = (int) Math.round(((a >> shift) & 0xFF) * share + ((b >> shift) & 0xFF) * (1 - share));
            rgb |= channel << shift;
        }
        return rgb;
    }

    private static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
