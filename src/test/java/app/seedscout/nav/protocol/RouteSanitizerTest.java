package app.seedscout.nav.protocol;

import static app.seedscout.nav.protocol.NavCodecTest.assertDropped;
import static app.seedscout.nav.protocol.NavCodecTest.decodeRoute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every bound in {@link RouteSanitizer}, asserted one at a time.
 *
 * <p>These are the tests for the class that stands between a phone on the wifi and a render
 * surface inside the game, so each bound gets its own case rather than being folded into a
 * happy path assertion: a sanitizer that silently stopped enforcing one rule would still
 * pass a test that only checked a good route decodes.
 */
class RouteSanitizerTest {

    private static String route(String fields) {
        return "{\"type\":\"route\",\"id\":1,\"dimension\":\"overworld\"," + fields + "}";
    }

    private static String points(int count) {
        StringJoiner joiner = new StringJoiner(",", "\"points\":[", "]");
        for (int i = 0; i < count; i++) {
            joiner.add("[" + i + "," + i + "]");
        }
        return joiner.toString();
    }

    @Nested
    @DisplayName("points count, section 4.2: at least 2, at most 512")
    class PointCount {

        @ParameterizedTest(name = "{0} points is refused")
        @ValueSource(ints = {0, 1, 513, 1000})
        void countsOutsideTheBoundAreDropped(int count) {
            assertDropped(NavCodec.decode(route(points(count))), DropReason.POINT_COUNT_OUT_OF_RANGE);
        }

        @ParameterizedTest(name = "{0} points is accepted")
        @ValueSource(ints = {2, 3, 511, 512})
        void countsInsideTheBoundAreAccepted(int count) {
            assertEquals(count, decodeRoute(route(points(count))).points().size());
        }

        @Test
        @DisplayName("points that is not an array at all is dropped")
        void nonArrayPoints() {
            for (String bad : List.of("\"points\":7", "\"points\":\"0,0\"", "\"points\":{}",
                    "\"points\":null", "\"label\":\"x\"")) {
                assertDropped(NavCodec.decode(route(bad)), DropReason.BAD_POINTS_ARRAY);
            }
        }

        @Test
        @DisplayName("a point that is not an [x, z] pair is dropped")
        void malformedPairs() {
            for (String bad : List.of("[[0,0],[1]]", "[[0,0],[1,2,3]]", "[[0,0],7]",
                    "[[0,0],\"1,2\"]", "[[0,0],[[1],[2]]]", "[[0,0],{}]", "[[0,0],null]")) {
                assertDropped(NavCodec.decode(route("\"points\":" + bad)), DropReason.BAD_POINTS_ARRAY);
            }
        }
    }

    @Nested
    @DisplayName("coordinates: integers only, within the world border limit")
    class Coordinates {

        @Test
        @DisplayName("a fractional or exponent coordinate is dropped, never rounded")
        void fractionalCoordinatesAreDropped() {
            for (String bad : List.of("[[0.5,0],[1,1]]", "[[0,0],[1,1.0]]", "[[1e3,0],[1,1]]",
                    "[[0,0],[-0.0001,1]]")) {
                assertDropped(NavCodec.decode(route("\"points\":" + bad)), DropReason.POINT_NOT_INTEGER);
            }
        }

        @Test
        @DisplayName("a non numeric coordinate is dropped")
        void nonNumericCoordinates() {
            for (String bad : List.of("[[\"0\",0],[1,1]]", "[[0,0],[true,1]]", "[[null,0],[1,1]]")) {
                assertDropped(NavCodec.decode(route("\"points\":" + bad)), DropReason.BAD_POINTS_ARRAY);
            }
        }

