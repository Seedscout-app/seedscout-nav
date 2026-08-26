package app.seedscout.nav.link;

import app.seedscout.nav.protocol.RouteFrame;

/**
 * Where a sanitized {@code route} or {@code clear} frame goes once {@link LinkSession} has
 * decoded it, implemented by the client facing layer.
 *
 * <p>This package hands over a fully checked {@link RouteFrame}: every bound in
 * {@code shared/nav_protocol.md} section 4.2 has already been enforced by
 * {@link app.seedscout.nav.protocol.RouteSanitizer}. What this interface's implementation
 * MUST still do, per the {@link RouteFrame} and {@link app.seedscout.nav.protocol.SafeLabel}
 * class documentation, because this package has no idea what dimension the player is
 * currently in or what route is currently drawn:
 *
 * <ul>
 *   <li>Call {@link RouteFrame#matchesDimension} against the player's current dimension and
 *       ignore a route that fails it (section 4.2's dimension rule).</li>
 *   <li>Call {@link RouteFrame#supersedes} against whatever is currently drawn and ignore a
 *       late arrival from an earlier plan (section 4.2's monotonic id rule).</li>
 *   <li>Render {@link app.seedscout.nav.protocol.SafeLabel#literalText()} as literal text
 *       only: never a translation key, never a text component, never fed to any
 *       command/NBT/selector parser.</li>
 * </ul>
 *
 * <p>Both methods are called from {@link LinkSession}'s own thread, never the game thread.
 * The implementation is responsible for handing off to the render/game thread if the game
 * engine requires that.
 */
public interface RenderSink {

    /** A sanitized {@code route} frame arrived and should replace whatever is drawn. */
    void showRoute(RouteFrame route);

    /** A {@code clear} frame arrived: remove the drawn route, if any. */
    void clearRoute();
}
