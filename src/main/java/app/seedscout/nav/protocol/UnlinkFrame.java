package app.seedscout.nav.protocol;

/**
 * Section 4.1's {@code unlink} frame, sent when the player ends the session or changes
 * world. The reason is a short machine readable token the app surfaces as copy; it is
 * never parsed for behaviour on either side.
 */
public record UnlinkFrame(String reason) implements OutboundFrame {

    /** The player left the world the link was established in (section 4.1's example). */
    public static final String PLAYER_LEFT_WORLD = "player_left_world";

    /** The player ended the link from the in-game screen. */
    public static final String PLAYER_ENDED = "player_ended";

    /** The link went quiet past {@link NavProtocol#LINK_IDLE_TIMEOUT}. */
    public static final String IDLE_TIMEOUT = "idle_timeout";

    /** The game is shutting down. */
    public static final String CLIENT_SHUTDOWN = "client_shutdown";

    public UnlinkFrame {
        if (reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be empty");
        }
    }

    @Override
    public String type() {
        return "unlink";
    }
}
