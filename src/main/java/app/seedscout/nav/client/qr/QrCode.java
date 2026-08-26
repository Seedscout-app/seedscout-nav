package app.seedscout.nav.client.qr;

import java.util.Arrays;

/**
 * A minimal, from-scratch QR Code (ISO/IEC 18004) symbol generator, written for exactly one
 * job: turning the short {@code seedscout://pair} URI (see {@code shared/nav_protocol.md}
 * section 3) into a scannable module grid with no external dependency.
 *
 * <p><b>This is NOT a vendored copy of Nayuki's QR-Code-generator library.</b> The task brief
 * asked for that library vendored verbatim with its MIT header preserved; this build
 * environment has no network access to fetch that file, and hand-transcribing an ~800 line
 * third-party file from memory risked either silently corrupting its Reed-Solomon tables (a
 * QR code that LOOKS right but will not scan) or, worse, attaching a real person's MIT
 * copyright notice to code that was not actually their text. Neither is acceptable, so this
 * file is original code written directly from the public ISO/IEC 18004 algorithm and is
 * licensed under this project's own Apache-2.0 license, the same as the rest of the mod. See
 * the integrator report for what to do instead of a THIRD_PARTY_NOTICES entry.
 *
 * <p>Deliberately narrow scope, to keep the parts of the QR algorithm that are genuinely
 * error-prone to hand-write (the per-version data/error-correction-codeword split, defined by
 * ISO/IEC 18004 Table 9, not by a formula) small enough to get right with confidence:
 *
 * <ul>
 *   <li>Byte mode only. The pairing URI is plain ASCII, so no other encoding mode is needed.</li>
 *   <li>Error correction level LOW only. Fewer error-correction codewords means a smaller
 *       symbol (fewer modules) for the same payload, which is what actually helps a phone
 *       camera resolve the code at the small physical size an in-game screen renders at; the
 *       token is a one-time, freshly generated secret anyway; there is nothing gained by
 *       spending modules on redundancy here.</li>
 *   <li>Versions 1 through 5 only (up to 108 data codewords, roughly 106 usable bytes after
 *       the mode and length header), which stays a single Reed-Solomon block the whole way,
 *       avoiding the multi-block interleaving table entirely. A realistic pairing URI is
 *       {@code seedscout://pair?h=<IPv4>&p=<port>&t=<43-char token>&v=1}, at most about 95
 *       bytes given {@code InterfaceSelector} only ever binds a genuine site-local IPv4
 *       address, comfortably inside version 5's capacity.</li>
 *   <li>Mask pattern 0 always ((row + column) % 2 == 0), rather than the 8-mask penalty-score
 *       search real encoders run. Mask choice only affects how *comfortably* a scanner reads
 *       a symbol, never whether the symbol is spec-valid: the format bits declare mask 0, and
 *       any compliant reader unmasks using that declared value. This trades a small amount of
 *       scan robustness on a few resulting images for a much smaller, much more trustworthy
 *       implementation.</li>
 * </ul>
 *
 * <p>The data/ECC-codewords-per-version numbers below (versions 1 to 5, level L) were checked
 * against the well known "byte mode character capacity" figures for those same
 * version/level combinations (17, 32, 53, 78, 106 characters) by subtracting the fixed 12 bit
 * byte-mode header: 19, 34, 55, 80 and 108 data codewords each leave exactly that many usable
 * bytes once the header is subtracted, which is a real cross-check, not a bare assertion.
 * The alignment-pattern placement algorithm below was checked the same way, against the well
 * known fact that a version 2 symbol has exactly one alignment pattern, at (18, 18).
 *
 * <p><b>This implementation has not been scanned by a real camera or decoder in this
 * environment.</b> See the integrator report for why, and treat a physical scan test as a
 * required step before this ships.
 */
public final class QrCode {

    /** Version 1..5 data codewords at error correction level LOW, single Reed-Solomon block. */
    private static final int[] DATA_CODEWORDS = {0, 19, 34, 55, 80, 108};

    /** Version 1..5 error-correction codewords at level LOW, single Reed-Solomon block. */
    private static final int[] ECC_CODEWORDS = {0, 7, 10, 15, 20, 26};

    private static final int MAX_SUPPORTED_VERSION = 5;

    private static final int MASK_FORMAT_ECC_LOW_BITS = 0b01; // ISO 18004 Table 25: L = 01

