package app.seedscout.nav.protocol;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The gate on the WebSocket upgrade: the one place that decides whether a peer on the wifi
 * gets to talk to the mod at all.
 *
 * <p>No sockets, no threads of its own, no Minecraft, no logging. It is a function from
 * ({@link HandshakeRequest}, its own pairing state) to {@link HandshakeDecision}, which is
 * what lets every rule below be asserted individually in a headless test rather than
 * demonstrated by hand against a real phone.
 *
 * <p>THE RULES, in the order they run. Every one of them produces the SAME opaque
 * {@link HandshakeDecision#REJECTED} instance, so the order is not observable to a peer:
 *
 * <ol>
 *   <li><b>The window is still open.</b> Once a handshake has been accepted, the token is
 *       spent and this validator refuses everything. ERRATA: section 3 rule 2 says the
 *       token "expires on first successful use" but never says the LISTENER closes; it
 *       must, so the caller closes its listening socket the moment this class returns
 *       {@link HandshakeDecision#ACCEPTED} rather than waiting to poll
 *       {@link #windowClosed()} on some later connection.</li>
 *   <li><b>Global failure ceiling.</b> {@link NavProtocol#MAX_FAILURES_PER_WINDOW} failures
 *       across all peers kills the pairing window outright and forces the player to open a
 *       fresh QR code.</li>
 *   <li><b>Per peer failure ceiling.</b> {@link NavProtocol#MAX_FAILURES_PER_PEER} failures
 *       from one remote address exhaust that address for the rest of the window, even if
 *       it later presents the correct token. Section 3 rule 4 requires a failed token to be
 *       rate limited; these two numbers are the ERRATA that gives that rule a value.</li>
 *   <li><b>No {@code Origin} header.</b> {@code dart:io}'s {@code WebSocket.connect} sends
 *       none and every browser sends one, so an {@code Origin} of any value, including an
 *       empty one, means the peer is a web page rather than the app. That is the cheapest
 *       defence there is against a page on any LAN device (or any page the player happens
 *       to be viewing) opening this socket from the background.</li>
 *   <li><b>{@code Host} equals the bound {@code ip:port} literal.</b> This is the DNS
 *       rebinding defence: an attacker page can make a browser resolve a name they control
 *       to this LAN address, but it cannot make the browser send a {@code Host} header of
 *       the raw address literal. Absent, empty or different, all rejected.</li>
 *   <li><b>No subprotocol requested.</b> The protocol has exactly one frame language
 *       (section 4). A peer negotiating for another one is not the app.</li>
 *   <li><b>The peer is on the bound subnet and in a private range.</b> See
 *       {@link BoundInterface#admits}. Loopback is not available as a boundary here because
 *       the phone is a different device, which makes this check carry the weight that a
 *       localhost only design would have got for free.</li>
 *   <li><b>The request target is exactly {@code /?t=<token>}.</b> Section 3.1 fixes that
 *       shape. One parameter, no path, no extras.</li>
 *   <li><b>The token matches, in constant time, and is live.</b> See {@link PairingToken}
 *       and {@link ConstantTime}.</li>
 * </ol>
 *
 * <p>Only after ALL of these pass is the token consumed, so a rejected peer can never burn
 * the player's token or their pairing window on its own.
 *
 * <p>PRE-HANDSHAKE FAILURES. A peer can fail before it ever produces a
 * {@link HandshakeRequest} at all: raw TCP that sends nothing, a truncated header block, a
 * plain HTTP probe that is not a WebSocket upgrade. Those never reach {@link #validate} and
 * used to cost an attacker nothing, which left the failure ceilings above trivially
 * side-steppable by a flood of half-open sockets. {@link #recordPreHandshakeFailure} charges
 * such a connection to the same two budgets, under exactly the same non-counting rules
 * (a closed or killed window and an already exhausted peer still do not count), so a peer
 * cannot reach the global ceiling on its own. It returns nothing: a caller learns no more
 * from a pre-handshake failure than a peer does from a rejection.
 *
 * <p>WHAT THIS CLASS NEVER DOES: it never records, returns or exposes which rule failed.
 * {@link #rejectionCount()} is a bare number, which is the most a caller can be trusted
 * with given section 3.1 rule 1 forbids logging the request line at any level.
 */
public final class HandshakeValidator {

    /** Base64url alphabet plus the padding character the app's own token pattern allows. */
    private static final String TOKEN_ALPHABET_EXTRA = "-_=";

    private final PairingToken token;
    private final BoundInterface bound;

    private final Map<String, Integer> failuresByPeer = new HashMap<>();
    private int totalFailures;
    private boolean accepted;
    private boolean killed;

    public HandshakeValidator(PairingToken token, BoundInterface bound) {
        this.token = Objects.requireNonNull(token, "token");
        this.bound = Objects.requireNonNull(bound, "bound");
    }

    /**
     * Decides one upgrade request. Never throws, for any input: a malformed request line, a
     * missing header or an address family nobody expected all land as
     * {@link HandshakeDecision#REJECTED}.
     */
    public synchronized HandshakeDecision validate(HandshakeRequest request) {
        if (request == null) {
            return HandshakeDecision.REJECTED;
        }

        // Rule 1 and 2. A request refused because the window is already closed or dead is
        // NOT counted as a new failure: counting it would let one blocked peer drive the
        // global counter up on its own, and the global counter is what forces an innocent
        // player to rescan.
        if (accepted || killed || token.isSpent()) {
            return HandshakeDecision.REJECTED;
        }

        String peerKey = peerKey(request.remoteAddress());

        // Rule 3. Same reasoning: an already exhausted peer cannot spend the window's
        // global budget by knocking repeatedly.
        if (failuresByPeer.getOrDefault(peerKey, 0) >= NavProtocol.MAX_FAILURES_PER_PEER) {
            return HandshakeDecision.REJECTED;
        }

        if (!passesEveryCheck(request)) {
            return recordFailure(peerKey);
        }

        // Section 3 rule 2: first successful use spends the token, and this window is now
        // closed to everybody (see windowClosed()).
        token.consume();
        accepted = true;
        return HandshakeDecision.ACCEPTED;
    }

    /**
     * Charges one connection that failed BEFORE it could produce a handshake request to the
     * per peer and global failure budgets: a peer that connected and sent nothing until the
     * upgrade deadline, sent a header block this listener could not read, or sent a request
     * that was not a WebSocket upgrade at all.
     *
     * <p>Deliberately void. The caller (the accept path) is told nothing it could turn into
     * a distinguishable response, and the two counters it feeds are the same ones
     * {@link #validate} uses, so a flood of cheap probes now runs into the same ceilings a
     * flood of wrong tokens does instead of running beside them for free.
     *
     * <p>Nothing is counted when the window is already closed or killed, or when this peer
     * has already exhausted its own allowance: identical to rules 1 to 3 of {@link #validate},
     * and for the identical reason, that one blocked peer must not be able to drive the
     * global counter, which is what forces an innocent player to rescan.
     */
    public synchronized void recordPreHandshakeFailure(InetAddress remoteAddress) {
        if (accepted || killed || token.isSpent()) {
            return;
        }
        String peerKey = peerKey(remoteAddress);
        if (failuresByPeer.getOrDefault(peerKey, 0) >= NavProtocol.MAX_FAILURES_PER_PEER) {
            return;
        }
        recordFailure(peerKey);
    }

    /**
     * Rules 4 to 9, as one boolean. Split out from {@link #validate} so the counting and
     * window bookkeeping cannot accidentally short circuit a security check, and written
     * without early returns per rule so no rule can be skipped by an edit that reorders it.
     */
    private boolean passesEveryCheck(HandshakeRequest request) {
        // Rule 4: any Origin at all, including an empty one, means a browser.
        if (request.originHeader() != null) {
            return false;
        }
        // Rule 5: exact ip:port literal. Case insensitive only because IPv6 hex digits may
        // arrive in either case; there is no hostname form that can pass this.
        String host = request.hostHeader();
        if (host == null || !host.trim().equalsIgnoreCase(bound.hostLiteral())) {
            return false;
        }
        // Rule 6: no subprotocol negotiation.
        if (request.subprotocolHeader() != null && !request.subprotocolHeader().isBlank()) {
            return false;
        }
        // Rule 7: on our subnet, and privately addressed.
        if (!bound.admits(request.remoteAddress())) {
            return false;
        }
        // Rules 8 and 9: the target shape, then the secret itself.
        String presented = presentedToken(request.requestTarget());
        return token.verify(presented, request.receivedAt());
    }

    /**
     * Extracts {@code t} from a request target that must be exactly {@code /?t=<value>}
     * (section 3.1). Returns null for any other shape, which the caller feeds to
     * {@link PairingToken#verify} unchanged: a null presented token fails the constant time
     * comparison like any other wrong value, so a malformed target and a wrong token take
     * the same path out.
     */
    private static String presentedToken(String requestTarget) {
        if (requestTarget == null) {
            return null;
        }
        int query = requestTarget.indexOf('?');
        if (query < 0) {
            return null;
        }
        String path = requestTarget.substring(0, query);
        if (!path.equals("/") && !path.isEmpty()) {
            return null;
        }
        String queryString = requestTarget.substring(query + 1);
        if (queryString.indexOf('&') >= 0) {
            // Exactly one parameter. A second one is either a sender that does not know
            // this protocol or an attempt to confuse a laxer parser downstream.
            return null;
        }
        if (!queryString.startsWith("t=")) {
            return null;
        }
        String value = queryString.substring(2);
        if (value.isEmpty() || !isTokenShaped(value)) {
            return null;
        }
        return value;
    }

    /**
     * base64url and nothing else. Rejecting a percent escape here (rather than decoding it)
     * means there is exactly one spelling of any given token, so no normalisation step can
     * ever disagree with the comparison.
     */
    private static boolean isTokenShaped(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || TOKEN_ALPHABET_EXTRA.indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private HandshakeDecision recordFailure(String peerKey) {
        failuresByPeer.merge(peerKey, 1, Integer::sum);
        totalFailures++;
        if (totalFailures >= NavProtocol.MAX_FAILURES_PER_WINDOW) {
            killed = true;
            // The token dies with the window: a fresh QR code is a fresh token, and the
            // dead one must not survive in memory as something a later check could accept.
            token.revoke();
        }
        return HandshakeDecision.REJECTED;
    }

    private static String peerKey(InetAddress address) {
        return address == null ? "" : address.getHostAddress();
    }

    /**
     * How many requests this window has rejected, in total, with no breakdown by reason and
     * no per peer detail. This is the ONLY thing a caller may log about a rejection
     * (section 3.1 rule 1), and it is a bare count precisely so there is nothing else to
     * log by accident.
     */
    public synchronized int rejectionCount() {
        return totalFailures;
    }

    /**
     * True once the listener must stop accepting connections: a handshake succeeded (the
     * ERRATA on section 3 rule 2), the failure ceiling killed the window, or the token was
     * spent or revoked because the pairing screen closed. Expiry by age is deliberately not
     * folded in here, because this class holds no clock: the caller that runs the pairing
     * screen already has one and closes the window when {@link PairingToken#expiresAt()}
     * passes.
     */
    public synchronized boolean windowClosed() {
        return accepted || killed || token.isSpent();
    }

    /**
     * True when the window died on the failure ceiling rather than on a success. The
     * in-game screen shows a rescan prompt for this case; the peer is told nothing, because
     * to the peer it is just another identical rejection.
     */
    public synchronized boolean windowKilled() {
        return killed;
    }

    /** True when a handshake has been accepted, which happens at most once per token. */
    public synchronized boolean hasAccepted() {
        return accepted;
    }
}