        @ParameterizedTest(name = "coordinate {0} is refused")
        @ValueSource(strings = {
            "30000001", "-30000001", "2147483648", "-2147483649",
            "9223372036854775807", "-9223372036854775808",
        })
        void coordinatesOutsideTheWorldBorderAreDropped(String coordinate) {
            assertDropped(
                    NavCodec.decode(route("\"points\":[[0,0],[" + coordinate + ",0]]")),
                    DropReason.COORDINATE_OUT_OF_RANGE);
            assertDropped(
                    NavCodec.decode(route("\"points\":[[0,0],[0," + coordinate + "]]")),
                    DropReason.COORDINATE_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("the world border limit itself is inside the bound, not outside it")
        void theLimitItselfIsAccepted() {
            RouteFrame frame = decodeRoute(route(
                    "\"points\":[[-30000000,-30000000],[30000000,30000000]]"));

            assertEquals(
                    List.of(new RoutePoint(-30_000_000, -30_000_000), new RoutePoint(30_000_000, 30_000_000)),
                    frame.points());
        }

        @Test
        @DisplayName("an integer past a long cannot be laundered into a coordinate")
        void oversizedIntegerLiteralIsRefusedByTheReader() {
            // Json refuses an integral literal too big for a long rather than widening it
            // to a double, so this never even reaches the coordinate bound.
            assertDropped(
                    NavCodec.decode(route("\"points\":[[0,0],[99999999999999999999,0]]")),
                    DropReason.MALFORMED_JSON);
        }
    }

    @Nested
    @DisplayName("id and dimension")
    class IdAndDimension {

        @Test
        @DisplayName("a missing, negative or non integer id is dropped")
        void badIds() {
            for (String bad : List.of("{\"type\":\"route\",\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}",
                    "{\"type\":\"route\",\"id\":-1,\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}",
                    "{\"type\":\"route\",\"id\":1.5,\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}",
                    "{\"type\":\"route\",\"id\":\"7\",\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}",
                    "{\"type\":\"route\",\"id\":null,\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}")) {
                assertDropped(NavCodec.decode(bad), DropReason.BAD_ROUTE_ID);
            }
        }

        @Test
        @DisplayName("a missing, empty, oversized or non string dimension is dropped")
        void badDimensions() {
            String oversized = "\"" + "d".repeat(NavProtocol.MAX_DIMENSION_CHARS + 1) + "\"";
            for (String bad : List.of("", "\"\"", "7", "null", oversized)) {
                String frame = "{\"type\":\"route\",\"id\":1,"
                        + (bad.isEmpty() ? "" : "\"dimension\":" + bad + ",")
                        + "\"points\":[[0,0],[1,1]]}";
                assertDropped(NavCodec.decode(frame), DropReason.BAD_DIMENSION);
            }
        }

        @Test
        @DisplayName("section 4.2's id rules live on the frame, not in the renderer")
        void idAndDimensionRules() {
            RouteFrame first = decodeRoute(route(points(2)));
            RouteFrame second = decodeRoute(
                    "{\"type\":\"route\",\"id\":2,\"dimension\":\"overworld\",\"points\":[[0,0],[1,1]]}");

            assertTrue(second.supersedes(first), "a higher id replaces the drawn route");
            assertFalse(first.supersedes(second), "a late arrival from an earlier plan is ignored");
            assertTrue(first.supersedes(null), "any route replaces nothing");
            assertTrue(first.matchesDimension("overworld"));
            assertFalse(first.matchesDimension("the_nether"));
            assertFalse(first.matchesDimension(null));
        }
    }

    @Nested
    @DisplayName("label, the only untrusted string that reaches a render surface")
    class Label {

        @Test
        @DisplayName("section sign colour codes are stripped, code character and all")
        void sectionSignCodesAreStripped() {
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"§cRed §kobfuscated§r Town\",\"points\":[[0,0],[1,1]]"));

            assertEquals("Red obfuscated Town", frame.label().literalText());
        }

        @Test
        @DisplayName("a trailing lone section sign eats nothing that follows it")
        void loneSectionSign() {
            RouteFrame frame = decodeRoute(route("\"label\":\"Town§\",\"points\":[[0,0],[1,1]]"));

            assertEquals("Town", frame.label().literalText());
        }

        @Test
        @DisplayName("a section sign followed by a non code character keeps that character")
        void sectionSignBeforeAnOrdinaryCharacter() {
            RouteFrame frame = decodeRoute(route("\"label\":\"§zTown\",\"points\":[[0,0],[1,1]]"));

            assertEquals("zTown", frame.label().literalText());
        }

