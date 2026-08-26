package app.seedscout.nav.protocol;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything {@link HandshakeValidator} is allowed to look at, lifted out of the HTTP
 * upgrade request as plain values.
 *
 * <p>This record is the reason the validator can be a pure function with zero Minecraft and
 * zero networking imports beyond {@link InetAddress}: the transport layer reads the socket
 * and fills this in, and the security decision is then testable headlessly with no server,
 * no port and no clock.
 *
 * <p><b>{@link #toString()} is overridden and omits {@link #requestTarget()}.</b> That field
 * holds {@code /?t=<token>}, so the generated record {@code toString} would put a live
 * pairing secret into any log line, assertion message or exception that ever formatted this
 * object. Section 3.1 rule 1 forbids logging the request line, request URI or query string
 * for this listener at any level, including on the rejection path, and a record's default
 * {@code toString} is exactly the accident that rule is written against.
 */
public record HandshakeRequest(
        InetAddress remoteAddress,
        String requestTarget,
        String hostHeader,
        String originHeader,
        String subprotocolHeader,
        Instant receivedAt) {

    public HandshakeRequest {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(receivedAt, "receivedAt");
        // requestTarget, hostHeader, originHeader and subprotocolHeader are all nullable
        // on purpose: a missing header is a real thing a peer can send, and the validator
        // has to decide about it rather than crash on it.
    }

    /**
     * A request with no {@code Origin} and no requested subprotocol, which is what
     * {@code dart:io}'s {@code WebSocket.connect} actually sends (see
     * {@code IoNavLinkTransport} in {@code app/lib/data/nav_link_service.dart}).
     */
    public static HandshakeRequest of(
            InetAddress remoteAddress, String requestTarget, String hostHeader, Instant receivedAt) {
        return new HandshakeRequest(remoteAddress, requestTarget, hostHeader, null, null, receivedAt);
    }

    /** Never includes {@link #requestTarget()}. See the class documentation. */
    @Override
    public String toString() {
        return "HandshakeRequest(from=" + remoteAddress.getHostAddress()
                + ", host=" + hostHeader
                + ", origin=" + (originHeader == null ? "absent" : "present")
                + ", subprotocol=" + (subprotocolHeader == null ? "absent" : "present")
                + ", at=" + receivedAt + ")";
    }
}
