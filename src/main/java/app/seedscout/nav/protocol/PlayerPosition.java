package app.seedscout.nav.protocol;

/**
 * Section 4.1's {@code pos} frame: where the player is right now.
 *
 * <p>Cadence is governed by {@link PosThrottle}, which enforces both the protocol's
 * stated 5 Hz ceiling and the 1 Hz floor this implementation adds (see
 * {@link NavProtocol#POS_MAX_INTERVAL} for why the floor is not optional).
 */
public record PlayerPosition(
        double x,
        double y,
        double z,
        double yaw,
        String dimension) implements OutboundFrame {

    public PlayerPosition {
        if (dimension == null || dimension.isEmpty()) {
            throw new IllegalArgumentException("dimension must not be empty");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
    }

    @Override
    public String type() {
        return "pos";
    }

    /** True when nothing the app can see has changed since {@code other}. */
    public boolean samePlaceAs(PlayerPosition other) {
        return other != null
                && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0
                && Double.compare(yaw, other.yaw) == 0
                && dimension.contentEquals(other.dimension);
    }

    private static void requireFinite(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
