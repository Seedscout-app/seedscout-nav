package app.seedscout.nav.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The pairing secret from {@code shared/nav_protocol.md} section 3: its shape, its three
 * deaths, and the property that makes it worth 32 bytes in the first place, which is that
 * comparing it leaks nothing about how much of it an attacker got right.
 */
class PairingTokenTest {

    private static final Instant NOON = Instant.parse("2026-08-26T12:00:00Z");

    @Nested
    @DisplayName("shape and entropy")
    class Shape {

        @Test
        @DisplayName("32 bytes of SecureRandom, base64url encoded, no padding")
        void tokenShape() {
            String value = PairingToken.issue(NOON).uriValue();

            // 32 bytes base64 encodes to 43 characters plus one padding character, which
            // the encoder drops.
            assertEquals(43, value.length(), value);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean base64url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_';
                assertTrue(base64url, "non base64url character at " + i + ": " + c);
            }
        }

        @Test
        @DisplayName("every issued token is different")
        void tokensAreUnique() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                assertTrue(seen.add(PairingToken.issue(NOON).uriValue()), "a token repeated");
            }
        }

        @Test
        @DisplayName("the token never appears in toString, so it cannot reach a log by accident")
        void toStringDoesNotCarryTheSecret() {
            PairingToken token = PairingToken.issue(NOON);

            String rendered = token.toString();

            assertFalse(rendered.contains(token.uriValue()), rendered);
            // Section 3 rule 1 binds the transport too (section 3.1): a token that shows
            // up in a formatted string is a token that shows up in a log line.
            assertFalse(rendered.contains(token.uriValue().substring(0, 8)), rendered);
        }
    }

    @Nested
    @DisplayName("section 3 rule 2, the three deaths")
    class Expiry {

        @Test
        @DisplayName("a fresh token verifies")
        void freshTokenVerifies() {
            PairingToken token = PairingToken.issue(NOON);

            assertTrue(token.verify(token.uriValue(), NOON));
            assertTrue(token.isLive(NOON));
        }

        @Test
        @DisplayName("the token dies at exactly 120 seconds, not a tick later")
        void expiresAfter120Seconds() {
            PairingToken token = PairingToken.issue(NOON);

            assertEquals(Duration.ofSeconds(120), Duration.between(NOON, token.expiresAt()));
            assertTrue(token.verify(token.uriValue(), NOON.plusSeconds(119)));
            assertTrue(token.verify(token.uriValue(), NOON.plusMillis(119_999)));
            assertFalse(token.verify(token.uriValue(), NOON.plusSeconds(120)),
                    "the boundary itself is dead, not alive");
            assertFalse(token.verify(token.uriValue(), NOON.plusSeconds(121)));
            assertFalse(token.isLive(NOON.plusSeconds(120)));
        }

        @Test
        @DisplayName("the token is single use: a second presentation of the right value fails")
        void singleUse() {
            PairingToken token = PairingToken.issue(NOON);
            assertTrue(token.verify(token.uriValue(), NOON));

            token.consume();

            assertFalse(token.verify(token.uriValue(), NOON),
                    "a consumed token must not verify again, however correct the value");
            assertTrue(token.isSpent());
            assertFalse(token.isLive(NOON));
        }

        @Test
        @DisplayName("closing the pairing screen revokes the token immediately")
        void revoke() {
            PairingToken token = PairingToken.issue(NOON);

            token.revoke();

            assertFalse(token.verify(token.uriValue(), NOON));
            assertTrue(token.isSpent());
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("a wrong token of the same length is refused")
        void wrongTokenSameLength() {
            PairingToken token = PairingToken.issue(NOON);
            String real = token.uriValue();

            assertFalse(token.verify(flip(real, 0), NOON), "differs in the first character");
            assertFalse(token.verify(flip(real, real.length() - 1), NOON),
                    "differs ONLY in the last character, so a comparison that stopped early would pass it");
            assertFalse(token.verify(flip(real, real.length() / 2), NOON));
        }

        @Test
        @DisplayName("a prefix, a suffix and an overlong value are all refused")
        void wrongLengths() {
            PairingToken token = PairingToken.issue(NOON);
            String real = token.uriValue();

            assertFalse(token.verify(real.substring(0, real.length() - 1), NOON));
            assertFalse(token.verify(real + "A", NOON));
            assertFalse(token.verify("", NOON));
            assertFalse(token.verify(null, NOON));
        }

        @Test
        @DisplayName("base64url is case sensitive and so is this comparison")
        void caseSensitive() {
            PairingToken token = PairingToken.issue(NOON);
            String real = token.uriValue();

            assertNotEquals(real, real.toLowerCase(java.util.Locale.ROOT),
                    "43 random base64url characters with no letter case difference is a broken RNG");
            assertFalse(token.verify(real.toLowerCase(java.util.Locale.ROOT), NOON));
            assertFalse(token.verify(real.toUpperCase(java.util.Locale.ROOT), NOON));
        }

        private static String flip(String value, int index) {
            char c = value.charAt(index);
            char replacement = c == 'A' ? 'B' : 'A';
            return value.substring(0, index) + replacement + value.substring(index + 1);
        }
    }

    @Nested
    @DisplayName("the comparison is constant time, not merely correct")
    class ConstantTimeComparison {

        @Test
        @DisplayName("sameSecret agrees with equality on every ordinary case")
        void correctness() {
            assertTrue(ConstantTime.sameSecret("abc", "abc"));
            assertTrue(ConstantTime.sameSecret("", ""));
            assertFalse(ConstantTime.sameSecret("abc", "abd"));
            assertFalse(ConstantTime.sameSecret("abc", "abcd"));
            assertFalse(ConstantTime.sameSecret("abc", "ab"));
            assertFalse(ConstantTime.sameSecret("abc", null));
            assertFalse(ConstantTime.sameSecret(null, "abc"));
            assertFalse(ConstantTime.sameSecret(null, null),
                    "two failures to extract a value must never read as a match");
        }

        /**
         * A behavioural test cannot tell a constant time comparison from a short circuiting
         * one: both return false for a wrong token, and the difference between them is a
         * few dozen nanoseconds, far below what a JIT warmed benchmark can measure without
         * being flaky. So this asserts the property where it is actually decidable, in the
         * compiled bytecode.
         *
         * <p>{@link ConstantTime} exists as a class with one method and nothing else
         * precisely so this assertion can be exact: if someone replaces the body with
         * {@code presented.equals(expected)}, the constant pool gains a reference to
         * {@code String.equals} and this test fails. The method is named
         * {@code sameSecret} rather than anything containing the substring so the search
         * cannot match our own name.
         */
        @Test
        @DisplayName("the compiled comparison calls MessageDigest.isEqual and nothing short circuiting")
        void bytecodeUsesTheConstantTimePrimitive() throws IOException {
            String bytecode = classBytes();

            assertTrue(bytecode.contains("java/security/MessageDigest"),
                    "ConstantTime must reference MessageDigest");
            assertTrue(bytecode.contains("isEqual"),
                    "ConstantTime must call MessageDigest.isEqual");
            assertFalse(bytecode.contains("equals"),
                    "a short circuiting equals in the secret comparison is a timing oracle");
            assertFalse(bytecode.contains("compareTo"),
                    "compareTo short circuits too and leaks the same prefix length");
            assertFalse(bytecode.contains("contentEquals"), "contentEquals short circuits too");
        }

        private static String classBytes() throws IOException {
            try (InputStream in = ConstantTime.class.getResourceAsStream("ConstantTime.class")) {
                assertTrue(in != null, "ConstantTime.class must be readable from the classpath");
                // ISO-8859-1 is a byte preserving round trip, so a substring search here is
                // a byte search over the whole class file, constant pool included.
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }
}
