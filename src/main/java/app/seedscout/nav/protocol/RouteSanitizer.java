package app.seedscout.nav.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The entire untrusted input path, in one class.
 *
 * <p>Everything arriving from the phone is untrusted, including after pairing succeeded: a
 * paired peer is a device that scanned a QR code, not a device that is guaranteed to be
 * running the Seedscout app. So this class assumes the sender is hostile and enforces every
 * bound before a value can reach a {@link RouteFrame}, which is why holding a
 * {@code RouteFrame} is a promise that nothing downstream needs to re-check.
 *
 * <p>The checks, and where each comes from:
 *
 * <ol>
 *   <li><b>Frame size</b>, {@link NavProtocol#MAX_FRAME_BYTES} UTF-8 bytes. ERRATA: section
 *       4.2 states no size bound. Checked BEFORE parsing, so a huge frame costs a length
 *       check rather than a parse.</li>
 *   <li><b>Strict JSON</b> via {@link Json}: bounded nesting depth (ERRATA, section 4
 *       states none), no duplicate keys, integral literals stay integral.</li>
 *   <li><b>Object only</b>. Section 4: "every frame is a JSON object". An array, a bare
 *       string or a bare number is dropped.</li>
 *   <li><b>Type allowlist</b> of exactly {@code route} and {@code clear} (section 1 rule 2).
 *       Anything else is IGNORED rather than treated as an error (section 4), so a newer
 *       app can talk to an older mod.</li>
 *   <li><b>route bounds</b>: 2 to 512 points (section 4.2), integer coordinates only, every
 *       coordinate within plus or minus {@link NavProtocol#MAX_COORDINATE} (ERRATA,
 *       section 4.2 states no coordinate bound), and a label reduced to a
 *       {@link SafeLabel}.</li>
 * </ol>
 *
 * <p>The label deserves its own note. It is the only attacker controlled string that
 * reaches a render surface, so it is never carried as a raw {@link String} past this class:
 * {@link SafeLabel} strips section sign colour codes, control characters and Unicode format
 * characters, then truncates, and its single accessor is named
 * {@link SafeLabel#literalText()} because that is the contract. Literal text, never a text
 * component, never a translation key.
 */
public final class RouteSanitizer {

    private RouteSanitizer() {
    }

    /**
     * Sanitizes one raw inbound text frame. Never throws, for any input, including null,
     * lone surrogates and deeply nested brackets.
     */
    public static Inbound sanitize(String rawFrame) {
        if (rawFrame == null) {
            return Inbound.drop(DropReason.MALFORMED_JSON);
        }
        if (rawFrame.getBytes(StandardCharsets.UTF_8).length > NavProtocol.MAX_FRAME_BYTES) {
            return Inbound.drop(DropReason.FRAME_TOO_LARGE);
        }

        Object parsed;
        try {
            parsed = Json.parse(rawFrame);
        } catch (Json.SyntaxException refused) {
            // Includes exceeding MAX_JSON_DEPTH and any duplicate key.
            return Inbound.drop(DropReason.MALFORMED_JSON);
        } catch (RuntimeException unexpected) {
            // Belt and braces. This seam runs on a network thread and must never let a
            // hostile frame escape as a stack unwind, so even a reader bug degrades to a
            // dropped frame rather than a crash.
            return Inbound.drop(DropReason.MALFORMED_JSON);
        }

        if (!(parsed instanceof Map<?, ?> object)) {
            return Inbound.drop(DropReason.NOT_AN_OBJECT);
        }
        return sanitizeObject(object);
    }

    /** The type dispatch and per-message checks, split out so a test can drive it directly. */
    static Inbound sanitizeObject(Map<?, ?> object) {
        Object type = object.get("type");
        if (!(type instanceof String typeName)) {
            return Inbound.drop(DropReason.UNKNOWN_TYPE);
        }
        return switch (typeName) {
            case "route" -> sanitizeRoute(object);
            case "clear" -> Inbound.accept(ClearFrame.INSTANCE);
            // Section 4: unknown types are ignored, not errors. That includes the mod to
            // app types (world, pos, unlink): they are not part of the app to mod
            // allowlist, so arriving here they are simply unknown.
            default -> Inbound.drop(DropReason.UNKNOWN_TYPE);
        };
    }

    private static Inbound sanitizeRoute(Map<?, ?> object) {
        Object rawId = object.get("id");
        if (!(rawId instanceof Long id) || id < 0) {
            // Long, never Double: Json keeps integral literals integral precisely so an id
            // (and a coordinate) cannot arrive pre-mangled by a double round trip.
            return Inbound.drop(DropReason.BAD_ROUTE_ID);
        }

        Object rawDimension = object.get("dimension");
        if (!(rawDimension instanceof String dimension)
                || dimension.isEmpty()
                || dimension.length() > NavProtocol.MAX_DIMENSION_CHARS) {
            return Inbound.drop(DropReason.BAD_DIMENSION);
        }

        Object rawLabel = object.get("label");
        SafeLabel label;
        if (rawLabel == null || rawLabel == Json.NULL) {
            // Absent or explicitly null is not a violation: a route with no label draws
            // fine. Only a label of the WRONG SHAPE (a number, an object, an array) is a
            // drop, because that is a sender that does not understand the field.
            label = SafeLabel.EMPTY;
        } else if (rawLabel instanceof String labelText) {
            label = SafeLabel.of(labelText);
        } else {
            return Inbound.drop(DropReason.BAD_LABEL);
        }

        Object rawPoints = object.get("points");
        if (!(rawPoints instanceof List<?> points)) {
            return Inbound.drop(DropReason.BAD_POINTS_ARRAY);
        }
        if (points.size() < NavProtocol.MIN_ROUTE_POINTS
                || points.size() > NavProtocol.MAX_ROUTE_POINTS) {
            // Counted before any element is inspected, so an oversized array is refused
            // without walking it.
            return Inbound.drop(DropReason.POINT_COUNT_OUT_OF_RANGE);
        }

        List<RoutePoint> waypoints = new ArrayList<>(points.size());
        for (Object rawPoint : points) {
            if (!(rawPoint instanceof List<?> pair) || pair.size() != 2) {
                return Inbound.drop(DropReason.BAD_POINTS_ARRAY);
            }
            Long x = coordinate(pair.get(0));
            Long z = coordinate(pair.get(1));
            if (x == null || z == null) {
                return coordinateDrop(pair.get(0), pair.get(1));
            }
            // Explicit two sided comparison, NOT Math.abs. Math.abs(Long.MIN_VALUE) is
            // itself negative (there is no positive long that large), so an abs based
            // check silently ADMITS the single most extreme coordinate a frame can carry,
            // which then truncates to 0 in intValue(). A test caught exactly that.
            if (x < -NavProtocol.MAX_COORDINATE || x > NavProtocol.MAX_COORDINATE
                    || z < -NavProtocol.MAX_COORDINATE || z > NavProtocol.MAX_COORDINATE) {
                return Inbound.drop(DropReason.COORDINATE_OUT_OF_RANGE);
            }
            waypoints.add(new RoutePoint(x.intValue(), z.intValue()));
        }

        return Inbound.accept(new RouteFrame(id, dimension, label, List.copyOf(waypoints)));
    }

    private static Long coordinate(Object value) {
        return value instanceof Long integral ? integral : null;
    }

    /**
     * Section 1 rule 5 makes waypoints horizontal block coordinates, so a fraction or an
     * exponent is a different failure from a coordinate that is not a number at all, and
     * the two are named separately for the mod's own drop counter.
     */
    private static Inbound coordinateDrop(Object x, Object z) {
        if (x instanceof Double || z instanceof Double) {
            return Inbound.drop(DropReason.POINT_NOT_INTEGER);
        }
        return Inbound.drop(DropReason.BAD_POINTS_ARRAY);
    }
}
