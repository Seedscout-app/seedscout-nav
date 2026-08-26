package app.seedscout.nav.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every rejection path in {@link HandshakeValidator}, asserted individually.
 *
 * <p>One test per rule, on purpose. A single "a bad request is refused" test would still
 * pass if four of the seven checks quietly stopped running, and this class is the only thing
 * standing between a stranger's device on a shared wifi and a live link into the player's
 * game. Every test therefore starts from a request that WOULD be accepted and breaks exactly
 * one thing about it, so a pass means that one rule is load bearing.
 */
class HandshakeValidatorTest {

    private static final Instant NOON = Instant.parse("2026-08-26T12:00:00Z");

    private static final InetAddress PHONE = address("192.168.1.77");
    private static final BoundInterface BOUND = new BoundInterface(address("192.168.1.5"), 24, 52341);
    private static final String HOST = "192.168.1.5:52341";

    private PairingToken token;
    private HandshakeValidator validator;

    HandshakeValidatorTest() {
        reset();
    }

    private void reset() {
        token = PairingToken.issue(NOON);
        validator = new HandshakeValidator(token, BOUND);
    }

    private static InetAddress address(String literal) {
        try {
            // A literal, so this never touches DNS.
            return InetAddress.getByName(literal);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The request a conformant app sends: no Origin, no subprotocol, exact Host, real token. */
    private HandshakeRequest good() {
        return HandshakeRequest.of(PHONE, "/?t=" + token.uriValue(), HOST, NOON);
    }

    private HandshakeRequest with(
            InetAddress peer, String target, String host, String origin, String subprotocol, Instant at) {
        return new HandshakeRequest(peer, target, host, origin, subprotocol, at);
    }

    private void assertRejected(HandshakeDecision decision) {
        assertSame(HandshakeDecision.REJECTED, decision,
                "every rejection must be the one indistinguishable instance");
        assertFalse(decision.isAccepted());
    }

    @Nested
    @DisplayName("the accept path")
    class Accept {

        @Test
        @DisplayName("a conformant request is accepted, and that closes the pairing window")
        void conformantRequestIsAccepted() {
            HandshakeDecision decision = validator.validate(good());

            assertSame(HandshakeDecision.ACCEPTED, decision);
            assertTrue(decision.isAccepted());
            assertTrue(validator.hasAccepted());
            assertEquals(0, validator.rejectionCount());
            // ERRATA on section 3 rule 2: the rule says the token expires on first
            // successful use but never says the LISTENER closes. It must.
            assertTrue(validator.windowClosed(), "the listener must stop accepting after a success");
            assertTrue(token.isSpent(), "a successful handshake spends the token");
        }

        @Test
        @DisplayName("the token is single use, so an identical replay is refused")
        void replayIsRefused() {
            HandshakeRequest request = good();
            assertSame(HandshakeDecision.ACCEPTED, validator.validate(request));

            assertRejected(validator.validate(request));
            assertRejected(validator.validate(request));
        }

        @Test
        @DisplayName("a rejected peer never burns the player's token")
        void aRejectedPeerDoesNotSpendTheToken() {
            assertRejected(validator.validate(with(PHONE, "/?t=wrong", HOST, null, null, NOON)));

            assertFalse(token.isSpent(), "a wrong token must not consume the right one");
            assertSame(HandshakeDecision.ACCEPTED, validator.validate(good()),
                    "the real app can still pair after somebody else guessed wrong");
        }
    }

    @Nested
    @DisplayName("the token itself")
    class Token {

        @Test
        @DisplayName("a wrong token is refused, including one that differs only in its last character")
        void wrongToken() {
            String real = token.uriValue();
            String almost = real.substring(0, real.length() - 1) + (real.endsWith("A") ? "B" : "A");

            assertRejected(validator.validate(with(PHONE, "/?t=" + almost, HOST, null, null, NOON)));
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredToken() {
            assertRejected(validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), HOST, null, null, NOON.plusSeconds(120))));
        }

        @Test
        @DisplayName("a revoked token (the pairing screen closed) is refused")
        void revokedToken() {
            token.revoke();

            assertRejected(validator.validate(good()));
            assertTrue(validator.windowClosed());
        }

        @ParameterizedTest(name = "request target {0} is refused")
        @ValueSource(strings = {
            "/",                      // no query at all
            "",                       // empty target
            "/?",                     // empty query
            "/?t=",                   // empty token
            "/?token=REAL",           // right value, wrong parameter name
            "/?T=REAL",               // parameter names are case sensitive
            "/?t=REAL&x=1",           // a second parameter
            "/?x=1&t=REAL",
            "/?t=REAL%20",            // percent escapes are not decoded, they are refused
            "/pair?t=REAL",           // section 3.1 fixes the path at /
            "//?t=REAL",
            "/?t=REAL ",              // a space is not base64url
            "/?t=REAL\"",
        })
        void malformedRequestTargets(String target) {
            String filled = target.replace("REAL", token.uriValue());

            assertRejected(validator.validate(with(PHONE, filled, HOST, null, null, NOON)));
        }

        @Test
        @DisplayName("a null request target is refused rather than throwing")
        void nullTarget() {
            assertRejected(validator.validate(with(PHONE, null, HOST, null, null, NOON)));
            assertRejected(validator.validate(null));
        }
    }

