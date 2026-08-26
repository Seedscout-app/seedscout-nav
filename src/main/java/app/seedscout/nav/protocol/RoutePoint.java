package app.seedscout.nav.protocol;

/**
 * One {@code [x, z]} waypoint from a {@code route} frame, in block coordinates.
 *
 * <p>Horizontal only: section 1 rule 5 puts vertical entirely on the mod side, because
 * the app's offline heightmap is about 3 blocks off and the mod can snap to real ground
 * in loaded chunks. A y value on the wire would be a value the mod is contractually
 * obliged to ignore, so there is no field for one.
 *
 * <p>Both components are already range checked against {@link NavProtocol#MAX_COORDINATE}
 * by {@link RouteSanitizer} before a point can exist inside a {@link RouteFrame}. The
 * constructor re-checks so a hand built point cannot bypass that.
 */
public record RoutePoint(int x, int z) {

    public RoutePoint {
        // Two sided comparison rather than Math.abs, for the same reason RouteSanitizer
        // uses one: Math.abs(Integer.MIN_VALUE) is negative, so an abs based bound admits
        // the one value most likely to overflow whatever renders it.
        if (x < -NavProtocol.MAX_COORDINATE || x > NavProtocol.MAX_COORDINATE
                || z < -NavProtocol.MAX_COORDINATE || z > NavProtocol.MAX_COORDINATE) {
            throw new IllegalArgumentException("coordinate outside the world border limit");
        }
    }
}
