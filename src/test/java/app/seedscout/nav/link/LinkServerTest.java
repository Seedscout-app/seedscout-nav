package app.seedscout.nav.link;

import app.seedscout.nav.protocol.NavProtocol;
import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.WorldSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real socket end to end tests: a {@link LinkServer} against a stub {@link WorldSource}, driven
 * by both the JDK's built-in {@code java.net.http.WebSocket} client (the happy path and every
 * case the client can express) and a hand rolled raw socket handshake (the cases that need
 * headers the JDK client will not let a caller set, such as {@code Origin} and {@code Host}).
 * No Minecraft, no Loom run task, no game window: this proves the entire accept and reject
 * path headlessly.
 */
class LinkServerTest {

    private static final String SEC_WEBSOCKET_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private final List<LinkServer> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() {
        for (LinkServer server : opened) {
            server.close();
        }
        opened.clear();
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    private static final class StubWorldSource implements WorldSource {
        volatile WorldSnapshot world = new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, "1.21.11", "overworld",
                WorldSnapshot.seedString(-4172144997902289642L), 112, -208);
        volatile PlayerPosition position = new PlayerPosition(10.0, 64.0, 10.0, 0.0, "overworld");

        @Override
        public WorldSnapshot worldSnapshot() {
            return world;
        }

        @Override
        public PlayerPosition playerPosition() {
            return position;
        }
    }

    private static final class RecordingRenderSink implements RenderSink {
        final List<RouteFrame> routes = new CopyOnWriteArrayList<>();
        volatile int clears;

        @Override
        public void showRoute(RouteFrame route) {
            routes.add(route);
        }

        @Override
        public void clearRoute() {
            clears++;
        }
    }

