package app.seedscout.nav.link;

import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.WorldSnapshot;

/**
 * The narrow window {@link LinkSession} is given into live Minecraft state, implemented by
 * the client facing layer so this package never imports Minecraft.
 *
 * <p>{@link #worldSnapshot()} is read right after the player confirms pairing, and again on the
 * rare occasion the loaded save changes under a live link (see
 * {@link app.seedscout.nav.protocol.SaveIdentity}, and note that a dimension change is NOT such
 * an occasion). It is not read on any other schedule. {@link #playerPosition()} is polled
 * repeatedly for the {@code pos} cadence in {@link app.seedscout.nav.protocol.PosThrottle}.
 *
 * <p>Both methods MUST be safe to call from a thread other than the client/render thread:
 * {@link LinkSession} calls them from its own scheduled sampling task, never from the game
 * thread. The implementation is responsible for whatever synchronization that requires
 * (typically reading a volatile snapshot the game thread publishes each tick).
 */
public interface WorldSource {

    /** The world to report as of the moment this is called. Never null. */
    WorldSnapshot worldSnapshot();

    /**
     * Where the player is right now, or {@code null} if that is momentarily unknown (for
     * example between world unload and load). A null result is simply skipped by the
     * sampler rather than sent.
     */
    PlayerPosition playerPosition();
}
