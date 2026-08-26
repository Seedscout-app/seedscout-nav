package app.seedscout.nav.protocol;

/**
 * The wire codec for {@code shared/nav_protocol.md} sections 4.1 and 4.2.
 *
 * <p>Two directions, two very different postures:
 *
 * <ul>
 *   <li>{@link #encode(OutboundFrame)} serialises frames this mod authored. The inputs are
 *       already typed and already validated by their own record constructors, so encoding
 *       is total: every legal {@link OutboundFrame} has exactly one wire form.</li>
 *   <li>{@link #decode(String)} reads bytes a phone sent. It never throws, for any input,
 *       and it never returns a partially checked frame: see {@link RouteSanitizer}, which
 *       owns the whole untrusted path.</li>
 * </ul>
 *
 * <p>This class has ZERO Minecraft imports, like the rest of this package. The
 * {@code world.mc} value it writes is the exact {@code SharedConstants} version name,
 * passed in by the Minecraft facing layer rather than read here.
 */
public final class NavCodec {

    private NavCodec() {
    }

    /**
     * Encodes a mod to app frame (section 4.1).
     *
     * <p>{@code world.seed} goes out through {@link JsonWriter#seed}, which accepts only
     * the DECIMAL STRING form. There is deliberately no path in this class that can put a
     * numeric seed on the wire: a seed past 2^53 read back as a JSON number would be
     * silently corrupted, and the app treats a numeric seed as a protocol violation
     * (see {@code NavLinkService._handleWorld}), so emitting one is a broken mod rather
     * than a degraded one.
     */
    public static String encode(OutboundFrame frame) {
        return switch (frame) {
            case WorldSnapshot world -> new JsonWriter()
                    .string("type", world.type())
                    .string("edition", world.edition())
                    .string("mc", world.mcVersion())
                    .string("dimension", world.dimension())
                    .seed("seed", world.seed())
                    .integer("spawnX", world.spawnX())
                    .integer("spawnZ", world.spawnZ())
                    .end();
            case PlayerPosition pos -> new JsonWriter()
                    .string("type", pos.type())
                    .number("x", pos.x())
                    .number("y", pos.y())
                    .number("z", pos.z())
                    .number("yaw", pos.yaw())
                    .string("dimension", pos.dimension())
                    .end();
            case UnlinkFrame unlink -> new JsonWriter()
                    .string("type", unlink.type())
                    .string("reason", unlink.reason())
                    .end();
        };
    }

    /**
     * Encodes an app to mod frame (section 4.2). The mod never sends one of these in
     * production, since section 1 rule 3 makes the app the only client: this exists so a
     * test can round trip a {@link RouteFrame} through the exact bytes the app would have
     * put on the wire, rather than asserting the decoder against a string a human typed.
     */
    public static String encode(InboundFrame frame) {
        return switch (frame) {
            case RouteFrame route -> new JsonWriter()
                    .string("type", route.type())
                    .integer("id", route.id())
                    .string("dimension", route.dimension())
                    .string("label", route.label().literalText())
                    .points("points", route.points())
                    .end();
            case ClearFrame clear -> new JsonWriter()
                    .string("type", clear.type())
                    .end();
        };
    }

    /**
     * Reads one inbound text frame. Never throws, for any input, including null.
     *
     * @return {@link Inbound.Accepted} holding a fully checked frame, or
     *         {@link Inbound.Dropped} with the reason (which stays inside the mod, see
     *         {@link DropReason}).
     */
    public static Inbound decode(String rawFrame) {
        return RouteSanitizer.sanitize(rawFrame);
    }
}
