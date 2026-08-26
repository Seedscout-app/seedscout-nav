package app.seedscout.nav.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code pos} cadence, both ends of it.
 *
 * <p>The floor is the interesting half. Section 4.1 sets a 5 Hz ceiling and no floor at all,
 * and the app ages its live marker out after 2 seconds, so these tests pin the ERRATA that
 * closes that gap: a player who stands still must still have a fresh marker.
 */
class PosThrottleTest {

    private static final Instant NOON = Instant.parse("2026-08-26T12:00:00Z");
    private static final PlayerPosition STILL = new PlayerPosition(10, 64, 20, 90, "overworld");
    private static final PlayerPosition MOVED = new PlayerPosition(11, 64, 20, 90, "overworld");

    @Test
    @DisplayName("the first position always goes out")
    void firstSendIsImmediate() {
        assertTrue(new PosThrottle().offer(STILL, NOON));
    }

    @Test
    @DisplayName("section 4.1's ceiling: never more than 5 Hz, even when the player is sprinting")
    void ceilingIsFiveHertz() {
        PosThrottle throttle = new PosThrottle();
        assertTrue(throttle.offer(STILL, NOON));

        assertFalse(throttle.offer(MOVED, NOON.plusMillis(50)));
        assertFalse(throttle.offer(MOVED, NOON.plusMillis(199)));
        assertTrue(throttle.offer(MOVED, NOON.plusMillis(200)), "200 ms is exactly 5 Hz and is allowed");
    }

    @Test
    @DisplayName("ERRATA: a standing player still gets a pos at least once a second")
    void floorIsOneHertz() {
        PosThrottle throttle = new PosThrottle();
        assertTrue(throttle.offer(STILL, NOON));

        // Nothing changed, so nothing is sent for most of the second.
        assertFalse(throttle.offer(STILL, NOON.plusMillis(200)));
        assertFalse(throttle.offer(STILL, NOON.plusMillis(999)));

        // At one second the frame goes out anyway, which keeps the app's marker inside
        // its 2 second staleness window rather than letting it age out under a standing
        // player.
        assertTrue(throttle.offer(STILL, NOON.plusMillis(1000)));
        assertFalse(throttle.offer(STILL, NOON.plusMillis(1500)));
        assertTrue(throttle.offer(STILL, NOON.plusMillis(2000)));
    }

    @Test
    @DisplayName("the floor is comfortably inside the app's 2 second staleness window")
    void floorBeatsTheAppsStalenessWindow() {
        assertTrue(NavProtocol.POS_MAX_INTERVAL.compareTo(java.time.Duration.ofSeconds(2)) < 0,
                "a floor at or past the app's 2 second timeout would not fix anything");
        assertTrue(NavProtocol.POS_MIN_INTERVAL.compareTo(NavProtocol.POS_MAX_INTERVAL) < 0);
    }

    @Test
    @DisplayName("a yaw only change is a change: the app draws a heading arrow from it")
    void yawCountsAsMovement() {
        PosThrottle throttle = new PosThrottle();
        assertTrue(throttle.offer(STILL, NOON));

        PlayerPosition turned = new PlayerPosition(10, 64, 20, 180, "overworld");

        assertTrue(throttle.offer(turned, NOON.plusMillis(200)));
    }

    @Test
    @DisplayName("a dimension change is a change")
    void dimensionCountsAsMovement() {
        PosThrottle throttle = new PosThrottle();
        assertTrue(throttle.offer(STILL, NOON));

        PlayerPosition nether = new PlayerPosition(10, 64, 20, 90, "the_nether");

        assertTrue(throttle.offer(nether, NOON.plusMillis(200)));
    }

    @Test
    @DisplayName("a clock that jumps backwards does not flood the socket")
    void backwardsClock() {
        PosThrottle throttle = new PosThrottle();
        assertTrue(throttle.offer(STILL, NOON));

        assertFalse(throttle.offer(MOVED, NOON.minusSeconds(30)));
    }

    @Test
    @DisplayName("a null offer is refused rather than throwing on the game thread")
    void nullsAreRefused() {
        PosThrottle throttle = new PosThrottle();

        assertFalse(throttle.offer(null, NOON));
        assertFalse(throttle.offer(STILL, null));
    }
}
