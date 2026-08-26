package app.seedscout.nav.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.seedscout.nav.protocol.ClearFrame;
import app.seedscout.nav.protocol.Inbound;
import app.seedscout.nav.protocol.InboundFrame;
import app.seedscout.nav.protocol.NavCodec;
import app.seedscout.nav.protocol.OutboundFrame;
import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.UnlinkFrame;
import app.seedscout.nav.protocol.WorldSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The anti-drift half of the wire contract: this repository's copy of {@code
 * shared/nav_protocol.md} and every file under {@code fixtures/wire/} is what the app
 * repository's {@code app/test/nav_wire_fixture_test.dart} feeds through the Dart parser
 * (see that file for the app side of this pair).
 *
 * <p>The mechanism has two independent layers, and BOTH must break when either repository
 * edits its half of the contract without updating the other:
 *
 * <ol>
 *   <li><b>Content.</b> This class decodes every fixture through the real {@link NavCodec}
 *       (or re-derives it through the real encoder for the mod-to-app direction, since the
 *       mod never decodes its own outbound types) and asserts the exact values a
 *       conformant Dart parser must also produce. A change to the wire shape on either side
 *       that the other side does not know about fails one of these assertions.</li>
 *   <li><b>Bytes.</b> {@link #fixtureBytesArePinned()} hardcodes the sha256 of the protocol
 *       document and every fixture file, as literal constants. {@code
 *       nav_wire_fixture_test.dart} hardcodes the SAME literals against ITS copies. Editing
 *       {@code shared/nav_protocol.md} or any {@code fixtures/wire/*.json} file in only one
 *       repository changes that repository's own sha256 out from under its own hardcoded
 *       constant, so the edit fails red in the repository it was made in; forgetting to
 *       carry the edit (and the new constant) to the other repository leaves that
 *       repository's hardcoded constant matching STALE bytes, which is exactly the drift
 *       this mechanism exists to catch when a human eventually updates one side's constant
 *       but not the other's. Neither side can silently drift alone: touching the shared
 *       text without touching the matching hardcoded hash is a local failure, and touching
 *       both without coordinating with the other repository is a cross-repo failure the
 *       next time anyone diffs the two hash literals during review.</li>
 * </ol>
 *
 * <p>{@code fixtures/wire/CHECKSUMS.sha256} is a separate, human/CI-facing copy of the same
 * hashes (checked by {@code sha256sum -c} in {@code .github/workflows/ci.yml}); it is
 * generated from the same source files as the constants below but is NOT read by this
 * class, on purpose, so a single bad edit cannot make both checks agree with each other
 * while quietly disagreeing with the app repository.
 */
class WireFixtureTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
    private static final Path PROTOCOL_DOC = REPO_ROOT.resolve("shared/nav_protocol.md");
    private static final Path FIXTURES_DIR = REPO_ROOT.resolve("fixtures/wire");

    // ------------------------------------------------------------------
    // Layer 2: pinned bytes. See the class doc. Every one of these was produced by
    // `shasum -a 256` against the exact file it names, at the moment the fixture was
    // authored. A red here means shared/nav_protocol.md or a fixture changed without this
    // constant changing with it.
    // ------------------------------------------------------------------

    private static final String SHA256_PROTOCOL_DOC =
            "73db328ba98c264fa7ba58745f34e5cd32c89f396cb6e057a5d3489c25cd5193";

    private static final String SHA256_WORLD =
            "ff03da82de42de6a05d79062e2a7acbd746b68b30d78ba4752093a705e85a486";

    private static final String SHA256_WORLD_NEGATIVE_SEED =
            "46ad72df8042ecd5b9971d39f87029016b62c4df7eed1352b997bea19150f13c";

    private static final String SHA256_WORLD_MULTIPLAYER_NULL_SEED =
            "2467e649b34181ffc6006b9c926d444088104843e60b3f65275f454e8666bd31";

    private static final String SHA256_POS =
            "bf3672ae04917762c381eaea8114665a221c419c52699987fee58327f3756f73";

    private static final String SHA256_UNLINK =
            "ff518ba53afa1c5832f9d4fa9090a92f91d9b2c025f3c924ae93e6ae8c930431";

    private static final String SHA256_ROUTE_MAX_POINTS_STRIPPED_LABEL =
            "96646bf09298e667ef09236b0818e220e45c180bea2f24d739ce072c236f5781";

    private static final String SHA256_CLEAR =
            "60b3521f8d615a44d519147582f40cf7cb14e3933f254101a885b6961202b7d0";

    @Test
    @DisplayName("shared/nav_protocol.md and every fixture match their pinned sha256")
    void fixtureBytesArePinned() throws IOException, NoSuchAlgorithmException {
        assertHash(PROTOCOL_DOC, SHA256_PROTOCOL_DOC);
        assertHash(fixture("world.json"), SHA256_WORLD);
        assertHash(fixture("world_negative_seed.json"), SHA256_WORLD_NEGATIVE_SEED);
        assertHash(fixture("world_multiplayer_null_seed.json"), SHA256_WORLD_MULTIPLAYER_NULL_SEED);
        assertHash(fixture("pos.json"), SHA256_POS);
        assertHash(fixture("unlink.json"), SHA256_UNLINK);
        assertHash(
                fixture("route_max_points_stripped_label.json"),
                SHA256_ROUTE_MAX_POINTS_STRIPPED_LABEL);
        assertHash(fixture("clear.json"), SHA256_CLEAR);
    }

    // ------------------------------------------------------------------
    // Layer 1: content, mod to app direction (world, pos, unlink). The mod only ever
    // ENCODES these, so the fixture proves the mod's own encoder still produces the exact
    // canonical bytes; app/test/nav_wire_fixture_test.dart is what proves the app's decoder
    // reads those same bytes back into the equivalent values.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("world: a seed well past 2^53 encodes to the pinned fixture bytes")
    void worldWithExtremeSeedMatchesFixture() throws IOException {
        WorldSnapshot world = new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, "26.2", "overworld",
                WorldSnapshot.seedString(Long.MAX_VALUE), 112, -208);
        assertEncodesToFixture(world, "world.json");
    }

    @Test
    @DisplayName("world: a negative seed encodes to the pinned fixture bytes")
    void worldWithNegativeSeedMatchesFixture() throws IOException {
        WorldSnapshot world = new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, "26.2", "the_nether",
                WorldSnapshot.seedString(Long.MIN_VALUE), 0, 0);
        assertEncodesToFixture(world, "world_negative_seed.json");
    }

    @Test
    @DisplayName("world: a multiplayer null seed encodes to the pinned fixture bytes")
    void worldWithNullSeedMatchesFixture() throws IOException {
        WorldSnapshot world = new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, "26.2", "overworld", null, 0, 0);
        assertEncodesToFixture(world, "world_multiplayer_null_seed.json");
    }

    @Test
    @DisplayName("pos: the section 4.1 shape encodes to the pinned fixture bytes")
    void posMatchesFixture() throws IOException {
        PlayerPosition pos = new PlayerPosition(134.5, 71.0, -902.25, 47.5, "overworld");
        assertEncodesToFixture(pos, "pos.json");
    }

    @Test
    @DisplayName("unlink: player_left_world encodes to the pinned fixture bytes")
    void unlinkMatchesFixture() throws IOException {
        UnlinkFrame unlink = new UnlinkFrame(UnlinkFrame.PLAYER_LEFT_WORLD);
        assertEncodesToFixture(unlink, "unlink.json");
    }

    // ------------------------------------------------------------------
    // Layer 1: content, app to mod direction (route, clear). The app only ever ENCODES
    // these (see NavLinkService.sendRoute/sendClear), so here the mod DECODES the fixture
    // and asserts the values a real route/clear from the app would produce, including the
    // label stripping section 4.2 leaves entirely to the mod.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("route: 512 points and a label needing stripping decode correctly")
    void routeAtMaxPointsWithDirtyLabelDecodesFromFixture() throws IOException {
        String raw = Files.readString(fixture("route_max_points_stripped_label.json"), StandardCharsets.UTF_8);

        InboundFrame frame = accepted(NavCodec.decode(raw));
        RouteFrame route = assertInstanceOf(RouteFrame.class, frame);

        assertEquals(42L, route.id());
        assertEquals("overworld", route.dimension());
        // The wire label carries a color code, a NUL, an obfuscate code and a
        // right-to-left override; every one of those must be gone, and nothing else
        // should be.
        assertEquals("Cave Entry!", route.label().literalText());
        assertEquals(512, route.points().size());
        assertEquals(0, route.points().get(0).x());
        assertEquals(0, route.points().get(0).z());
        assertEquals(511, route.points().get(511).x());
        assertEquals(-511, route.points().get(511).z());
    }

    @Test
    @DisplayName("clear: decodes to the singleton from the fixture")
    void clearDecodesFromFixture() throws IOException {
        String raw = Files.readString(fixture("clear.json"), StandardCharsets.UTF_8);

        InboundFrame frame = accepted(NavCodec.decode(raw));

        assertTrue(frame == ClearFrame.INSTANCE, "clear.json must decode to the ClearFrame singleton");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Path fixture(String name) {
        return FIXTURES_DIR.resolve(name);
    }

    private static void assertEncodesToFixture(OutboundFrame frame, String fixtureName) throws IOException {
        String expected = Files.readString(fixture(fixtureName), StandardCharsets.UTF_8).stripTrailing();
        assertEquals(expected, NavCodec.encode(frame), "encoded bytes must match " + fixtureName + " exactly");
    }

    private static InboundFrame accepted(Inbound result) {
        Inbound.Accepted ok = assertInstanceOf(Inbound.Accepted.class, result, "expected the fixture to decode");
        return ok.frame();
    }

    private static void assertHash(Path file, String expectedHex) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(file);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(expectedHex, hex.toString(), file + " sha256 drifted from its pinned value");
    }
}
