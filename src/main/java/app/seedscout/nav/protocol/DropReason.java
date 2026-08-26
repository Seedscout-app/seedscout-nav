package app.seedscout.nav.protocol;

/**
 * Why an inbound frame was thrown away.
 *
 * <p><b>This value never leaves the mod.</b> Section 4 gives the app-to-mod direction an
 * allowlist of exactly {@code route} and {@code clear}, so there is no error verb to reply
 * with, and inventing one would be the third verb section 1 rule 2 forbids. A dropped
 * frame is therefore silent on the wire: this enum exists only so the mod can count and
 * name its own drops in a local log line, and so a test can assert that a hostile frame
 * failed for the reason it was supposed to fail for rather than passing by accident.
 *
 * <p>Contrast {@link HandshakeDecision.Rejected}, which deliberately carries NO reason at
 * all. That asymmetry is intentional: a handshake rejection is observed by an
 * unauthenticated peer and must be indistinguishable (section 3 rule 4), while a frame
 * drop happens after the peer is already through the door.
 */
public enum DropReason {

    /** Not valid JSON under {@link Json}'s strict reader, or a null frame. */
    MALFORMED_JSON,

    /** Larger than {@link NavProtocol#MAX_FRAME_BYTES} in UTF-8 bytes. */
    FRAME_TOO_LARGE,

    /** Valid JSON, but not a JSON object. Section 4: "every frame is a JSON object". */
    NOT_AN_OBJECT,

    /**
     * No usable {@code type} string, or a {@code type} outside the {@code route} and
     * {@code clear} allowlist. Section 4 requires this to be IGNORED rather than treated
     * as an error, so a newer app can talk to an older mod.
     */
    UNKNOWN_TYPE,

    /** {@code route.id} absent, not an integer, or negative. */
    BAD_ROUTE_ID,

    /** {@code route.dimension} absent, not a string, empty, or implausibly long. */
    BAD_DIMENSION,

    /** {@code route.label} present but not a string. */
    BAD_LABEL,

    /** {@code route.points} absent, not an array, or holding something that is not a pair. */
    BAD_POINTS_ARRAY,

    /** {@code route.points} outside the 2 to 512 bound of section 4.2. */
    POINT_COUNT_OUT_OF_RANGE,

    /** A coordinate that was a JSON fraction or exponent rather than an integer. */
    POINT_NOT_INTEGER,

    /** A coordinate outside plus or minus {@link NavProtocol#MAX_COORDINATE}. */
    COORDINATE_OUT_OF_RANGE,
}
