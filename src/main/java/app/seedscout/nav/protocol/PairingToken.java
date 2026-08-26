package app.seedscout.nav.protocol;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * The pairing secret from {@code shared/nav_protocol.md} section 3: 32 bytes from a
 * cryptographic RNG, base64url encoded, carried in the QR code and then in the WebSocket
 * connect URL's {@code t} query parameter (section 3.1).
 *
 * <p>Section 3 rule 1 says the token "lives only in the QR code and in memory. It MUST NOT
 * be written to disk or to a log." That is enforced here rather than merely documented:
 *
 * <ul>
 *   <li>{@link #toString()} does not contain the token, so no string concatenation, no
 *       logger format argument and no exception message can leak it by accident.</li>
 *   <li>The value is reachable through exactly one accessor, {@link #uriValue()}, whose
 *       name says the only place it belongs: the pairing URI that becomes the QR code.</li>
 *   <li>There is no serialization, no persistence hook and no getter for the raw bytes.</li>
 * </ul>
 *
 * <p>Section 3 rule 2 gives the token three independent deaths, and all three are here:
 * {@link #revoke()} for "the pairing screen closes", {@link #consume()} for "on first
 * successful use", and {@link NavProtocol#TOKEN_TTL} for "after 120 seconds". ERRATA:
 * rule 2 says the token expires on first successful use but does not say the LISTENER
 * closes. It must, or the socket stays open for a second peer to try against a token that
 * is dead anyway, and that is exactly the state a hostile LAN device wants: see
 * {@link HandshakeValidator#windowClosed()}.
 *
 * <p>Instances are safe to use from more than one thread. The mod's accept loop and the
 * game thread both touch this object.
 */
public final class PairingToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String value;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private volatile boolean consumed;
    private volatile boolean revoked;

    private PairingToken(String value, Instant issuedAt, Duration ttl) {
        this.value = value;
        this.issuedAt = issuedAt;
        this.expiresAt = issuedAt.plus(ttl);
    }

    /**
     * Mints a fresh token from {@link SecureRandom}. {@link NavProtocol#TOKEN_BYTES} bytes
     * of entropy, base64url encoded without padding, which is 43 characters and matches
     * the app side token pattern in {@code app/lib/data/nav_pair_uri.dart}.
     */
    public static PairingToken issue(Instant now) {
        Objects.requireNonNull(now, "now");
        byte[] bytes = new byte[NavProtocol.TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String encoded = ENCODER.encodeToString(bytes);
        // The array is the secret in its rawest form; there is no reason for it to stay
        // resident once encoded.
        java.util.Arrays.fill(bytes, (byte) 0);
        return new PairingToken(encoded, now, NavProtocol.TOKEN_TTL);
    }

    /**
     * The token as it appears in the {@code t} parameter of the {@code seedscout://pair}
     * URI, and nowhere else. Do not log this, do not persist it, do not put it in a crash
     * report (section 3.1 rules 1 to 3).
     */
    public String uriValue() {
        return value;
    }

    /** When this token stops being accepted on time alone (section 3 rule 2's 120 seconds). */
    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    /** True once the token has been spent by a successful handshake, or revoked. */
    public boolean isSpent() {
        return consumed || revoked;
    }

    /** True when the token would be accepted right now, before any comparison. */
    public boolean isLive(Instant now) {
        return !isSpent() && now.isBefore(expiresAt);
    }

    /**
     * Constant time check of a presented token, with liveness folded in AFTER the
     * comparison rather than short circuiting before it.
     *
     * <p>The ordering matters. Returning early for an expired or spent token would make an
     * expired token measurably faster to reject than a live but wrong one, which tells an
     * attacker whether a pairing window is currently open. The comparison therefore always
     * runs, and only then is the result combined with liveness.
     */
    public boolean verify(String presented, Instant now) {
        boolean matches = ConstantTime.sameSecret(presented, value);
        return matches && isLive(now);
    }

    /**
     * Section 3 rule 2's "expires on first successful use". Called only after a handshake
     * has passed every other check, so a rejected peer can never burn the player's token.
     */
    public void consume() {
        consumed = true;
    }

    /** Section 3 rule 2's "expires when the pairing screen closes". */
    public void revoke() {
        revoked = true;
    }

    /**
     * Deliberately free of the token value. See the class documentation: if this returned
     * the secret, then one careless {@code LOGGER.debug("token {}", token)} would put a
     * live pairing secret in a log file that outlives the 120 second window.
     */
    @Override
    public String toString() {
        return "PairingToken(" + NavProtocol.TOKEN_BYTES + " bytes, spent=" + isSpent() + ")";
    }
}
