package app.seedscout.nav.protocol;

import java.time.Duration;
import java.time.Instant;

/**
 * The cadence rule for section 4.1's {@code pos} frame, and the ERRATA that makes it
 * actually work.
 *
 * <p>Section 4.1 says {@code pos} is "throttled to at most 5 Hz" and stops there. It sets a
 * MAXIMUM rate and no MINIMUM, and that gap is a real bug rather than a pedantic one: the
 * app ages its live marker out after 2 seconds (see {@code LivePosition.receivedAt} and the
 * staleness note in {@code app/lib/data/nav_link_service.dart}), so a perfectly conformant
 * mod that only sent {@code pos} when the player moved would leave a standing player's
 * marker permanently stale. Standing still is the normal state of a player reading a map.
 *
 * <p>So this throttle enforces both ends:
 *
 * <ul>
 *   <li>never more often than {@link NavProtocol#POS_MIN_INTERVAL} (the protocol's 5 Hz
 *       ceiling), and</li>
 *   <li>at least once every {@link NavProtocol#POS_MAX_INTERVAL} even when nothing changed,
 *       comfortably inside the app's 2 second staleness window.</li>
 * </ul>
 *
 * <p>This is mod side only and needs no app change: the app already accepts an unchanged
 * position, it simply resets its staleness clock.
 *
 * <p>Not thread safe. One instance belongs to one link session on one thread.
 */
public final class PosThrottle {

    private PlayerPosition lastSent;
    private Instant lastSentAt;

    /**
     * Decides whether {@code candidate} goes on the wire now, and records the send when the
     * answer is yes.
     *
     * @return true when the caller should encode and send {@code candidate}.
     */
    public boolean offer(PlayerPosition candidate, Instant now) {
        if (candidate == null || now == null) {
            return false;
        }
        if (lastSent == null) {
            return send(candidate, now);
        }
        Duration since = Duration.between(lastSentAt, now);
        if (since.isNegative()) {
            // A clock that went backwards is not a reason to flood the socket.
            return false;
        }
        if (since.compareTo(NavProtocol.POS_MIN_INTERVAL) < 0) {
            // The 5 Hz ceiling, and the only rule that can hold back a frame that carries
            // a genuinely new position.
            return false;
        }
        if (!candidate.samePlaceAs(lastSent)) {
            return send(candidate, now);
        }
        // Unchanged position: the ERRATA floor is the only thing that lets it through.
        if (since.compareTo(NavProtocol.POS_MAX_INTERVAL) >= 0) {
            return send(candidate, now);
        }
        return false;
    }

    /** The last position actually put on the wire, or null before the first send. */
    public PlayerPosition lastSent() {
        return lastSent;
    }

    private boolean send(PlayerPosition candidate, Instant now) {
        lastSent = candidate;
        lastSentAt = now;
        return true;
    }
}
