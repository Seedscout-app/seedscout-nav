package app.seedscout.nav.protocol;

/**
 * Section 4.1's {@code world} frame, sent immediately after the player confirms pairing,
 * and resent if the loaded save changes under a live link (see {@link SaveIdentity}: a
 * dimension change is NOT such a change, and does not resend).
 *
 * <p><b>{@code seed} is a DECIMAL STRING at every layer, never a numeric type.</b> This
 * is the single most damaging thing in the protocol to get wrong, and it fails silently:
 * Minecraft seeds are full 64 bit values, the app parses this frame with a JSON reader
 * that would read a bare number as a double, and every seed past 2^53 would come out
 * subtly wrong with nothing anywhere reporting an error. The app deliberately never
 * parses this field to a number either (see {@code WorldInfo.seed} in
 * {@code app/lib/data/nav_link_service.dart}), so the string is carried end to end.
 *
 * <p>Because a {@code long} field here would be exactly the mistake, this record does not
 * have one. A caller that holds a numeric seed converts it through {@link #seedString},
 * which is the only supported bridge.
 *
 * <p>A null {@code seed} means "linked but no map", which section 4.1 says is true of
 * every multiplayer server, and the app must not guess a value for it. Note that the app
 * treats a {@code seed} that is present but NOT a JSON string (a number, most
 * dangerously) as a protocol violation and drops the link, so emitting one is not a
 * degraded mode, it is a broken mod.
 *
 * <p>{@code mcVersion} carries the exact {@code SharedConstants} version name. The
 * protocol leaves the format of this field undefined, and that gap is closed here: this
 * package holds no Minecraft import by design, so the Minecraft-facing layer reads
 * {@code SharedConstants} itself and passes the string in. It is not reformatted,
 * trimmed, or prettified on the way through.
 */
public record WorldSnapshot(
        String edition,
        String mcVersion,
        String dimension,
        String seed,
        int spawnX,
        int spawnZ) implements OutboundFrame {

    /** The only {@code edition} value this protocol version has: the Java link. */
    public static final String EDITION_JAVA = "java";

    public WorldSnapshot {
        requireNonEmpty(edition, "edition");
        requireNonEmpty(mcVersion, "mcVersion");
        requireNonEmpty(dimension, "dimension");
        if (seed != null && !isDecimalInteger(seed)) {
            throw new IllegalArgumentException("seed must be a decimal integer string or null");
        }
    }

    @Override
    public String type() {
        return "world";
    }

    /**
     * The ONLY supported way to turn a numeric world seed into the wire value. Kept as a
     * named bridge so the conversion is visible at the call site rather than happening
     * implicitly inside an encoder.
     */
    public static String seedString(long seed) {
        return Long.toString(seed);
    }

    private static boolean isDecimalInteger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
    }
}
