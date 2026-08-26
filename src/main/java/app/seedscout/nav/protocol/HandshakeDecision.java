package app.seedscout.nav.protocol;

/**
 * What {@link HandshakeValidator} decided about one HTTP upgrade request: let it through,
 * or close it.
 *
 * <p><b>{@link Rejected} carries no reason, on purpose.</b> Section 3 rule 4: "a failed
 * token MUST close the connection without indicating whether the token was wrong or
 * expired". This implementation goes further than the letter of that rule and makes EVERY
 * rejection indistinguishable, not just the two the rule names: a wrong token, an expired
 * token, a spent token, a browser {@code Origin}, a rebinding {@code Host}, an off subnet
 * peer, a requested subprotocol and a rate limited address all produce the same single
 * instance. A caller cannot tell them apart, so a caller cannot leak them apart, whether
 * through a status code, a close reason, a response body or a log line.
 *
 * <p>That is deliberately stricter than {@link DropReason}, which does name its reasons.
 * The asymmetry is the trust boundary: a handshake rejection is observed by an
 * UNAUTHENTICATED peer, while a frame drop happens after that peer is already through the
 * door and has a token the player showed it.
 *
 * <p>The mod may log the FACT of a rejection, as a bare running count and nothing else
 * (section 3.1 rule 1 forbids logging the request line, URI or query string at any level,
 * because the token is in it).
 */
public sealed interface HandshakeDecision {

    /** True for exactly one of the two outcomes. Kept so callers do not pattern match by accident. */
    boolean isAccepted();

    /**
     * The peer may complete the WebSocket upgrade.
     *
     * <p>Reaching this outcome has already consumed the pairing token, and the pairing
     * window is now closed: see {@link HandshakeValidator#windowClosed()}. The caller MUST
     * stop accepting new connections, which is the ERRATA this implementation adds to
     * section 3 rule 2 (the rule says the token expires on first successful use, but never
     * says the listener closes).
     */
    record Accepted() implements HandshakeDecision {
        @Override
        public boolean isAccepted() {
            return true;
        }
    }

    /**
     * The peer is refused. No field, no subclass, no reason: see the interface
     * documentation for why this type is deliberately opaque.
     */
    final class Rejected implements HandshakeDecision {

        private Rejected() {
        }

        @Override
        public boolean isAccepted() {
            return false;
        }

        /** Identical for every rejection, so a log or a test cannot distinguish two of them. */
        @Override
        public String toString() {
            return "Rejected";
        }
    }

    /** The single accept outcome. */
    HandshakeDecision ACCEPTED = new Accepted();

    /**
     * The single reject outcome. Every rejection returns THIS instance, so
     * {@code assertSame} in a test proves indistinguishability rather than merely
     * asserting equal fields.
     */
    HandshakeDecision REJECTED = new Rejected();
}
