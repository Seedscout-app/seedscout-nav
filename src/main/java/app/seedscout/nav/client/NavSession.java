package app.seedscout.nav.client;

import app.seedscout.nav.link.LinkServer;
import app.seedscout.nav.protocol.UnlinkFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Owns the one {@link LinkServer} instance that can exist at a time, and the four teardown
 * triggers the task brief lists: the pairing window closing on its own, the player quitting
 * the confirm prompt, the player leaving the world, and client shutdown.
 * {@link SeedscoutNavClientHooks} wires the Fabric-side events into this; nothing in this class
 * touches a Fabric or Minecraft-lifecycle API directly, so it stays easy to reason about
 * independently of them.
 *
 * <p>"Leaving the world" means the play connection ending, NOT a client level change. A level
 * change is a nether portal as often as it is anything else, and it is now handled by
 * {@link #resendWorld()} rather than by teardown; see
 * {@link app.seedscout.nav.protocol.SaveIdentity}.
 */
public final class NavSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedscout-nav");

    public static final NavSession INSTANCE = new NavSession();

    private LinkServer server;

    private NavSession() {
    }

    /**
     * Opens a fresh pairing window, closing whatever was open before (there is only ever one
     * pairing window; a second key-press starts over rather than stacking). Returns the new
     * {@link LinkServer}, or empty if this machine has no LAN interface to pair over (see
     * {@link LinkServer#open}'s documented failure case).
     */
    public synchronized java.util.Optional<LinkServer> openPairing() {
        endSession(UnlinkFrame.PLAYER_ENDED);
        try {
            LinkServer opened = LinkServer.open(ClientNavState.INSTANCE, ClientNavState.INSTANCE);
            server = opened;
            return java.util.Optional.of(opened);
        } catch (IOException e) {
            // No genuine site-local LAN interface: see LinkServer's javadoc and the link
            // worker's integrator note. There is nothing left for a pairing screen to do.
            LOGGER.warn("Seedscout Nav pairing window could not open: no usable LAN interface");
            return java.util.Optional.empty();
        }
    }

    synchronized LinkServer current() {
        return server;
    }

    /**
     * Resends the {@code world} frame on the live link, because the loaded save changed under
     * it. No-op unless a session is actually linked, so the caller does not have to know.
     *
     * <p>This is NOT a teardown trigger and deliberately does not touch {@link #server}: the
     * whole point of {@link app.seedscout.nav.protocol.SaveIdentity} is that a world change is
     * now something the link survives rather than something that kills it.
     */
    public synchronized void resendWorld() {
        if (server != null) {
            server.resendWorld();
        }
    }

    /** Ends whatever session or pending pairing is open, for any of the four teardown triggers. */
    public synchronized void endSession(String reason) {
        if (server != null) {
            server.unlink(reason);
            server = null;
        }
    }
}
