package app.seedscout.nav.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Wire level tests for {@link NavCodec} against shared/nav_protocol.md sections 4.1 and
 * 4.2, cross checked against the app side decoder in
 * {@code app/lib/data/nav_link_service.dart}.
 */
class NavCodecTest {

    private static final String OVERWORLD = "overworld";

    @Nested
    @DisplayName("section 4.1, mod to app")
    class ModToApp {

        @Test
        @DisplayName("world encodes exactly the shape section 4.1 documents")
        void worldMatchesTheSpecExample() {
            WorldSnapshot world = new WorldSnapshot(
                    WorldSnapshot.EDITION_JAVA,
                    "1.21.11",
                    OVERWORLD,
                    "-4172144997902289642",
                    112,
                    -208);

            assertEquals(
                    "{\"type\":\"world\",\"edition\":\"java\",\"mc\":\"1.21.11\","
                            + "\"dimension\":\"overworld\",\"seed\":\"-4172144997902289642\","
                            + "\"spawnX\":112,\"spawnZ\":-208}",
                    NavCodec.encode(world));
        }

        @Test
        @DisplayName("world with an unknown seed encodes JSON null, not the string 'null'")
        void worldWithNullSeed() {
            WorldSnapshot world = new WorldSnapshot(
                    WorldSnapshot.EDITION_JAVA, "26.2", OVERWORLD, null, 0, 0);

            String encoded = NavCodec.encode(world);

            assertTrue(encoded.contains("\"seed\":null"), encoded);
            assertFalse(encoded.contains("\"seed\":\"null\""), encoded);
        }

        @Test
        @DisplayName("pos encodes exactly the shape section 4.1 documents")
        void posMatchesTheSpecExample() {
            PlayerPosition pos = new PlayerPosition(134.5, 71.0, -902.25, 47.5, OVERWORLD);

            assertEquals(
                    "{\"type\":\"pos\",\"x\":134.5,\"y\":71.0,\"z\":-902.25,"
                            + "\"yaw\":47.5,\"dimension\":\"overworld\"}",
                    NavCodec.encode(pos));
        }

        @Test
        @DisplayName("unlink encodes exactly the shape section 4.1 documents")
        void unlinkMatchesTheSpecExample() {
            assertEquals(
                    "{\"type\":\"unlink\",\"reason\":\"player_left_world\"}",
                    NavCodec.encode(new UnlinkFrame(UnlinkFrame.PLAYER_LEFT_WORLD)));
        }

        @Test
        @DisplayName("every outbound frame the app parses survives a JSON round trip")
        void outboundFramesReparse() {
            List<OutboundFrame> frames = List.of(
                    new WorldSnapshot(WorldSnapshot.EDITION_JAVA, "26.2", OVERWORLD, "42", 1, 2),
                    new PlayerPosition(1.5, 2.5, 3.5, 4.5, OVERWORLD),
                    new UnlinkFrame(UnlinkFrame.IDLE_TIMEOUT));

            for (OutboundFrame frame : frames) {
                Object reparsed = Json.parse(NavCodec.encode(frame));
                assertInstanceOf(java.util.Map.class, reparsed);
                assertEquals(frame.type(), ((java.util.Map<?, ?>) reparsed).get("type"));
            }
        }

        @Test
        @DisplayName("a label with a quote and a backslash cannot break out of its JSON string")
        void encoderEscapesStringContent() {
            WorldSnapshot world = new WorldSnapshot(
                    WorldSnapshot.EDITION_JAVA, "a\"b\\c", OVERWORLD, null, 0, 0);

            Object reparsed = Json.parse(NavCodec.encode(world));

            assertEquals("a\"b\\c", ((java.util.Map<?, ?>) reparsed).get("mc"));
        }
    }

    @Nested
    @DisplayName("world.seed is a decimal string at every layer")
    class SeedIsAlwaysAString {

        /**
         * The whole point of the decimal string rule: every one of these is past 2^53 and
         * would come back subtly wrong from any parser that reads a bare JSON number as a
         * double. The encoder must never turn one into a JSON number, and the value must
         * survive byte for byte.
         */
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(strings = {
            "9007199254740993",             // 2^53 + 1, the first integer a double cannot hold
            "-9007199254740993",
            "9223372036854775807",          // Long.MAX_VALUE
            "-9223372036854775808",         // Long.MIN_VALUE
            "-4172144997902289642",         // the spec's own example
            "1234567890123456789",
            "0",
            "-1",
        })
        void seedSurvivesAsAnExactDecimalString(String seed) {
            WorldSnapshot world = new WorldSnapshot(
                    WorldSnapshot.EDITION_JAVA, "26.2", OVERWORLD, seed, 0, 0);

            String encoded = NavCodec.encode(world);

            // Quoted on the wire, so the app's jsonDecode hands back a Dart String.
            assertTrue(encoded.contains("\"seed\":\"" + seed + "\""), encoded);

            Object reparsed = Json.parse(encoded);
            Object decodedSeed = ((java.util.Map<?, ?>) reparsed).get("seed");
            assertInstanceOf(String.class, decodedSeed, "seed must decode as a string, never a number");
            assertEquals(seed, decodedSeed);
        }

        @Test
        @DisplayName("seedString is the only bridge from a numeric seed, and it is exact")
        void seedStringIsExactForExtremeValues() {
            assertEquals("9223372036854775807", WorldSnapshot.seedString(Long.MAX_VALUE));
            assertEquals("-9223372036854775808", WorldSnapshot.seedString(Long.MIN_VALUE));
            assertEquals("9007199254740993", WorldSnapshot.seedString(9007199254740993L));

            // The value a double would have corrupted it to, proving the two differ.
            assertEquals(9007199254740992L, (long) (double) 9007199254740993L);
        }

