package app.seedscout.nav.protocol;

import java.util.List;

/**
 * Section 4.2's {@code route} frame, after {@link RouteSanitizer} has finished with it.
 *
 * <p>An instance of this record is by construction a route that passed every bound:
 * 2 to 512 points, every coordinate an integer within the world border limit, and a
 * {@link SafeLabel} rather than a raw string. Nothing downstream needs to re-check, and
 * nothing downstream is holding an unchecked value.
 *
 * <p>{@code id} is monotonic per session: section 4.2 says the mod draws the highest id
 * it has seen and ignores late arrivals from an earlier plan. {@link #supersedes} is that
 * rule, kept here next to the field it governs rather than reimplemented in the renderer.
 *
 * <p>{@code dimension} is NOT checked against the player's current dimension here,
 * because this package has no idea what dimension the player is in. Section 4.2 requires
 * that check ("the mod MUST ignore a route whose dimension is not the one the player is
 * currently in, rather than drawing it in the wrong world"), and it is the Minecraft
 * facing layer's job, using {@link #matchesDimension}.
 */
public record RouteFrame(
        long id,
        String dimension,
        SafeLabel label,
        List<RoutePoint> points) implements InboundFrame {

    public RouteFrame {
        if (dimension == null || dimension.isEmpty()) {
            throw new IllegalArgumentException("dimension must not be empty");
        }
        if (label == null) {
            throw new IllegalArgumentException("label must not be null");
        }
        if (points == null
                || points.size() < NavProtocol.MIN_ROUTE_POINTS
                || points.size() > NavProtocol.MAX_ROUTE_POINTS) {
            throw new IllegalArgumentException("points must hold between 2 and 512 entries");
        }
        points = List.copyOf(points);
    }

    @Override
    public String type() {
        return "route";
    }

    /** Section 4.2's id rule: true when this route replaces one already drawn. */
    public boolean supersedes(RouteFrame currentlyDrawn) {
        return currentlyDrawn == null || id > currentlyDrawn.id;
    }

    /** Section 4.2's dimension rule: true when this route belongs in {@code playerDimension}. */
    public boolean matchesDimension(String playerDimension) {
        return dimension.contentEquals(playerDimension == null ? "" : playerDimension);
    }
}
