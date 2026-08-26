package app.seedscout.nav.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Secret comparison that does not leak how much of the secret was right.
 *
 * <p>Its own class, with exactly one method and no other code in it, on purpose. The
 * comparison is the single line where a wrong choice is invisible in review and catastrophic
 * in effect: {@code String.equals} returns as soon as it finds a differing character, so the
 * time it takes tells an attacker how long a prefix they guessed correctly, which turns a
 * 256 bit token into a byte at a time search. Isolating the primitive here means a test can
 * inspect this class's bytecode and assert the constant time primitive is the one actually
 * compiled in, rather than trusting a comment on a method that also does five other things.
 *
 * <p>The method is deliberately NOT named with the substring that a short circuiting
 * comparison would put in the constant pool, so that bytecode assertion can be a plain
 * search with no false positive from our own method name.
 */
final class ConstantTime {

    private ConstantTime() {
    }

    /**
     * Compares two secrets in time that depends only on their lengths, never on how many
     * leading bytes matched.
     *
     * <p>Null is never equal to anything, including another null: a null here means a
     * caller failed to extract a value, and treating two failures as a match would be an
     * authentication bypass.
     *
     * <p>Length is not hidden and cannot be: the tokens this compares are a fixed 43
     * character base64url encoding of {@link NavProtocol#TOKEN_BYTES} bytes, so length
     * carries no secret.
     */
    static boolean sameSecret(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        byte[] a = presented.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
