package app.seedscout.nav.qr;

import app.seedscout.nav.client.qr.QrCode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Renders a {@link QrCode}'s module grid to a PNG file, purely for
 * {@link QrCodeScanRoundTripTest}'s use as a scan-proof test fixture. This is test-only code:
 * the mod itself never needs a PNG, it draws the module grid straight into the game's GUI.
 *
 * <p>Uses a 10px-per-module scale and a 4-module quiet zone (the minimum ISO/IEC 18004
 * recommends) on all four sides, since an undersized quiet zone is a real-world cause of scan
 * failures that has nothing to do with the symbol data itself, and this test wants to isolate
 * the encoder's correctness from that separate concern.
 */
final class QrPngWriter {

    private static final int MODULE_PX = 10;
    private static final int QUIET_ZONE_MODULES = 4;
    private static final int WHITE = 0xFFFFFF;
    private static final int BLACK = 0x000000;

    private QrPngWriter() {}

    static void write(QrCode qr, Path outFile) throws IOException {
        int quietPx = QUIET_ZONE_MODULES * MODULE_PX;
        int imgSize = qr.size * MODULE_PX + quietPx * 2;
        BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < imgSize; y++) {
            for (int x = 0; x < imgSize; x++) {
                img.setRGB(x, y, WHITE);
            }
        }
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (!qr.getModule(x, y)) {
                    continue;
                }
                int px0 = quietPx + x * MODULE_PX;
                int py0 = quietPx + y * MODULE_PX;
                for (int dy = 0; dy < MODULE_PX; dy++) {
                    for (int dx = 0; dx < MODULE_PX; dx++) {
                        img.setRGB(px0 + dx, py0 + dy, BLACK);
                    }
                }
            }
        }
        ImageIO.write(img, "png", outFile.toFile());
    }
}