    /** A clock a test can jump forward on demand, with no dependency on wall-clock sleeps. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private LinkServer open(WorldSource worldSource, RenderSink renderSink) throws IOException {
        LinkServer server = LinkServer.open(worldSource, renderSink);
        opened.add(server);
        return server;
    }

    private LinkServer open(WorldSource worldSource, RenderSink renderSink, Clock clock) throws IOException {
        LinkServer server = LinkServer.open(worldSource, renderSink, clock);
        opened.add(server);
        return server;
    }

    private static String tokenFrom(LinkServer server) {
        Matcher m = Pattern.compile("[?&]t=([^&]+)").matcher(server.pairingUri());
        assertTrue(m.find(), "pairing URI must carry a t= token: " + server.pairingUri());
        return m.group(1);
    }

    private static InetSocketAddress addressOf(LinkServer server) {
        var bound = server.boundInterface();
        return new InetSocketAddress(bound.address(), bound.port());
    }

    private static void awaitState(LinkServer server, LinkServer.State expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (server.state() == expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for state " + expected);
            }
        }
        fail("server never reached " + expected + ", was " + server.state());
    }

    private WebSocket connect(LinkServer server, String token, WebSocket.Listener listener) {
        var bound = server.boundInterface();
        URI uri = URI.create("ws://" + bound.hostLiteral() + "/?t=" + token);
        HttpClient client = HttpClient.newHttpClient();
        try {
            return client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri, listener)
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class CollectingListener implements WebSocket.Listener {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final StringBuilder buffer = new StringBuilder();

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Raw handshake helper, for cases java.net.http.WebSocket cannot express
    // (a custom Origin header, or a Host header that does not match the bind address).
    // ------------------------------------------------------------------

    private static String rawHandshakeStatusLine(
            InetSocketAddress address, String target, String host, String origin) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, 3000);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(target).append(" HTTP/1.1\r\n");
            if (host != null) {
                request.append("Host: ").append(host).append("\r\n");
            }
            request.append("Upgrade: websocket\r\n");
            request.append("Connection: Upgrade\r\n");
            request.append("Sec-WebSocket-Key: ").append(SEC_WEBSOCKET_KEY).append("\r\n");
            request.append("Sec-WebSocket-Version: 13\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readStatusLine(socket.getInputStream());
        }
    }

    /**
     * A connection that is NOT a WebSocket upgrade at all: a plain HTTP probe, the shape a
     * port scanner or a browser typed at the port produces. It never reaches
     * {@code HandshakeValidator.validate}, which is exactly why its cost has to be charged
     * some other way (finding M4b).
     */
    private static String rawProbeStatusLine(InetSocketAddress address, String host) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, 3000);
            socket.setSoTimeout(3000);
            String request = "GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readStatusLine(socket.getInputStream());
        }
    }

    private static String readStatusLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            line.write(b);
            if (prev == '\r' && b == '\n') {
                break;
            }
            prev = b;
        }
        return line.toString(StandardCharsets.US_ASCII).trim();
    }

    // ------------------------------------------------------------------
    // The happy path and the full message flow
    // ------------------------------------------------------------------

    @Test
    @Timeout(15)
    void successfulPairAndFullMessageFlow() throws Exception {
        StubWorldSource world = new StubWorldSource();
        RecordingRenderSink sink = new RecordingRenderSink();
        LinkServer server = open(world, sink);
        String token = tokenFrom(server);

        CollectingListener listener = new CollectingListener();
        WebSocket socket = connect(server, token, listener);

        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);
        server.confirm();
        awaitState(server, LinkServer.State.LINKED);

        waitFor(() -> !listener.messages.isEmpty(), "world frame");
        String worldFrame = listener.messages.get(0);
        assertTrue(worldFrame.contains("\"type\":\"world\""), worldFrame);
        assertTrue(worldFrame.contains("\"seed\":\"-4172144997902289642\""),
                "seed must be a decimal string on the wire: " + worldFrame);
        assertFalse(worldFrame.matches(".*\"seed\":-?[0-9]+[,}].*"),
                "seed must never appear as a bare JSON number: " + worldFrame);

        waitFor(() -> listener.messages.size() >= 2, "at least one pos frame");
        assertTrue(listener.messages.stream().anyMatch(m -> m.contains("\"type\":\"pos\"")));

        String route = "{\"type\":\"route\",\"id\":1,\"dimension\":\"overworld\","
                + "\"label\":\"Woodland Mansion\",\"points\":[[0,0],[240,96],[512,96]]}";
        socket.sendText(route, true).get(3, TimeUnit.SECONDS);
        waitFor(() -> !sink.routes.isEmpty(), "route to be received");
        assertEquals(1L, sink.routes.get(0).id());
        assertEquals("Woodland Mansion", sink.routes.get(0).label().literalText());

        socket.sendText("{\"type\":\"clear\"}", true).get(3, TimeUnit.SECONDS);
        waitFor(() -> sink.clears > 0, "clear to be received");
    }

    /**
     * The transport half of the nether-portal fix: a save change under a live link puts a
     * SECOND {@code world} frame on the wire, freshly reread from the {@link WorldSource}, and
     * the link stays LINKED through it.
     *
     * <p>This proves the resend path carries bytes. It does NOT prove a real portal keeps a real
     * link alive: whether {@code resendWorld()} is called at the right moments lives in
     * {@code SeedscoutNavClientHooks} and {@code app.seedscout.nav.protocol.SaveIdentity},
     * where the discrimination is unit tested separately, and neither can be exercised against
     * a running game here.
     */
    @Test
    @Timeout(15)
    void resendWorldSendsASecondWorldFrameAndKeepsTheLink() throws Exception {
        StubWorldSource world = new StubWorldSource();
        RecordingRenderSink sink = new RecordingRenderSink();
        LinkServer server = open(world, sink);
        String token = tokenFrom(server);

        CollectingListener listener = new CollectingListener();
        connect(server, token, listener);

        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);
        server.confirm();
        awaitState(server, LinkServer.State.LINKED);
        waitFor(() -> countWorldFrames(listener) >= 1, "the first world frame");

        // A different save is now loaded under the link.
        world.world = new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, "1.21.11", "overworld",
                WorldSnapshot.seedString(42L), 8, -8);
        server.resendWorld();

        waitFor(() -> countWorldFrames(listener) >= 2, "the resent world frame");
        String resent = listener.messages.stream()
                .filter(m -> m.contains("\"type\":\"world\""))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(resent.contains("\"seed\":\"42\""),
                "the resend must reread the WorldSource, not replay the frame sent at confirm: "
                        + resent);
        assertEquals(LinkServer.State.LINKED, server.state(),
                "a save change must not end the session; that was the portal bug");
    }

    @Test
    @Timeout(15)
    void resendWorldIsANoOpBeforeConfirmAndAfterClose() throws Exception {
        StubWorldSource world = new StubWorldSource();
        RecordingRenderSink sink = new RecordingRenderSink();
        LinkServer server = open(world, sink);
        String token = tokenFrom(server);

        // LISTENING: nothing to send to, and the pairing window must survive being asked.
        server.resendWorld();
        assertEquals(LinkServer.State.LISTENING, server.state());

        CollectingListener listener = new CollectingListener();
        connect(server, token, listener);
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);

        // AWAITING_CONFIRMATION: the player has not confirmed, so no world frame exists yet.
        server.resendWorld();
        assertEquals(LinkServer.State.AWAITING_CONFIRMATION, server.state());
        assertEquals(0, countWorldFrames(listener),
                "a world frame must never precede the player's confirmation");

        server.close();
        awaitState(server, LinkServer.State.CLOSED);
        server.resendWorld();
        assertEquals(LinkServer.State.CLOSED, server.state());
    }

    private static long countWorldFrames(CollectingListener listener) {
        return listener.messages.stream().filter(m -> m.contains("\"type\":\"world\"")).count();
    }

    /** True if an {@link IOException} appears anywhere in the cause chain of {@code t}. */
    private static boolean chainContainsIOException(Throwable t) {
        return chainContains(t, IOException.class);
    }

    /** True if {@code type} appears anywhere in the cause chain of {@code t}. */
    private static boolean chainContains(Throwable t, Class<? extends Throwable> type) {
        Throwable current = t;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    @Timeout(10)
    void wrongTokenIsRejected() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> connect(server, "not-the-real-token-aaaaaaaaaaaaaaaaaaaaaaaaaa",
                        new WebSocket.Listener() {}));
        assertTrue(chainContainsIOException(failure), failure.toString());
        assertEquals(LinkServer.State.LISTENING, server.state());
    }

    @Test
    @Timeout(10)
    void replayedTokenFailsOnSecondUse() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String token = tokenFrom(server);

        connect(server, token, new WebSocket.Listener() {});
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);

        // The listener is closed by the first successful upgrade, BEFORE the state leaves
        // LISTENING (section 3 rule 2's ERRATA), so by the time the state above is
        // observable the port is already gone and the replay is refused at the TCP layer.
        // Asserting the exact failure matters: an earlier version of this test claimed the
        // reconnect "cannot even complete a TCP handshake" while asserting only a bare
        // Exception, and at that time the reconnect in fact completed TCP and was answered
        // with a 400, so the comment was false and the assertion could not have caught it.
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> connect(server, token, new WebSocket.Listener() {}));
        assertTrue(chainContains(failure, ConnectException.class),
                "the replay must be refused by a closed port, not answered: " + failure);
    }

    @Test
    @Timeout(10)
    void secondClientCannotConnectAfterOneSucceeded() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String token = tokenFrom(server);

        connect(server, token, new WebSocket.Listener() {});
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> connect(server, token, new WebSocket.Listener() {}));
        assertTrue(chainContains(failure, ConnectException.class), failure.toString());
        assertEquals(LinkServer.State.AWAITING_CONFIRMATION, server.state());
    }

    @Test
    @Timeout(10)
    void expiredTokenIsRejected() throws Exception {
        MutableClock clock = new MutableClock();
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink(), clock);
        String token = tokenFrom(server);

        clock.advance(NavProtocol.TOKEN_TTL.plusSeconds(1));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> connect(server, token, new WebSocket.Listener() {}));
        assertTrue(chainContainsIOException(failure), failure.toString());
    }

    @Test
    @Timeout(10)
    void originHeaderIsRejected() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String token = tokenFrom(server);
        var bound = server.boundInterface();

        String status = rawHandshakeStatusLine(
                addressOf(server), "/?t=" + token, bound.hostLiteral(), "http://evil.example");
        assertTrue(status.startsWith("HTTP/1.1 400"), status);
    }

    @Test
    @Timeout(10)
    void wrongHostIsRejected() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String token = tokenFrom(server);

        String status = rawHandshakeStatusLine(
                addressOf(server), "/?t=" + token, "attacker.example:80", null);
        assertTrue(status.startsWith("HTTP/1.1 400"), status);
    }

    @Test
    @Timeout(15)
    void rateLimitTripsAfterFiveFailuresFromOnePeer() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String realToken = tokenFrom(server);
        var bound = server.boundInterface();

        for (int i = 0; i < NavProtocol.MAX_FAILURES_PER_PEER; i++) {
            String status = rawHandshakeStatusLine(
                    addressOf(server), "/?t=wrong-token-attempt-" + i + "-aaaaaaaaaaaaaaaaaaaaaa",
                    bound.hostLiteral(), null);
            assertTrue(status.startsWith("HTTP/1.1 400"), status);
        }

        // The address is now exhausted: even the CORRECT token must be refused.
        String status = rawHandshakeStatusLine(addressOf(server), "/?t=" + realToken, bound.hostLiteral(), null);
        assertTrue(status.startsWith("HTTP/1.1 400"), status);
        assertEquals(LinkServer.State.LISTENING, server.state());
    }

    // ------------------------------------------------------------------
    // Lifecycle: the listener is really gone once the window is gone
    // ------------------------------------------------------------------

    /**
     * FINDING M4a. The class doc promises the listener is already closed in
     * AWAITING_CONFIRMATION. It was not: the accept loop only re-checked
     * {@code windowClosed()} after the NEXT connection arrived, so the port stayed bound and
     * parked in {@code accept()} for the whole live session. The port must be gone with no
     * confirm(), no second connection and no teardown, and closing it must not disturb the
     * connection that was just accepted.
     */
    @Test
    @Timeout(15)
    void successfulPairClosesTheListenerAndReleasesThePort() throws Exception {
        StubWorldSource world = new StubWorldSource();
        LinkServer server = open(world, new RecordingRenderSink());
        String token = tokenFrom(server);
        var bound = server.boundInterface();

        CollectingListener listener = new CollectingListener();
        connect(server, token, listener);
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);

        assertThrows(ConnectException.class, () -> {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(bound.address(), bound.port()), 1000);
            }
        }, "the pairing port must be released the moment a pairing is accepted");

        // The accepted socket is untouched by that close: confirming still links and the
        // session still talks.
        server.confirm();
        awaitState(server, LinkServer.State.LINKED);
        waitFor(() -> !listener.messages.isEmpty(), "world frame after the listener closed");
        assertTrue(listener.messages.get(0).contains("\"type\":\"world\""), listener.messages.get(0));
    }

    /**
     * FINDING M3a. The confirmation prompt has to be able to NAME the peer, because that is
     * the only thing separating the player's own phone from someone who read the QR code off
     * the screen. This is the accessor the pairing UI consumes.
     */
    @Test
    @Timeout(10)
    void pendingPeerAddressNamesTheWaitingPeer() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String token = tokenFrom(server);
        var bound = server.boundInterface();

        assertTrue(server.pendingPeerAddress().isEmpty(), "nothing is pending while LISTENING");

        connect(server, token, new WebSocket.Listener() {});
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);

        String peer = server.pendingPeerAddress().orElseThrow();
        assertFalse(peer.isBlank(), "the prompt must have something to show");
        assertTrue(bound.admits(java.net.InetAddress.getByName(peer)),
                "the address shown must be the real peer literal, admitted by the same "
                        + "subnet rule that let it in: " + peer);
        assertFalse(peer.contains(token), "the peer address must never carry the token");

        server.confirm();
        awaitState(server, LinkServer.State.LINKED);
        assertTrue(server.pendingPeerAddress().isEmpty(), "cleared once there is nothing pending");
    }

    // ------------------------------------------------------------------
    // FINDING M4b: nothing before the handshake is free
    // ------------------------------------------------------------------

    /**
     * A probe that is not a WebSocket upgrade never reaches the validator, so it used to
     * cost an attacker nothing at all: the 5-per-peer and 20-global ceilings could be
     * side-stepped entirely by never sending a token. It must now spend the same budget a
     * wrong token does, and it must still be answered with the one shared rejection.
     */
    @Test
    @Timeout(15)
    void preHandshakeProbesSpendTheFailureBudget() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        String realToken = tokenFrom(server);
        var bound = server.boundInterface();

        for (int i = 0; i < NavProtocol.MAX_FAILURES_PER_PEER; i++) {
            String status = rawProbeStatusLine(addressOf(server), bound.hostLiteral());
            assertTrue(status.startsWith("HTTP/1.1 400"),
                    "a pre-handshake probe must get the same rejection as everything else: " + status);
        }

        // This address has now spent its whole allowance without ever presenting a token,
        // so even the CORRECT token from it is refused for the rest of the window.
        String status = rawHandshakeStatusLine(
                addressOf(server), "/?t=" + realToken, bound.hostLiteral(), null);
        assertTrue(status.startsWith("HTTP/1.1 400"), status);
        assertEquals(LinkServer.State.LISTENING, server.state());
    }

    /**
     * The other half of M4b: half-open sockets are bounded in number, not just in lifetime.
     * Each silent connection would otherwise sit on the listener for the whole
     * {@link NavProtocol#UPGRADE_IDLE_TIMEOUT}; past the per-peer ceiling they are dropped
     * at once instead. One machine can only exercise the per-peer ceiling, which is the
     * tighter of the two and is what stops a single attacker filling the global one.
     */
    @Test
    @Timeout(30)
    void concurrentPreHandshakeConnectionsAreCapped() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        InetSocketAddress address = addressOf(server);
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < LinkServer.MAX_CONCURRENT_UPGRADES_PER_PEER; i++) {
                Socket socket = new Socket();
                socket.connect(address, 3000);
                held.add(socket); // sends nothing, ever: this is the flood being bounded
            }
            waitFor(() -> server.inFlightUpgradesForTest() >= LinkServer.MAX_CONCURRENT_UPGRADES_PER_PEER,
                    "the held connections to occupy their slots");

            long startNanos = System.nanoTime();
            boolean closedByServer;
            try (Socket overCap = new Socket()) {
                overCap.connect(address, 3000);
                // Comfortably longer than the upgrade budget, so an unbounded connection
                // fails this test by elapsed time rather than by timing out the read.
                overCap.setSoTimeout((int) NavProtocol.UPGRADE_IDLE_TIMEOUT.toMillis() + 5000);
                try {
                    closedByServer = overCap.getInputStream().read() == -1;
                } catch (java.net.SocketException reset) {
                    closedByServer = true; // a reset is a close too
                }
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            assertTrue(closedByServer, "an over-cap connection must be dropped, not served");
            assertTrue(elapsed.compareTo(NavProtocol.UPGRADE_IDLE_TIMEOUT) < 0,
                    "an over-cap connection must be dropped immediately, not held for the "
                            + "whole upgrade budget; it lasted " + elapsed);
        } finally {
            for (Socket socket : held) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Test cleanup only.
                }
            }
        }
    }

    @Test
    @Timeout(10)
    void windowExpiryClosesTheListenerAndReleasesThePort() throws Exception {
        LinkServer server = open(new StubWorldSource(), new RecordingRenderSink());
        var bound = server.boundInterface();

        server.triggerWindowExpiryForTest();
        waitFor(() -> server.state() == LinkServer.State.CLOSED, "server to close");

        assertThrows(ConnectException.class, () -> {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(bound.address(), bound.port()), 1000);
            }
        });
    }

    @Test
    @Timeout(10)
    void closeTearsDownAConfirmedSessionCleanly() throws Exception {
        StubWorldSource world = new StubWorldSource();
        LinkServer server = open(world, new RecordingRenderSink());
        String token = tokenFrom(server);
        CountDownLatch closedLatch = new CountDownLatch(1);

        connect(server, token, new WebSocket.Listener() {
            @Override
            public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closedLatch.countDown();
                return null;
            }
            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closedLatch.countDown();
            }
        });
        awaitState(server, LinkServer.State.AWAITING_CONFIRMATION);
        server.confirm();
        awaitState(server, LinkServer.State.LINKED);

        server.close();

        assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "peer should observe the close");
        assertEquals(LinkServer.State.CLOSED, server.state());
    }
}