    private final int version;
    public final int size;
    private final boolean[][] dark;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.dark = new boolean[size][size];
        this.isFunction = new boolean[size][size];
    }

    /**
     * Encodes {@code text} (which must be pure ASCII: the pairing URI always is) as a QR
     * symbol at the smallest supported version that fits. Throws if the text is too long for
     * {@link #MAX_SUPPORTED_VERSION}: see the class documentation for why that is expected
     * to never happen for a real pairing URI.
     */
    public static QrCode encodeAscii(String text) {
        byte[] data = toAsciiBytes(text);
        int version = -1;
        for (int v = 1; v <= MAX_SUPPORTED_VERSION; v++) {
            if (usableBytes(v) >= data.length) {
                version = v;
                break;
            }
        }
        if (version == -1) {
            throw new IllegalArgumentException(
                    "pairing URI is " + data.length + " bytes, too long for the QR versions "
                            + "this generator supports (max " + usableBytes(MAX_SUPPORTED_VERSION)
                            + " bytes); see QrCode's class documentation");
        }
        QrCode qr = new QrCode(version);
        byte[] codewords = qr.buildCodewords(data);
        qr.drawFunctionPatterns();
        qr.drawData(codewords);
        qr.applyMaskZeroAndFormatInfo();
        return qr;
    }

    /** True when the module at (x, y) is dark (should be drawn as an opaque/ink pixel). */
    public boolean getModule(int x, int y) {
        return dark[y][x];
    }

    private static int usableBytes(int version) {
        // 4 bits mode + 8 bits byte-mode character count (both versions 1-5 use an 8 bit
        // count field per ISO 18004 Table 3), rounded down to whole bytes of headroom.
        return DATA_CODEWORDS[version] - 2;
    }

    private static byte[] toAsciiBytes(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F) {
                throw new IllegalArgumentException("pairing URI must be pure ASCII");
            }
            out[i] = (byte) c;
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Bitstream and error correction
    // -----------------------------------------------------------------

    private byte[] buildCodewords(byte[] data) {
        int dataCap = DATA_CODEWORDS[version];
        BitWriter bits = new BitWriter();
        bits.write(0b0100, 4); // byte mode
        bits.write(data.length, 8); // versions 1-5: 8 bit character count for byte mode
        for (byte b : data) {
            bits.write(b & 0xFF, 8);
        }
        int terminatorBits = Math.min(4, dataCap * 8 - bits.bitLength());
        if (terminatorBits > 0) {
            bits.write(0, terminatorBits);
        }
        bits.padToByteBoundary();
        byte[] padBytes = {(byte) 0xEC, (byte) 0x11};
        int padIndex = 0;
        while (bits.byteLength() < dataCap) {
            bits.write(padBytes[padIndex % 2] & 0xFF, 8);
            padIndex++;
        }
        byte[] dataCodewords = bits.toByteArray();

        byte[] ecc = ReedSolomon.computeRemainder(dataCodewords, ECC_CODEWORDS[version]);
        byte[] all = new byte[dataCodewords.length + ecc.length];
        System.arraycopy(dataCodewords, 0, all, 0, dataCodewords.length);
        System.arraycopy(ecc, 0, all, dataCodewords.length, ecc.length);
        return all;
    }

    // -----------------------------------------------------------------
    // Function patterns (finder, separator, timing, alignment)
    // -----------------------------------------------------------------

    private void drawFunctionPatterns() {
        drawTimingPatterns();
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);
        drawAlignmentPatterns();
        reserveFormatInfoArea();
        // Versions 1-5 never need the version-information blocks (those start at version 7).
        // The fixed dark module, ISO 18004 6.9, at (column 8, row 4*version+9): already inside
        // the format-info reserved strip marked above, but not yet painted dark.
        set(8, size - 8, true);
        setFunction(8, size - 8, true);
    }

    private void drawTimingPatterns() {
        for (int i = 8; i < size - 8; i++) {
            boolean darkModule = i % 2 == 0;
            set(6, i, darkModule);
            setFunction(6, i, true);
            set(i, 6, darkModule);
            setFunction(i, 6, true);
        }
    }

    private void drawFinderPattern(int centerX, int centerY) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = centerX + dx;
                int y = centerY + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) {
                    continue;
                }
                int r = Math.max(Math.abs(dx), Math.abs(dy));
                boolean isDark = r != 2 && r <= 3;
                set(x, y, isDark);
                setFunction(x, y, true);
            }
        }
    }

    private void drawAlignmentPatterns() {
        int[] positions = alignmentPatternPositions(version);
        for (int r : positions) {
            for (int c : positions) {
                if (overlapsFinder(r, c)) {
                    continue;
                }
                drawAlignmentPattern(c, r);
            }
        }
    }

    private boolean overlapsFinder(int r, int c) {
        boolean topLeft = r <= 8 && c <= 8;
        boolean topRight = r <= 8 && c >= size - 9;
        boolean bottomLeft = r >= size - 9 && c <= 8;
        return topLeft || topRight || bottomLeft;
    }

    private void drawAlignmentPattern(int centerX, int centerY) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = centerX + dx;
                int y = centerY + dy;
                int r = Math.max(Math.abs(dx), Math.abs(dy));
                boolean isDark = r != 1;
                set(x, y, isDark);
                setFunction(x, y, true);
            }
        }
    }

    /** ISO 18004 Annex E algorithm; version 1 has none. Checked against the well known fact
     * that version 2 has exactly one alignment pattern, at (18, 18) - see class docs. */
    private static int[] alignmentPatternPositions(int version) {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int size = version * 4 + 17;
        int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        int pos = size - 7;
        for (int i = numAlign - 1; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private void reserveFormatInfoArea() {
        for (int i = 0; i < 9; i++) {
            setFunction(i, 8, true);
            setFunction(8, i, true);
        }
        for (int i = 0; i < 8; i++) {
            setFunction(size - 1 - i, 8, true);
            setFunction(8, size - 1 - i, true);
        }
    }

    // -----------------------------------------------------------------
    // Data placement (zigzag, skipping function modules) and masking
    // -----------------------------------------------------------------

    private void drawData(byte[] codewords) {
        int bitIndex = 0;
        int totalBits = codewords.length * 8;
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5; // column 6 is the vertical timing line, skip it
            }
            for (int rowStep = 0; rowStep < size; rowStep++) {
                int row = upward ? size - 1 - rowStep : rowStep;
                for (int colOffset = 0; colOffset < 2; colOffset++) {
                    int col = right - colOffset;
                    if (isFunction[row][col]) {
                        continue;
                    }
                    boolean bit = bitIndex < totalBits
                            && ((codewords[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1) != 0;
                    dark[row][col] = bit;
                    bitIndex++;
                }
            }
            upward = !upward;
        }
    }

    private void applyMaskZeroAndFormatInfo() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) {
                    dark[y][x] = !dark[y][x];
                }
            }
        }
        drawFormatInfo();
    }

    private void drawFormatInfo() {
        int data = (MASK_FORMAT_ECC_LOW_BITS << 3); // ECC level bits + mask pattern 000
        int rem = data << 10;
        int generator = 0b10100110111; // ISO 18004 Annex C, the (15,5) BCH generator, 0x537
        for (int i = 4; i >= 0; i--) {
            if (((rem >> (i + 10)) & 1) != 0) {
                rem ^= generator << i;
            }
        }
        int format = ((data << 10) | rem) ^ 0b101010000010010; // fixed XOR mask, Annex C
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(format, i));
        }
        set(8, 7, bit(format, 6));
        set(8, 8, bit(format, 7));
        set(7, 8, bit(format, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(format, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(format, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(format, i));
        }
    }

    private static boolean bit(int value, int index) {
        return ((value >> index) & 1) != 0;
    }

    private void set(int x, int y, boolean value) {
        dark[y][x] = value;
    }

    private void setFunction(int x, int y, boolean value) {
        isFunction[y][x] = value;
    }

    // -----------------------------------------------------------------
    // GF(256) Reed-Solomon, computed at runtime rather than from a memorized table: the
    // primitive polynomial (0x11D) and generator element (2) are the two ISO 18004 constants
    // this needs, and everything else (log/exp tables, the generator polynomial for a given
    // number of ECC codewords, and polynomial long division for the remainder) follows from
    // them mechanically.
    // -----------------------------------------------------------------

    private static final class ReedSolomon {
        private static final int[] EXP = new int[256];
        private static final int[] LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if (x >= 256) {
                    x ^= 0x11D;
                }
            }
            EXP[255] = EXP[0];
        }

        static byte[] computeRemainder(byte[] data, int eccLen) {
            int[] generator = computeGenerator(eccLen);
            int[] remainder = new int[eccLen];
            for (byte b : data) {
                int factor = (b & 0xFF) ^ remainder[0];
                System.arraycopy(remainder, 1, remainder, 0, eccLen - 1);
                remainder[eccLen - 1] = 0;
                for (int i = 0; i < eccLen; i++) {
                    remainder[i] ^= multiply(generator[i], factor);
                }
            }
            byte[] out = new byte[eccLen];
            for (int i = 0; i < eccLen; i++) {
                out[i] = (byte) remainder[i];
            }
            return out;
        }

        private static int[] computeGenerator(int degree) {
            int[] result = new int[degree];
            result[degree - 1] = 1;
            int root = 1;
            for (int i = 0; i < degree; i++) {
                for (int j = 0; j < degree; j++) {
                    result[j] = multiply(result[j], root);
                    if (j + 1 < degree) {
                        result[j] ^= result[j + 1];
                    }
                }
                root = multiply(root, 2);
            }
            return result;
        }

        private static int multiply(int a, int b) {
            if (a == 0 || b == 0) {
                return 0;
            }
            return EXP[(LOG[a] + LOG[b]) % 255];
        }
    }

    private static final class BitWriter {
        private byte[] buffer = new byte[32];
        private int bitLength = 0;

        void write(int value, int numBits) {
            ensureCapacity(bitLength + numBits);
            for (int i = numBits - 1; i >= 0; i--) {
                int bit = (value >> i) & 1;
                if (bit != 0) {
                    buffer[bitLength / 8] |= (byte) (1 << (7 - (bitLength % 8)));
                }
                bitLength++;
            }
        }

        void padToByteBoundary() {
            int remainder = bitLength % 8;
            if (remainder != 0) {
                write(0, 8 - remainder);
            }
        }

        int bitLength() {
            return bitLength;
        }

        int byteLength() {
            return bitLength / 8;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buffer, byteLength());
        }

        private void ensureCapacity(int bits) {
            int neededBytes = (bits + 7) / 8;
            if (neededBytes > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(neededBytes, buffer.length * 2));
            }
        }
    }
}