        @Test
        @DisplayName("a seed that is not a decimal integer string is refused at construction")
        void nonDecimalSeedIsRejected() {
            for (String bad : List.of("", "-", "1.0", "0x10", "12a", " 12", "12 ", "+12", "1e9")) {
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new WorldSnapshot(
                                WorldSnapshot.EDITION_JAVA, "26.2", OVERWORLD, bad, 0, 0),
                        "expected rejection of seed: " + bad);
            }
        }
    }

    @Nested
    @DisplayName("section 4.2, app to mod")
    class AppToMod {

        @Test
        @DisplayName("the spec's own route example decodes to a fully checked frame")
        void routeMatchesTheSpecExample() {
            String raw = "{\"type\":\"route\",\"id\":7,\"dimension\":\"overworld\","
                    + "\"label\":\"Woodland Mansion\",\"points\":[[0,0],[240,96],[512,96]]}";

            RouteFrame route = decodeRoute(raw);

            assertEquals(7L, route.id());
            assertEquals(OVERWORLD, route.dimension());
            assertEquals("Woodland Mansion", route.label().literalText());
            assertEquals(
                    List.of(new RoutePoint(0, 0), new RoutePoint(240, 96), new RoutePoint(512, 96)),
                    route.points());
        }

        @Test
        @DisplayName("clear decodes to the singleton")
        void clearDecodes() {
            InboundFrame frame = accepted(NavCodec.decode("{\"type\":\"clear\"}"));

            assertSame(ClearFrame.INSTANCE, frame);
        }

        @Test
        @DisplayName("route and clear round trip through encode and decode unchanged")
        void inboundRoundTrip() {
            RouteFrame original = new RouteFrame(
                    99L,
                    OVERWORLD,
                    SafeLabel.of("Ocean Monument"),
                    List.of(new RoutePoint(-30_000_000, 30_000_000), new RoutePoint(7, -7)));

            RouteFrame back = decodeRoute(NavCodec.encode(original));

            assertEquals(original.id(), back.id());
            assertEquals(original.dimension(), back.dimension());
            assertEquals(original.label().literalText(), back.label().literalText());
            assertEquals(original.points(), back.points());

            assertSame(ClearFrame.INSTANCE, accepted(NavCodec.decode(NavCodec.encode(ClearFrame.INSTANCE))));
        }

        @Test
        @DisplayName("section 4: an unknown type is ignored, never an error")
        void unknownTypeIsIgnored() {
            assertDropped(NavCodec.decode("{\"type\":\"teleport\",\"x\":0}"), DropReason.UNKNOWN_TYPE);
            assertDropped(NavCodec.decode("{\"type\":\"world\"}"), DropReason.UNKNOWN_TYPE);
            assertDropped(NavCodec.decode("{\"nope\":1}"), DropReason.UNKNOWN_TYPE);
            assertDropped(NavCodec.decode("{\"type\":7}"), DropReason.UNKNOWN_TYPE);
        }

        @Test
        @DisplayName("a non-object frame is dropped")
        void nonObjectFramesAreDropped() {
            for (String raw : List.of("[]", "\"route\"", "7", "true", "null", "[{\"type\":\"clear\"}]")) {
                assertDropped(NavCodec.decode(raw), DropReason.NOT_AN_OBJECT);
            }
        }

        @Test
        @DisplayName("a frame that is not JSON at all is dropped, never thrown")
        void malformedJsonIsDropped() {
            for (String raw : List.of("", "   ", "{", "{\"type\":}", "{\"type\":\"clear\",}",
                    "{'type':'clear'}", "{\"type\":\"clear\"} trailing")) {
                assertDropped(NavCodec.decode(raw), DropReason.MALFORMED_JSON);
            }
        }

        @Test
        @DisplayName("a duplicate key cannot smuggle a second points array past the checks")
        void duplicateKeysAreRejected() {
            String raw = "{\"type\":\"route\",\"id\":1,\"dimension\":\"overworld\","
                    + "\"points\":[[0,0],[1,1]],\"points\":[[0,0],[99999999999,1]]}";

            assertDropped(NavCodec.decode(raw), DropReason.MALFORMED_JSON);
        }

        @Test
        @DisplayName("decode never throws for any input, however hostile")
        void decodeNeverThrows() {
            for (String raw : List.of("\u0000", "\uD800", "{\"a\":\"\\uD800\"}", "{\"type\":\"route\"",
                    "[[[[[[[[[[", "{\"type\":\"clear\"}\u0000")) {
                assertNotNull(NavCodec.decode(raw), "decode returned null for input");
            }
            assertDropped(NavCodec.decode(null), DropReason.MALFORMED_JSON);
        }
    }

    // ------------------------------------------------------------------
    // helpers, shared with RouteSanitizerTest
    // ------------------------------------------------------------------

    static RouteFrame decodeRoute(String raw) {
        InboundFrame frame = accepted(NavCodec.decode(raw));
        return assertInstanceOf(RouteFrame.class, frame);
    }

    static InboundFrame accepted(Inbound result) {
        Inbound.Accepted ok = assertInstanceOf(
                Inbound.Accepted.class, result, "expected the frame to be accepted");
        return ok.frame();
    }

    static void assertDropped(Inbound result, DropReason expected) {
        Inbound.Dropped dropped = assertInstanceOf(
                Inbound.Dropped.class, result, "expected the frame to be dropped");
        assertEquals(expected, dropped.reason());
        assertNull(dropped.frameOrNull(), "a dropped frame must not carry a usable frame");
    }
}