        @Test
        @DisplayName("control characters cannot break a single line HUD element")
        void controlCharactersAreStripped() {
            // A newline, a tab, a NUL and an ESC (the lead-in of a terminal escape
            // sequence, which matters wherever a label is echoed outside the game).
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"a\\nb\\tc\\u0000d\\u001bE\",\"points\":[[0,0],[1,1]]"));

            String literal = frame.label().literalText();
            assertEquals("abcdE", literal);
            for (int i = 0; i < literal.length(); i++) {
                assertFalse(Character.isISOControl(literal.charAt(i)),
                        "control character survived at index " + i);
            }
        }

        @Test
        @DisplayName("bidirectional override and zero width characters are stripped")
        void formatCharactersAreStripped() {
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"safe\\u202Egnp.exe\\u202C\\u200B\\u2066x\\u2069\",\"points\":[[0,0],[1,1]]"));

            assertEquals("safegnp.exex", frame.label().literalText());
        }

        @Test
        @DisplayName("the label is capped at 64 code points")
        void labelIsTruncated() {
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"" + "x".repeat(500) + "\",\"points\":[[0,0],[1,1]]"));

            assertEquals(NavProtocol.MAX_LABEL_CODE_POINTS, frame.label().literalText().length());
        }

        @Test
        @DisplayName("stripping happens BEFORE truncation, so padding cannot smuggle a longer label")
        void strippingBeforeTruncation() {
            // 64 formatting codes (128 chars of pure padding) then 64 visible characters.
            // A truncate-then-strip order would leave nothing visible at all; a
            // strip-then-truncate order keeps exactly the 64 visible characters.
            String padded = "§a".repeat(64) + "y".repeat(64);
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"" + padded + "\",\"points\":[[0,0],[1,1]]"));

            assertEquals("y".repeat(64), frame.label().literalText());
        }

        @Test
        @DisplayName("truncation counts code points, so it never splits a surrogate pair")
        void truncationNeverSplitsASurrogatePair() {
            // U+1F5FA, a supplementary code point: two chars each.
            String emoji = "🗺";
            RouteFrame frame = decodeRoute(route(
                    "\"label\":\"" + emoji.repeat(100) + "\",\"points\":[[0,0],[1,1]]"));

            String literal = frame.label().literalText();
            assertEquals(NavProtocol.MAX_LABEL_CODE_POINTS, literal.codePointCount(0, literal.length()));
            assertEquals(NavProtocol.MAX_LABEL_CODE_POINTS * 2, literal.length());
            assertFalse(Character.isHighSurrogate(literal.charAt(literal.length() - 1)),
                    "the last char must not be an unpaired high surrogate");
        }

        @Test
        @DisplayName("an absent or null label is not a violation, it is an empty label")
        void absentLabel() {
            assertSame(SafeLabel.EMPTY, decodeRoute(route(points(2))).label());
            assertSame(SafeLabel.EMPTY,
                    decodeRoute(route("\"label\":null," + points(2))).label());
            assertSame(SafeLabel.EMPTY,
                    decodeRoute(route("\"label\":\"§a§b\"," + points(2))).label(),
                    "a label made entirely of formatting codes reduces to the empty label");
        }

        @Test
        @DisplayName("a label of the wrong shape is dropped, not coerced")
        void wrongShapeLabel() {
            for (String bad : List.of("7", "true", "[]", "{}", "[\"x\"]")) {
                assertDropped(
                        NavCodec.decode(route("\"label\":" + bad + "," + points(2))),
                        DropReason.BAD_LABEL);
            }
        }

        @Test
        @DisplayName("SafeLabel.toString does not leak the label back into a log line")
        void toStringDoesNotCarryTheLabel() {
            SafeLabel label = SafeLabel.of("Woodland Mansion");

            assertFalse(label.toString().contains("Woodland"), label.toString());
            assertEquals("Woodland Mansion", label.literalText());
        }

        @Test
        @DisplayName("the label reaches the renderer only as a SafeLabel, never as a String")
        void routeFrameCarriesNoRawLabelString() {
            // The API property, not just the comment: RouteFrame.label() is typed
            // SafeLabel, whose only accessor is named literalText(). There is no
            // constructor or accessor on the record that takes or returns a raw label
            // String, so a render call site has nothing raw in scope to pass by accident.
            for (var accessor : RouteFrame.class.getRecordComponents()) {
                if (accessor.getName().equals("label")) {
                    assertEquals(SafeLabel.class, accessor.getType());
                }
            }
            assertNotEquals(String.class, RouteFrame.class.getRecordComponents()[2].getType());
        }
    }

    @Nested
    @DisplayName("frame level bounds, the ERRATA section 4.2 leaves open")
    class FrameBounds {

        @Test
        @DisplayName("a frame past 64 KiB is dropped before it is parsed")
        void oversizedFrameIsDropped() {
            String huge = route("\"label\":\"" + "x".repeat(NavProtocol.MAX_FRAME_BYTES) + "\","
                    + points(2));
            assertTrue(huge.getBytes(StandardCharsets.UTF_8).length > NavProtocol.MAX_FRAME_BYTES);

            assertDropped(NavCodec.decode(huge), DropReason.FRAME_TOO_LARGE);
        }

        @Test
        @DisplayName("the size bound is measured in UTF-8 bytes, not in chars")
        void sizeIsMeasuredInBytes() {
            // Each of these is 3 bytes in UTF-8 and one char in Java, so a char based
            // check would let a frame three times over the limit through.
            String multiByte = "世".repeat(NavProtocol.MAX_FRAME_BYTES / 3);
            String frame = route("\"label\":\"" + multiByte + "\"," + points(2));

            assertTrue(frame.length() < NavProtocol.MAX_FRAME_BYTES, "under the limit in chars");
            assertDropped(NavCodec.decode(frame), DropReason.FRAME_TOO_LARGE);
        }

        @Test
        @DisplayName("the largest legal route is far inside the size bound")
        void theLargestLegalRouteFits() {
            // The claim NavProtocol.MAX_FRAME_BYTES documents: 512 points of two nine
            // digit coordinates plus a 64 character label cannot be rejected for size.
            StringJoiner joiner = new StringJoiner(",", "\"points\":[", "]");
            for (int i = 0; i < NavProtocol.MAX_ROUTE_POINTS; i++) {
                joiner.add("[-29999999,29999999]");
            }
            String frame = route("\"label\":\"" + "x".repeat(64) + "\"," + joiner);

            assertTrue(frame.getBytes(StandardCharsets.UTF_8).length < NavProtocol.MAX_FRAME_BYTES,
                    "largest legal route was " + frame.length() + " bytes");
            assertEquals(NavProtocol.MAX_ROUTE_POINTS, decodeRoute(frame).points().size());
        }

        @Test
        @DisplayName("JSON nesting past the depth bound is dropped, never recursed into")
        void nestingIsBounded() {
            String deep = "[".repeat(64) + "]".repeat(64);

            assertDropped(NavCodec.decode(deep), DropReason.MALFORMED_JSON);
            assertDropped(NavCodec.decode(route("\"points\":" + deep)), DropReason.MALFORMED_JSON);
        }

        @Test
        @DisplayName("a nest deep enough to blow the stack is still just a dropped frame")
        void aStackBustingNestDoesNotThrow() {
            // Sized to sit just under the frame bound, so this exercises the DEPTH guard
            // rather than being caught earlier by the size guard. Without a depth guard,
            // a recursive descent reader would recurse 60,000 frames deep here and take
            // the network thread down with a StackOverflowError.
            String pathological = "[".repeat(NavProtocol.MAX_FRAME_BYTES - 1);

            assertDropped(NavCodec.decode(pathological), DropReason.MALFORMED_JSON);

            // Past the size bound, the cheaper guard wins and the reader is never entered.
            assertDropped(NavCodec.decode("[".repeat(200_000)), DropReason.FRAME_TOO_LARGE);
        }

        @Test
        @DisplayName("a non object frame is dropped")
        void nonObjectFramesAreDropped() {
            for (String raw : List.of("[]", "7", "\"route\"", "true", "null", "[[0,0],[1,1]]")) {
                assertDropped(NavCodec.decode(raw), DropReason.NOT_AN_OBJECT);
            }
        }
    }
}
