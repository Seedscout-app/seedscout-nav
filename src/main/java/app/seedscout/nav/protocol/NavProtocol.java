package app.seedscout.nav.protocol;

import java.time.Duration;

/**
 * Every numeric limit and timeout the Seedscout Nav link obeys, in one place.
 *
 * <p>The normative contract is {@code shared/nav_protocol.md} version 1 in the Seedline
 * repository, mirrored into this repository. Where a value below is quoted from that
 * document the section is cited. Where the document is SILENT, the constant is marked
 * ERRATA and carries the reasoning for the value chosen here: those gaps are closed
 * mod-side only and need no app change, because in every case this side is choosing to
 * be stricter or more talkative than the contract requires, never less.
 *
 * <p>This class, and the whole {@code app.seedscout.nav.protocol} package, has ZERO
 * Minecraft imports. That is load-bearing, not stylistic: it keeps the security-critical
 * code unit-testable headlessly and lets a Minecraft version bump land without touching
 * a single line of protocol logic.
 */
public final class NavProtocol {

    private NavProtocol() {
    }

    /** Protocol version, the {@code v} field of the {@code seedscout://pair} URI (section 3). */
    public static final int VERSION = 1;

    // ---------------------------------------------------------------------
    // Framing
    // ---------------------------------------------------------------------

    /**
     * ERRATA (section 4.2 states no frame size bound). Hard cap on a single inbound text
     * frame, measured in UTF-8 bytes. 64 KiB is roughly 40x the largest legal
     * {@code route} frame (512 points of two 9-digit coordinates plus a 64 character
     * label is under 12 KiB), so it cannot reject legitimate traffic, while still
     * denying an unbounded-allocation attack from a paired-but-hostile peer.
     */
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    /**
     * ERRATA (section 4 states no nesting bound). Maximum JSON nesting depth accepted by
     * {@link Json#parse}. The deepest legal frame is {@code route}, at depth 3
     * (object, {@code points} array, point array), so 8 is generous while making a
     * stack-exhausting nest of brackets impossible.
     */
    public static final int MAX_JSON_DEPTH = 8;

    // ---------------------------------------------------------------------
    // route bounds (section 4.2, plus ERRATA for the coordinate bound)
    // ---------------------------------------------------------------------

    /** Section 4.2: {@code points} carries "at least 2". */
    public static final int MIN_ROUTE_POINTS = 2;

    /** Section 4.2: {@code points} carries "at most 512". */
    public static final int MAX_ROUTE_POINTS = 512;

    /**
     * ERRATA (section 4.2 states no coordinate bound). Plus or minus 30,000,000, which is
     * the Minecraft world border limit. A coordinate outside it can never be walked to,
     * so accepting one only risks overflow in whatever renders it.
     */
    public static final int MAX_COORDINATE = 30_000_000;

    /** Maximum {@code label} length in code points, AFTER sanitizing (see {@link SafeLabel}). */
    public static final int MAX_LABEL_CODE_POINTS = 64;

    /**
     * Maximum {@code dimension} length in characters. The dimension is compared, never
     * rendered, and every real dimension id is far under this.
     */
    public static final int MAX_DIMENSION_CHARS = 64;

    // ---------------------------------------------------------------------
    // Pairing (section 3)
    // ---------------------------------------------------------------------

    /** Section 3: "Base64url pairing token, 32 bytes from a cryptographic RNG". */
    public static final int TOKEN_BYTES = 32;

    /** Section 3 rule 2: the token expires "after 120 seconds". */
    public static final Duration TOKEN_TTL = Duration.ofSeconds(120);

    /**
     * Section 3 rule 4 requires a failed token to be rate limited but names no number.
     * Five failures from one remote address exhaust that address for the rest of the
     * pairing window.
     */
    public static final int MAX_FAILURES_PER_PEER = 5;

    /**
     * Twenty failures in total kill the pairing window outright and force the player to
     * open a fresh QR code. This is the ceiling that makes a distributed guessing attempt
     * pointless: 20 guesses against a 256 bit token is not a threat, and the window dies
     * long before an attacker can rotate through enough source addresses to matter.
     */
    public static final int MAX_FAILURES_PER_WINDOW = 20;

    /**
     * ERRATA (section 3 states no idle timeout). A peer that completes a TCP connection
     * but does not finish the HTTP upgrade within this budget is dropped. The app's own
     * connect budget is 5 seconds (NavLinkService.connectTimeout), so 10 seconds cannot
     * cut off a client that is still trying, and it closes the door on a socket held open
     * to occupy the listener.
     */
    public static final Duration UPGRADE_IDLE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * ERRATA (section 3 states no idle timeout). A LINKED socket that has produced no
     * inbound traffic at all (no frame, no pong) for this long is dropped. The app sends
     * {@code route} and {@code clear} only on a human action and may legitimately be
     * silent for minutes, so liveness rides on the WebSocket ping the mod sends every
     * {@link #LINK_PING_INTERVAL}: {@code dart:io}'s WebSocket answers a ping
     * automatically, so a live app always resets this clock without any protocol change.
     */
    public static final Duration LINK_IDLE_TIMEOUT = Duration.ofSeconds(90);

    /** How often the mod pings a linked socket. See {@link #LINK_IDLE_TIMEOUT}. */
    public static final Duration LINK_PING_INTERVAL = Duration.ofSeconds(30);

    // ---------------------------------------------------------------------
    // pos cadence (section 4.1, plus ERRATA for the floor)
    // ---------------------------------------------------------------------

    /** Section 4.1: {@code pos} is "throttled to at most 5 Hz", so 200 ms between frames. */
    public static final Duration POS_MIN_INTERVAL = Duration.ofMillis(200);

    /**
     * ERRATA (section 4.1 sets a maximum rate but NO minimum). The app ages its live
     * marker out after 2 seconds, so a conformant mod that only sent {@code pos} on
     * movement would leave a standing player's marker permanently stale. The mod
     * therefore re-sends at least once per second even when nothing moved. This is
     * mod-side only: the app already accepts an unchanged position.
     */
    public static final Duration POS_MAX_INTERVAL = Duration.ofMillis(1000);
}
