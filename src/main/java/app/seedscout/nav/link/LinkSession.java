package app.seedscout.nav.link;

import app.seedscout.nav.protocol.ClearFrame;
import app.seedscout.nav.protocol.Inbound;
import app.seedscout.nav.protocol.NavCodec;
import app.seedscout.nav.protocol.NavProtocol;
import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.PosThrottle;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.UnlinkFrame;
import app.seedscout.nav.protocol.WorldSnapshot;

import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A single linked session (section 4): sends {@code world} on confirm, then {@code pos} on the
 * throttle from {@link PosThrottle}, and applies whatever {@code route} or {@code clear}
 * frames arrive to a {@link RenderSink}.
 *
 * <p>{@code world} is sent once per LOADED SAVE, not once per session. It is resent by
 * {@link #resendWorld()} if the save changes under a live link, which is what keeps a nether
 * portal (same save, new dimension, no resend) from being confused with a hot-swap to a
 * different save. See {@link app.seedscout.nav.protocol.SaveIdentity}.
 *
 * <p>Free of every Minecraft rendering call, as required: it reads game state only through
 * {@link WorldSource} and hands decoded frames only to {@link RenderSink}, both implemented
 * by the client facing layer.
 *
 * <p>Not reused across sessions. One instance is created by {@link LinkServer} the moment
 * the player confirms pairing, and is discarded when the session ends.
 */
final class LinkSession {

    /** How often the position sampler wakes up. Well under the 5&nbsp;Hz ceiling so the
     * ceiling, not the sampler, is what limits the outbound rate; comfortably under the
     * 1&nbsp;second floor too, so a stationary player's refresh never drifts close to the
     * app's 2 second staleness window. */
    private static final long SAMPLE_INTERVAL_MILLIS = 100;

    private final WebSocketConnection connection;
    private final WorldSource worldSource;
    private final RenderSink renderSink;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Runnable onEnded;

    private final PosThrottle throttle = new PosThrottle();
    private volatile ScheduledFuture<?> sampleTask;
    private volatile boolean ended;

    LinkSession(
            WebSocketConnection connection,
            WorldSource worldSource,
            RenderSink renderSink,
            ScheduledExecutorService scheduler,
            Clock clock,
            Runnable onEnded) {
        this.connection = connection;
        this.worldSource = worldSource;
        this.renderSink = renderSink;
        this.scheduler = scheduler;
        this.clock = clock;
        this.onEnded = onEnded;
    }

    /** Sends {@code world} and begins sampling position. Call exactly once, after confirm. */
    void start() {
        connection.startReading(new WebSocketConnection.Listener() {
            @Override
            public void onText(String text) {
                handleInbound(text);
            }

            @Override
            public void onClosed() {
                end();
            }
        });
        WorldSnapshot world = worldSource.worldSnapshot();
        connection.sendText(NavCodec.encode(world));
        sampleTask = scheduler.scheduleAtFixedRate(
                this::samplePosition, 0, SAMPLE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void samplePosition() {
        if (ended) {
            return;
        }
        PlayerPosition position = worldSource.playerPosition();
        if (position == null) {
            return;
        }
        if (throttle.offer(position, clock.instant())) {
            connection.sendText(NavCodec.encode(position));
        }
    }

    private void handleInbound(String rawFrame) {
        Inbound result = NavCodec.decode(rawFrame);
        if (!(result instanceof Inbound.Accepted accepted)) {
            // Section 4: an unrecognised or invalid frame is ignored, not an error. Which
            // reason, if any, stays inside RouteSanitizer/DropReason; this layer has
            // nothing further to do with it.
            return;
        }
        switch (accepted.frame()) {
            case RouteFrame route -> renderSink.showRoute(route);
            case ClearFrame clear -> renderSink.clearRoute();
        }
    }

    /**
     * Sends a fresh {@code world} frame mid-session, because the loaded save changed under the
     * link. See {@link app.seedscout.nav.protocol.SaveIdentity} for when this is and is not
     * called; the short version is never for a dimension change, only for a genuinely different
     * save or a save this client could not identify.
     *
     * <p>Safe for the app to receive: {@code app/lib/data/nav_link_service.dart} documents a
     * second {@code world} while linked as overwriting the stored one, which is what makes this
     * a resend rather than a protocol violation.
     */
    void resendWorld() {
        if (ended) {
            return;
        }
        connection.sendText(NavCodec.encode(worldSource.worldSnapshot()));
    }

    /** Sends {@code unlink} with {@code reason} and closes the socket. Idempotent. */
    void sendUnlinkAndClose(String reason) {
        if (ended) {
            return;
        }
        try {
            connection.sendText(NavCodec.encode(new UnlinkFrame(reason)));
        } catch (RuntimeException ignored) {
            // A dead socket cannot be told it is dead; proceed straight to closing it.
        }
        connection.close();
        end();
    }

    /** Sends a ping to keep the link's idle clock honest (section 3's ERRATA timers). */
    void sendPing() {
        if (!ended) {
            connection.sendPing();
        }
    }

    long idleMillis() {
        return connection.idleMillis();
    }

    private void end() {
        if (ended) {
            return;
        }
        ended = true;
        ScheduledFuture<?> task = sampleTask;
        if (task != null) {
            task.cancel(false);
        }
        onEnded.run();
    }
}
