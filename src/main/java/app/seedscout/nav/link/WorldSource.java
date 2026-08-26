package app.seedscout.nav.link;

import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.WorldSnapshot;

/**
 * The narrow window {@link LinkSession} is given into live Minecraft state, implemented by
 * the client facing layer so this package never imports Minecraft.
 *
 * <p>{@link #worldSnapshot()} is read exactly once, right after the player confirms pairing
 * (section 4.1: {@code world} is "sent once immediately after the player confirms pairing").
 * {@link #playerPosition()} is polled repeatedly for the {@code pos} cadence in
 * {@link app.seedscout.nav.protocol.PosThrottle}.
 *
 * <p>Both methods MUST be safe to call from a thread other than the client/render thread:
 * {@link LinkSession} calls them from its own scheduled sampling task, never from the game
 * thread. The implementation is responsible for whatever synchronization that requires
 * (typically reading a volatile snapshot the game thread publishes each tick).
 */
public interface WorldSource {

    /** The world to report at the moment the player confirmed. Never null. */
    WorldSnapshot worldSnapshot();

    /**
     * Where the player is right now, or {@code null} if that is momentarily unknown (for
     * example between world unload and load). A null result is simply skipped by the
     * sampler rather than sent.
     */
    PlayerPosition playerPosition();
}
