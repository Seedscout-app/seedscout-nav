package app.seedscout.nav.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.seedscout.nav.client.qr.QrCode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scan-proof for {@code QrCode.java} that its own class doc says has never been run: encode
 * a realistic pairing URI, render the module grid to a PNG, decode that PNG with {@link
 * VisionQrDecoder} (a decoder with zero code in common with the encoder), and assert the
 * decoded text is exactly the input.
 *
 * <p>This proves the symbols this encoder emits are readable by a real, independently-written
 * QR decoder (macOS Vision's, which real phone camera apps use the same underlying algorithm
 * family for: finder/timing/alignment pattern detection, format-info decode, unmasking,
 * Reed-Solomon error correction, then bitstream decode). It does NOT prove every possible input
 * this encoder could ever produce decodes correctly, only the specific payload shapes covered
 * here, nor does it prove anything about a physical camera's behavior under bad lighting, glare,
 * a curved or small in-game display, etc.
 *
 * <p>Skips itself (does not fail the build) off macOS, or when the {@code swift} toolchain is
 * not on {@code PATH}, since the decoder oracle in {@code tools/decode_qr.swift} imports Apple's
 * Vision framework, which exists only on macOS: a {@code swift} binary alone is not proof the
 * decoder can run (GitHub's ubuntu-latest image ships a standalone Swift toolchain with no
 * Vision behind it), so both conditions are checked. See the {@code qr scan proof (macOS)} CI
 * job for where this class actually runs.
 */
class QrCodeScanRoundTripTest {

    private static VisionQrDecoder decoder;

    @BeforeAll
    static void checkToolchain() {
        assumeTrue(
                VisionQrDecoder.isAvailable(),
                "not on macOS (or swift toolchain not on PATH); Vision.framework is macOS-only, "
                        + "skipping the Vision scan proof");
        // Gradle's test task runs with the project root as the working directory.
        decoder = new VisionQrDecoder(Path.of(System.getProperty("user.dir")));
    }

    @Test
    @DisplayName("realistic full-length pairing URI round-trips through an independent decoder")
    void fullLengthPairingUriRoundTrips() throws Exception {
        String uri = "seedscout://pair?h=192.168.1.42&p=54321&t=" + fakeToken(43) + "&v=1";
        assertRoundTrips(uri, "full-length");
    }

    @Test
    @DisplayName("shortest plausible pairing URI round-trips through an independent decoder")
    void shortestPlausibleUriRoundTrips() throws Exception {
        String uri = "seedscout://pair?h=10.0.0.1&p=1&t=" + fakeToken(43) + "&v=1";
        assertRoundTrips(uri, "shortest-plausible");
    }

    @Test
    @DisplayName("input at exactly version 5's usable byte capacity round-trips")
    void longestVersion5CapacityRoundTrips() throws Exception {
        // Fixed non-host/port literal text ("seedscout://pair?h=" + "&p=" + "&t=" + "&v=1")
        // plus a 43-char token is 73 bytes; version 5 LOW has 106 usable bytes (108 data
        // codewords minus the 2-byte byte-mode header), leaving exactly 33 bytes for host+port.
        // A bracketed placeholder host fills that budget exactly so this input sits at the
        // true version-5 boundary rather than comfortably under it.
        String host = "[" + "f".repeat(31) + "]";
        String uri = "seedscout://pair?h=" + host + "&p=1&t=" + fakeToken(43) + "&v=1";
        assertEquals(106, uri.length(), "test fixture must sit exactly at the version 5 byte boundary");
        assertRoundTrips(uri, "version5-boundary");
    }

    @Test
    @DisplayName("input beyond version 5 capacity is rejected, never silently truncated or corrupted")
    void beyondVersion5CapacityIsRejected() {
        String host = "[" + "f".repeat(32) + "]"; // one byte over the version 5 boundary fixture
        String uri = "seedscout://pair?h=" + host + "&p=1&t=" + fakeToken(43) + "&v=1";
        assertEquals(107, uri.length(), "test fixture must sit exactly one byte past the version 5 boundary");
        assertThrows(IllegalArgumentException.class, () -> QrCode.encodeAscii(uri));
    }

    private void assertRoundTrips(String uri, String caseName) throws Exception {
        QrCode qr = QrCode.encodeAscii(uri);
        Path png = tempPngPath(caseName);
        QrPngWriter.write(qr, png);

        VisionQrDecoder.Result result = decoder.decode(png);
        assertEqualsWithContext(uri, result, caseName);
    }

    private void assertEqualsWithContext(String expected, VisionQrDecoder.Result result, String caseName) {
        if (!result.found()) {
            throw new AssertionError(
                    "Vision found no scannable barcode for case '" + caseName + "' (exit code "
                            + result.exitCode() + "). The QR encoder emitted a symbol that a real, "
                            + "independent decoder cannot read: this is the exact failure mode "
                            + "QrCode.java's header warns about.");
        }
        List<String> payloads = result.payloadLines();
        assertEquals(1, payloads.size(), "expected exactly one decoded barcode for case '" + caseName + "'");
        assertEquals(expected, payloads.get(0), "decoded payload must equal the encoder's input exactly");
    }

    @TempDir
    static Path tempDir;

    private static Path tempPngPath(String caseName) {
        return tempDir.resolve(caseName + ".png");
    }

    private static String fakeToken(int length) {
        // Shape-only stand-in for PairingToken's real base64url output (32 bytes -> 43 chars,
        // see PairingTokenTest); this test only cares about byte length and pure-ASCII-ness.
        StringBuilder sb = new StringBuilder(length);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(i % alphabet.length()));
        }
        return sb.toString();
    }
}