    @Nested
    @DisplayName("Origin, the browser tell")
    class Origin {

        @ParameterizedTest(name = "Origin: {0} is refused")
        @ValueSource(strings = {
            "http://evil.example",
            "https://minecraft-tools.example",
            "null",
            "http://192.168.1.5:52341",
            "",
        })
        void anyOriginIsRefused(String origin) {
            // dart:io's WebSocket.connect sends no Origin at all; every browser sends one.
            // So the presence of the header, at ANY value including the page's own origin
            // and the literal string "null", means the peer is a web page.
            assertRejected(validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), HOST, origin, null, NOON)));
        }

        @Test
        @DisplayName("the same request without the Origin header is accepted")
        void absentOriginIsTheOnlyAcceptableState() {
            assertSame(HandshakeDecision.ACCEPTED, validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), HOST, null, null, NOON)));
        }
    }

    @Nested
    @DisplayName("Host, the DNS rebinding defence")
    class Host {

        @ParameterizedTest(name = "Host: {0} is refused")
        @ValueSource(strings = {
            "seedscout.local:52341",        // a name that resolved to us: rebinding
            "attacker.example:52341",
            "192.168.1.5",                  // right address, no port
            "192.168.1.5:52342",            // right address, wrong port
            "192.168.1.6:52341",            // wrong address
            "127.0.0.1:52341",
            "192.168.1.5:52341.",
            " ",
            "",
        })
        void wrongHostIsRefused(String host) {
            assertRejected(validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), host, null, null, NOON)));
        }

        @Test
        @DisplayName("an absent Host header is refused")
        void absentHostIsRefused() {
            assertRejected(validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), null, null, null, NOON)));
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated, the value is not")
        void hostIsTrimmedNotLoosened() {
            assertSame(HandshakeDecision.ACCEPTED, validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), "  " + HOST + "  ", null, null, NOON)));
        }
    }

    @Nested
    @DisplayName("subprotocol")
    class Subprotocol {

        @ParameterizedTest(name = "Sec-WebSocket-Protocol: {0} is refused")
        @ValueSource(strings = {"chat", "seedscout", "seedscout-nav, chat", "v2"})
        void anySubprotocolIsRefused(String requested) {
            assertRejected(validator.validate(
                    with(PHONE, "/?t=" + token.uriValue(), HOST, null, requested, NOON)));
        }
    }

    @Nested
    @DisplayName("the peer's address")
    class PeerAddress {

        @ParameterizedTest(name = "a peer at {0} is refused")
        @ValueSource(strings = {
            "192.168.2.77",     // private, but a different subnet
            "10.0.0.5",         // private, different subnet
            "172.16.4.4",
            "8.8.8.8",          // globally routable
            "203.0.113.9",
            "127.0.0.1",        // loopback is NOT a bypass
            "169.254.9.9",      // link local, but not on the bound subnet
            "100.64.0.9",       // CGNAT, but not on the bound subnet
        })
        void offSubnetPeersAreRefused(String peer) {
            assertRejected(validator.validate(
                    with(address(peer), "/?t=" + token.uriValue(), HOST, null, null, NOON)));
        }

        @Test
        @DisplayName("an IPv6 peer against an IPv4 bind is refused")
        void wrongFamilyIsRefused() {
            assertRejected(validator.validate(
                    with(address("fd00::1"), "/?t=" + token.uriValue(), HOST, null, null, NOON)));
        }

        @Test
        @DisplayName("an IPv4 mapped IPv6 address is measured as the IPv4 address it is")
        void ipv4MappedAddressesAreNormalized() {
            // ::ffff:192.168.1.77 is the same peer as 192.168.1.77 and must not get a
            // different answer for being spelled differently.
            assertTrue(BOUND.admits(address("::ffff:192.168.1.77")));
            assertFalse(BOUND.admits(address("::ffff:8.8.8.8")));
        }

        @Test
        @DisplayName("on subnet AND privately addressed are both required, not either")
        void bothConditionsAreRequired() {
            // A machine with a globally routable address: every peer on its own /24 is on
            // subnet, and every one of them is still refused, because version 1 is a LAN
            // protocol with no relay and no cloud hop (section 2).
            BoundInterface publiclyRouted = new BoundInterface(address("203.0.113.5"), 24, 52341);

            assertFalse(publiclyRouted.admits(address("203.0.113.9")),
                    "on subnet is not enough when the subnet itself is public");
            assertTrue(BOUND.admits(PHONE), "on subnet and private is the only accepted combination");
        }

        @Test
        @DisplayName("a link local, CGNAT or unique local LAN is a real LAN and is admitted")
        void otherPrivateRangesWork() {
            assertTrue(new BoundInterface(address("169.254.3.3"), 16, 1)
                    .admits(address("169.254.200.1")), "link local");
            assertTrue(new BoundInterface(address("100.64.0.1"), 10, 1)
                    .admits(address("100.100.5.5")), "CGNAT");
            assertTrue(new BoundInterface(address("fd12:3456::1"), 64, 1)
                    .admits(address("fd12:3456::9")), "IPv6 unique local");
            assertTrue(new BoundInterface(address("fe80::1"), 10, 1)
                    .admits(address("fe80::abcd")), "IPv6 link local");
            assertFalse(new BoundInterface(address("fd12:3456::1"), 64, 1)
                    .admits(address("2606:4700::1111")), "a global IPv6 peer is refused");
        }

        @Test
        @DisplayName("a subnet mask that is not a whole number of bytes still masks correctly")
        void nonByteAlignedPrefix() {
            BoundInterface slash20 = new BoundInterface(address("10.1.16.1"), 20, 1);

            assertTrue(slash20.admits(address("10.1.31.255")), "inside a /20");
            assertFalse(slash20.admits(address("10.1.32.1")), "just outside a /20");
        }

        @Test
        @DisplayName("an IPv6 bound interface advertises a bracketed Host literal")
        void ipv6HostLiteral() {
            BoundInterface ipv6 = new BoundInterface(address("fd12:3456::1"), 64, 52341);

            String literal = ipv6.hostLiteral();

            assertTrue(literal.startsWith("[") && literal.endsWith("]:52341"), literal);

            PairingToken ipv6Token = PairingToken.issue(NOON);
            HandshakeValidator ipv6Validator = new HandshakeValidator(ipv6Token, ipv6);
            assertSame(HandshakeDecision.ACCEPTED, ipv6Validator.validate(HandshakeRequest.of(
                    address("fd12:3456::9"), "/?t=" + ipv6Token.uriValue(), literal, NOON)));
        }
    }

    @Nested
    @DisplayName("rate limiting, section 3 rule 4")
    class RateLimit {

        private HandshakeRequest badTokenFrom(InetAddress peer) {
            return HandshakeRequest.of(peer, "/?t=" + "A".repeat(43), HOST, NOON);
        }

        @Test
        @DisplayName("five failures exhaust one address, even for the correct token afterwards")
        void fivePerPeer() {
            for (int i = 1; i <= NavProtocol.MAX_FAILURES_PER_PEER; i++) {
                assertRejected(validator.validate(badTokenFrom(PHONE)));
                assertEquals(i, validator.rejectionCount());
            }

            // The sixth attempt carries the RIGHT token and is still refused: the address
            // is spent for the rest of this pairing window.
            assertRejected(validator.validate(good()));
            assertFalse(token.isSpent(), "a rate limited peer must not spend the token");
            assertEquals(NavProtocol.MAX_FAILURES_PER_PEER, validator.rejectionCount(),
                    "a request refused by the limit is not counted again, so one blocked peer "
                            + "cannot drive the global counter on its own");
        }

        @Test
        @DisplayName("the limit is per address: a different phone can still pair")
        void limitIsPerPeer() {
            for (int i = 0; i < NavProtocol.MAX_FAILURES_PER_PEER; i++) {
                assertRejected(validator.validate(badTokenFrom(address("192.168.1.90"))));
            }

            assertSame(HandshakeDecision.ACCEPTED, validator.validate(good()));
        }

        @Test
        @DisplayName("twenty failures across the LAN kill the window and force a rescan")
        void twentyPerWindow() {
            for (int i = 0; i < NavProtocol.MAX_FAILURES_PER_WINDOW; i++) {
                // A different address each time, so the per peer limit never fires and
                // this is unambiguously the global ceiling.
                assertRejected(validator.validate(badTokenFrom(address("192.168.1." + (100 + i)))));
            }

            assertTrue(validator.windowKilled());
            assertTrue(validator.windowClosed());
            assertTrue(token.isSpent(), "the window's token dies with the window");
            assertRejected(validator.validate(good()));
            assertEquals(NavProtocol.MAX_FAILURES_PER_WINDOW, validator.rejectionCount());
        }

        @Test
        @DisplayName("nineteen failures is not yet a kill, so an honest player is not locked out early")
        void justUnderTheCeiling() {
            for (int i = 0; i < NavProtocol.MAX_FAILURES_PER_WINDOW - 1; i++) {
                assertRejected(validator.validate(badTokenFrom(address("192.168.1." + (100 + i)))));
            }

            assertFalse(validator.windowKilled());
            assertSame(HandshakeDecision.ACCEPTED, validator.validate(good()));
        }
    }

    @Nested
    @DisplayName("section 3 rule 4: rejections are indistinguishable")
    class Indistinguishable {

        @Test
        @DisplayName("every rejection path returns the same instance with the same rendering")
        void allRejectionsAreIdentical() {
            List<HandshakeDecision> decisions = new ArrayList<>();
            String real = token.uriValue();

            decisions.add(fresh().validate(with(PHONE, "/?t=" + "A".repeat(43), HOST, null, null, NOON)));
            decisions.add(freshWithRealToken((v, t) ->
                    v.validate(with(PHONE, "/?t=" + t, HOST, null, null, NOON.plusSeconds(200)))));
            decisions.add(freshWithRealToken((v, t) -> {
                v.validate(with(PHONE, "/?t=" + t, HOST, null, null, NOON));
                return v.validate(with(PHONE, "/?t=" + t, HOST, null, null, NOON));
            }));
            decisions.add(fresh().validate(with(PHONE, "/?t=" + real, HOST, "http://evil.example", null, NOON)));
            decisions.add(fresh().validate(with(PHONE, "/?t=" + real, "evil.example:52341", null, null, NOON)));
            decisions.add(fresh().validate(with(PHONE, "/?t=" + real, HOST, null, "chat", NOON)));
            decisions.add(fresh().validate(with(address("8.8.8.8"), "/?t=" + real, HOST, null, null, NOON)));
            decisions.add(fresh().validate(with(PHONE, "/nope", HOST, null, null, NOON)));
            decisions.add(fresh().validate(null));

            for (HandshakeDecision decision : decisions) {
                assertSame(HandshakeDecision.REJECTED, decision);
                assertEquals("Rejected", decision.toString(),
                        "the rendering must not hint at which check failed");
            }
            assertEquals(1, decisions.stream().distinct().count(),
                    "there must be exactly one rejection value in the whole system");
        }

        @Test
        @DisplayName("the rejection type carries no field a caller could log")
        void rejectionHasNoPayload() {
            assertEquals(0, HandshakeDecision.Rejected.class.getDeclaredFields().length,
                    "a field here is a reason waiting to be logged");
            assertFalse(HandshakeDecision.Rejected.class.isRecord(),
                    "a record would generate an accessor and a revealing toString");
        }

        @Test
        @DisplayName("the request never renders the token, not even into an assertion message")
        void requestToStringElidesTheToken() {
            HandshakeRequest request = good();

            String rendered = request.toString();

            // Section 3.1 rule 1: no request line, URI or query string in a log, at any
            // level, including on the rejection path.
            assertFalse(rendered.contains(token.uriValue()), rendered);
            assertFalse(rendered.contains(token.uriValue().substring(0, 6)), rendered);
            assertFalse(rendered.contains("/?"), "the request target must not be rendered: " + rendered);
        }

        private HandshakeValidator fresh() {
            return new HandshakeValidator(PairingToken.issue(NOON), BOUND);
        }

        private HandshakeDecision freshWithRealToken(
                java.util.function.BiFunction<HandshakeValidator, String, HandshakeDecision> body) {
            PairingToken fresh = PairingToken.issue(NOON);
            return body.apply(new HandshakeValidator(fresh, BOUND), fresh.uriValue());
        }
    }
}
