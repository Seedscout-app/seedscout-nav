package app.seedscout.nav.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The fixed RFC 6455 arithmetic for turning a {@code Sec-WebSocket-Key} into its answer. */
final class WebSocketHandshake {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    static String acceptValue(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandated by every JDK distribution; this cannot happen in practice.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
