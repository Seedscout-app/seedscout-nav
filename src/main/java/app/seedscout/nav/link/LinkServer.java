package app.seedscout.nav.link;

import app.seedscout.nav.protocol.BoundInterface;
import app.seedscout.nav.protocol.HandshakeDecision;
import app.seedscout.nav.protocol.HandshakeRequest;
import app.seedscout.nav.protocol.HandshakeValidator;
import app.seedscout.nav.protocol.NavProtocol;
import app.seedscout.nav.protocol.PairingToken;
import app.seedscout.nav.protocol.UnlinkFrame;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pairing listener and, once a player confirms, the linked session. One instance is one
 * pairing window from {@code shared/nav_protocol.md} section 3: created on demand when the
 * in-game pairing screen opens, and destroyed on any of the four events section 3 and the
 * task brief require: the window expiring, the session ending, the player leaving the world,
 * or client shutdown. There is no reconnect and no second window; a fresh QR code means a
 * fresh {@link LinkServer}.
 *
 * <p>A CLIENT LEVEL CHANGE IS NOT ONE OF THOSE FOUR. A nether portal changes the level without
 * the player going anywhere, so it survives the link and at most triggers
 * {@link #resendWorld()}; see {@link app.seedscout.nav.protocol.SaveIdentity}.
 *
 * <p>Lifecycle, modelled explicitly as {@link State} because "does not go live until the
 * player confirms" (the task brief) is a real state a caller must be able to observe and
 * drive, not an implementation detail:
 *
 * <pre>
 *   LISTENING            accepting upgrade attempts, port bound, no client yet
 *        |  a peer presents a valid token (HandshakeValidator ACCEPTED)
 *        v
 *   AWAITING_CONFIRMATION  exactly one socket held open; listener already closed
 *        |  confirm()                              |  reject() / unlink() / TTL expiry
 *        v                                          v
 *   LINKED  (world sent, pos sampling begins)     CLOSED
 *        |  peer disconnects / unlink() / idle timeout
 *        v
 *   CLOSED
 * </pre>
 *
 * <p>"Listener already closed" above is load bearing and is enforced in that order: the
 * {@link ServerSocket} is closed the instant {@link HandshakeValidator} returns ACCEPTED,
 * BEFORE the state moves to AWAITING_CONFIRMATION, because the pairing token is single use
 * and so from that instant no further connection could ever be accepted. Closing it from
 * the handling thread also unblocks the accept thread's {@code accept()} immediately, which
 * is deliberate: an earlier version only re-checked {@link HandshakeValidator#windowClosed()}
 * after the NEXT connection arrived, so in practice the port stayed bound and blocked in
 * {@code accept()} for the entire live session. Any observer that sees a state other than
 * LISTENING can rely on the port already being released.
 *
 * <p>Everything socket-related is bound to ONE specific chosen LAN interface address,
 * never {@code 0.0.0.0}: see {@link InterfaceSelector}. Every upgrade attempt goes through
 * {@link HandshakeValidator}, which this class never bypasses or second-guesses.
 *
 * <p>THE PRE-HANDSHAKE FLOOD. Reaching {@link HandshakeValidator} at all takes a
 * well-formed WebSocket upgrade request, so everything before that point needs its own
 * bound or it is a free way to occupy the listener. Three bounds, all of them cheap and
 * none of them observable to a peer as anything but the one shared rejection:
 *
 * <ol>
 *   <li>a hard ceiling on connections being handled at once, globally
 *       ({@value #MAX_CONCURRENT_UPGRADES}) and per remote address
 *       ({@value #MAX_CONCURRENT_UPGRADES_PER_PEER}), so one address cannot fill the
 *       listener on its own;</li>
 *   <li>an absolute deadline of {@link NavProtocol#UPGRADE_IDLE_TIMEOUT} per connection,
 *       enforced by a scheduled hard close rather than by a read timeout, so a peer cannot
 *       hold a slot by stalling the WRITE of the rejection either;</li>
 *   <li>every failure before the handshake is charged to the pairing window's failure
 *       budgets through {@link HandshakeValidator#recordPreHandshakeFailure}.</li>
 * </ol>
 */
public final class LinkServer implements AutoCloseable {

    /** See the class documentation for the transition diagram. */
    public enum State {
        LISTENING,
        AWAITING_CONFIRMATION,
        LINKED,
        CLOSED
    }

    /**
     * ERRATA (the protocol states no concurrency bound). How many connections may be in the
     * pre-upgrade phase at once, across all peers. Exactly one peer ever legitimately pairs,
     * so this only has to be large enough to absorb a phone retrying while a previous
     * attempt of its own is still timing out. Package-private so the test that proves the
     * ceiling holds can name it instead of hardcoding the number.
     */
    static final int MAX_CONCURRENT_UPGRADES = 8;

    /** The same ceiling per remote address, so one address cannot fill the global one. */
    static final int MAX_CONCURRENT_UPGRADES_PER_PEER = 3;

    private final ServerSocket serverSocket;
    private final BoundInterface bound;
    private final PairingToken token;
    private final HandshakeValidator validator;
    private final WorldSource worldSource;
    private final RenderSink renderSink;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;

    private final Object lock = new Object();
    private final Object slotLock = new Object();
    private final Map<String, Integer> inFlightByPeer = new HashMap<>();
    private int inFlightTotal;
    private volatile State state = State.LISTENING;
    private WebSocketConnection pendingConnection;
    private volatile String pendingPeerAddress;
    private LinkSession session;
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> idleWatchTask;
    private boolean listenerClosed;
    private boolean tornDown;

    private LinkServer(
            ServerSocket serverSocket,
            BoundInterface bound,
            PairingToken token,
            HandshakeValidator validator,
            WorldSource worldSource,
            RenderSink renderSink,
            Clock clock) {
        this.serverSocket = serverSocket;
        this.bound = bound;
        this.token = token;
        this.validator = validator;
        this.worldSource = worldSource;
        this.renderSink = renderSink;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "seedscout-nav-link-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens a pairing window: picks a LAN interface, binds an ephemeral port, mints a token. */
    public static LinkServer open(WorldSource worldSource, RenderSink renderSink) throws IOException {
        return open(worldSource, renderSink, Clock.systemUTC());
    }

    /**
     * Test-only seam: identical to {@link #open(WorldSource, RenderSink)} except the caller
     * supplies the clock used for token issuance and for timestamping each upgrade attempt.
     * The pairing window's real teardown timer still fires on the wall clock after
     * {@link NavProtocol#TOKEN_TTL} regardless of this clock, so a test cannot use it to
     * skip that wait; it exists to make an already-expired token reproducible without a
     * 120 second sleep. Package-private: production code always uses the system clock.
     */
    static LinkServer open(WorldSource worldSource, RenderSink renderSink, Clock clock) throws IOException {
        InterfaceSelector.Candidate candidate = InterfaceSelector.choose()
                .orElseThrow(() -> new IOException(
                        "no genuine LAN interface found to bind the pairing listener to"));
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(candidate.address(), 0));
        BoundInterface bound = new BoundInterface(
                candidate.address(), candidate.prefixLength(), serverSocket.getLocalPort());
        PairingToken token = PairingToken.issue(clock.instant());
        HandshakeValidator validator = new HandshakeValidator(token, bound);
        LinkServer server = new LinkServer(
                serverSocket, bound, token, validator, worldSource, renderSink, clock);
        server.start();
        return server;
    }

    private void start() {
        scheduler.schedule(this::expireWindow, NavProtocol.TOKEN_TTL.toMillis(), TimeUnit.MILLISECONDS);
        Thread.startVirtualThread(this::acceptLoop);
    }

    /**
     * The {@code seedscout://pair} URI (section 3) for the QR code the pairing screen
     * renders. Safe to log or display: unlike the {@code ws://} connect URL, this is the
     * value the player is meant to expose.
     */
    public String pairingUri() {
        String host = bound.address() instanceof Inet4Address
                ? bound.address().getHostAddress()
                : hostLiteralWithoutPort();
        return "seedscout://pair?h=" + host + "&p=" + bound.port()
                + "&t=" + token.uriValue() + "&v=" + NavProtocol.VERSION;
    }

    private String hostLiteralWithoutPort() {
        // BoundInterface#hostLiteral bundles in the port for the Host header form; the
        // pairing URI carries host and port as separate fields (section 3), so strip it
        // back off the bracketed IPv6 literal that method returns.
        String withPort = bound.hostLiteral();
        int lastColon = withPort.lastIndexOf(':');
        return withPort.substring(0, lastColon);
    }

    public State state() {
        return state;
    }

    /**
     * The numeric address of the peer whose handshake was accepted and which is waiting on
     * the player's in-game confirmation, for example {@code "192.168.1.42"}.
     *
     * <p>This is the ONE thing that lets a player tell their own phone from a shoulder
     * surfer or a stream viewer who read the QR code off the screen, so the confirmation
     * prompt is required to name it. It is present exactly while {@link #state()} is
     * {@link State#AWAITING_CONFIRMATION} and empty in every other state, including after
     * {@link #confirm()}: a UI should read it once when it first observes that state and
     * keep its own copy for as long as it is drawing the prompt. State and address are
     * published together under one lock, so an observer that sees AWAITING_CONFIRMATION
     * never sees an empty address for it.
     *
     * <p>The value is the literal the socket reported and nothing else: no reverse DNS
     * lookup, no port, and never anything derived from the pairing token. Safe to render
     * and safe to log, unlike every other detail of the handshake (section 3.1 rule 1).
     */
    public Optional<String> pendingPeerAddress() {
        return Optional.ofNullable(pendingPeerAddress);
    }

    /**
     * Driven by the client layer once the player confirms pairing in game. No-op unless a
     * peer's handshake has already been accepted and confirmation has not already happened.
     * Sends {@code world} and starts the {@code pos} cadence immediately.
     */
    public void confirm() {
        WebSocketConnection connection;
        synchronized (lock) {
            if (state != State.AWAITING_CONFIRMATION || pendingConnection == null) {
                return;
            }
            connection = pendingConnection;
            pendingConnection = null;
            pendingPeerAddress = null;
            session = new LinkSession(connection, worldSource, renderSink, scheduler, clock, this::onSessionEnded);
            state = State.LINKED;
            startIdleWatch();
        }
        session.start();
    }

    /**
     * Driven by the client layer when the loaded save changed under a live link: rereads
     * {@link WorldSource#worldSnapshot()} and sends a fresh {@code world} frame. No-op in every
     * state but {@link State#LINKED}, since there is no one to send it to.
     *
     * <p>The reread happens on the sending side of this call rather than here, so the caller
     * never has to hold a snapshot across the hand-off.
     */
    public void resendWorld() {
        LinkSession current;
        synchronized (lock) {
            if (state != State.LINKED) {
                return;
            }
            current = session;
        }
        if (current != null) {
            current.resendWorld();
        }
    }

    /** Driven by the client layer if the player declines the confirmation prompt. */
    public void reject() {
        WebSocketConnection connection;
        synchronized (lock) {
            if (state != State.AWAITING_CONFIRMATION) {
                return;
            }
            connection = pendingConnection;
            pendingConnection = null;
            pendingPeerAddress = null;
            state = State.CLOSED;
        }
        if (connection != null) {
            connection.close();
        }
        tearDown();
    }

    /**
     * Ends the link, if any, for the given reason (world change, quit, or shutdown) and
     * releases every resource this instance holds. {@code reason} should be one of
     * {@link UnlinkFrame}'s constants. Safe to call more than once and safe to call before
     * any client ever paired.
     */
    public void unlink(String reason) {
        LinkSession activeSession;
        WebSocketConnection connection;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            activeSession = session;
            connection = pendingConnection;
            session = null;
            pendingConnection = null;
            pendingPeerAddress = null;
            state = State.CLOSED;
        }
        if (activeSession != null) {
            activeSession.sendUnlinkAndClose(reason);
        } else if (connection != null) {
            connection.close();
        }
        tearDown();
    }

    /** {@link AutoCloseable}: client shutdown, per the task brief's four teardown triggers. */
    @Override
    public void close() {
        unlink(UnlinkFrame.CLIENT_SHUTDOWN);
    }

    /** Test-only seam: fires the same teardown the real {@link NavProtocol#TOKEN_TTL} timer
     * would fire, without waiting 120 real seconds for it. Package-private. */
    void triggerWindowExpiryForTest() {
        expireWindow();
    }

    BoundInterface boundInterface() {
        return bound;
    }

    /**
     * Test-only seam: how many connections are currently in the pre-upgrade phase and so
     * holding one of the {@value #MAX_CONCURRENT_UPGRADES} slots. Package-private, and a
     * bare count for the same reason {@link HandshakeValidator#rejectionCount()} is.
     */
    int inFlightUpgradesForTest() {
        synchronized (slotLock) {
            return inFlightTotal;
        }
    }

    private void expireWindow() {
        synchronized (lock) {
            if (state == State.LISTENING) {
                state = State.CLOSED;
            } else if (state == State.AWAITING_CONFIRMATION) {
                WebSocketConnection connection = pendingConnection;
                pendingConnection = null;
                pendingPeerAddress = null;
                state = State.CLOSED;
                if (connection != null) {
                    connection.close();
                }
            } else {
                // LINKED already: the pairing token's TTL is not this session's clock, and
                // CLOSED means there is nothing left to expire.
                return;
            }
        }
        token.revoke();
        tearDown();
    }

    private void onSessionEnded() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            session = null;
            state = State.CLOSED;
        }
        tearDown();
    }

    private void startIdleWatch() {
        pingTask = scheduler.scheduleAtFixedRate(
                this::sendPing,
                NavProtocol.LINK_PING_INTERVAL.toMillis(),
                NavProtocol.LINK_PING_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        idleWatchTask = scheduler.scheduleAtFixedRate(
                this::checkIdle, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    private void sendPing() {
        LinkSession current = session;
        if (current != null) {
            current.sendPing();
        }
    }

    private void checkIdle() {
        LinkSession current = session;
        if (current != null && current.idleMillis() >= NavProtocol.LINK_IDLE_TIMEOUT.toMillis()) {
            unlink(UnlinkFrame.IDLE_TIMEOUT);
        }
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                // The ONLY way accept() fails on a bound socket here is this class closing
                // it: a successful upgrade (see handleConnection), window expiry, or
                // teardown. All three are deliberate and all three mean this loop's job is
                // over, so there is nothing to report and nothing to retry. Section 3.1
                // rule 1 forbids logging the traffic anyway.
                break;
            }
            String peerKey = peerKey(socket.getInetAddress());
            if (!reserveSlot(peerKey)) {
                // Over the concurrency ceiling. Closed at the TCP level with no bytes
                // written: nothing is revealed about the pairing state, and there is no
                // write for the peer to stall in order to keep holding the slot.
                closeQuietly(socket);
                continue;
            }
            Thread.startVirtualThread(() -> {
                try {
                    handleConnection(socket);
                } finally {
                    releaseSlot(peerKey);
                }
            });
            if (validator.windowClosed()) {
                break;
            }
        }
        closeListener();
    }

    private void handleConnection(Socket socket) {
        // An absolute deadline, not merely a read timeout: SO_TIMEOUT bounds a blocked read
        // but nothing bounds a blocked write, so a peer that advertises a zero receive
        // window could otherwise hold this connection (and its concurrency slot) forever by
        // never reading the rejection it was sent.
        AtomicBoolean settled = new AtomicBoolean();
        ScheduledFuture<?> deadline = scheduleUpgradeDeadline(socket, settled);
        if (deadline == null) {
            closeQuietly(socket);
            return;
        }
        try {
            socket.setSoTimeout((int) NavProtocol.UPGRADE_IDLE_TIMEOUT.toMillis());
            HttpUpgradeRequest request;
            try {
                request = HttpUpgradeRequest.read(socket.getInputStream());
            } catch (IOException e) {
                // Sent nothing, sent a truncated header block, or ran out the deadline. It
                // occupied the listener either way, so it pays the same price a wrong token
                // does and gets the same response.
                rejectPreHandshake(socket);
                return;
            }
            if (!request.isWebSocketUpgrade()) {
                rejectPreHandshake(socket);
                return;
            }
            HandshakeRequest handshakeRequest = new HandshakeRequest(
                    socket.getInetAddress(),
                    request.target(),
                    request.header("host"),
                    request.header("origin"),
                    request.header("sec-websocket-protocol"),
                    clock.instant());
            HandshakeDecision decision = validator.validate(handshakeRequest);
            if (!decision.isAccepted()) {
                writeReject(socket);
                closeQuietly(socket);
                return;
            }
            // ACCEPTED means the token has just been consumed, so no connection after this
            // one can ever be accepted: the listening socket is useless from this instant
            // and is released now rather than at teardown. This also unblocks the accept
            // thread immediately instead of leaving it parked in accept() for the whole
            // session. Done BEFORE the state moves off LISTENING so that any observer of a
            // non-LISTENING state can rely on the port already being gone.
            closeListener();
            if (!settled.compareAndSet(false, true)) {
                // The upgrade deadline beat us to this socket and has already closed it.
                // The token is spent, so the window is over: end it honestly rather than
                // leaving a listener-less LISTENING state behind.
                expireWindow();
                return;
            }
            String secWebSocketKey = request.header("sec-websocket-key");
            writeAccept(socket, secWebSocketKey);
            socket.setSoTimeout(0);
            WebSocketConnection connection = new WebSocketConnection(socket);
            boolean accept;
            synchronized (lock) {
                accept = state == State.LISTENING;
                if (accept) {
                    pendingConnection = connection;
                    pendingPeerAddress = peerKey(socket.getInetAddress());
                    state = State.AWAITING_CONFIRMATION;
                }
            }
            if (!accept) {
                // The window closed between validator.validate() returning ACCEPTED and this
                // point (e.g. a concurrent expireWindow()). The validator only ever accepts
                // once, so this is a defensive close, not a path production traffic takes.
                connection.close();
            }
        } catch (IOException e) {
            closeQuietly(socket);
        } finally {
            deadline.cancel(false);
        }
    }

    /**
     * Charges a connection that died before it could present a handshake to the pairing
     * window's failure budgets, then answers it with the SAME response every rejection gets.
     * A peer therefore cannot tell a pre-handshake failure from a rejected token.
     */
    private void rejectPreHandshake(Socket socket) {
        validator.recordPreHandshakeFailure(socket.getInetAddress());
        writeReject(socket);
        closeQuietly(socket);
    }

    /**
     * Arms the hard close described in {@link #handleConnection}. Returns null only when the
     * scheduler is already shut down, which means the pairing window is over and the caller
     * should simply drop the connection.
     */
    private ScheduledFuture<?> scheduleUpgradeDeadline(Socket socket, AtomicBoolean settled) {
        try {
            return scheduler.schedule(
                    () -> {
                        if (settled.compareAndSet(false, true)) {
                            closeQuietly(socket);
                        }
                    },
                    NavProtocol.UPGRADE_IDLE_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private boolean reserveSlot(String peerKey) {
        synchronized (slotLock) {
            if (inFlightTotal >= MAX_CONCURRENT_UPGRADES) {
                return false;
            }
            if (inFlightByPeer.getOrDefault(peerKey, 0) >= MAX_CONCURRENT_UPGRADES_PER_PEER) {
                return false;
            }
            inFlightTotal++;
            inFlightByPeer.merge(peerKey, 1, Integer::sum);
            return true;
        }
    }

    private void releaseSlot(String peerKey) {
        synchronized (slotLock) {
            inFlightTotal--;
            inFlightByPeer.computeIfPresent(peerKey, (key, count) -> count == 1 ? null : count - 1);
        }
    }

    /** The numeric address literal of a peer, never a hostname: this never resolves DNS. */
    private static String peerKey(InetAddress address) {
        return address == null ? "" : address.getHostAddress();
    }

    private void writeAccept(Socket socket, String secWebSocketKey) throws IOException {
        String accept = WebSocketHandshake.acceptValue(secWebSocketKey);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        socket.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private void writeReject(Socket socket) {
        // Deliberately the SAME response for every rejection reason (wrong token, expired
        // token, bad Origin, bad Host, off-subnet peer, rate limited...): see
        // HandshakeDecision's documentation on why a rejection must be indistinguishable.
        String response = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";
        try {
            socket.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
            // The peer is being closed either way.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing meaningful to do with a failure to close an already-broken socket.
        }
    }

    private void closeListener() {
        synchronized (lock) {
            if (listenerClosed) {
                return;
            }
            listenerClosed = true;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // The port is what matters being released; a close failure has nothing further
            // to report.
        }
    }

    private void tearDown() {
        synchronized (lock) {
            if (tornDown) {
                return;
            }
            tornDown = true;
        }
        closeListener();
        ScheduledFuture<?> ping = pingTask;
        if (ping != null) {
            ping.cancel(false);
        }
        ScheduledFuture<?> idleWatch = idleWatchTask;
        if (idleWatch != null) {
            idleWatch.cancel(false);
        }
        scheduler.shutdownNow();
    }
}
