package app.seedscout.nav.link;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A raw HTTP request line and headers, read byte by byte off the still-unwrapped socket
 * {@link InputStream} up to the blank line that ends the header block.
 *
 * <p>This is deliberately not a general purpose HTTP parser: it reads exactly one request,
 * stops reading the instant the header terminator is seen (so the remaining bytes on the
 * stream, the WebSocket frames, are untouched by any buffering), and caps the total bytes
 * read so a peer that never sends a blank line cannot hold the accept thread hostage with
 * an unbounded stream.
 *
 * <p>Per {@code shared/nav_protocol.md} section 3.1 rule 1, nothing in this class or its
 * caller ever logs the request line, the target, or any header value.
 */
final class HttpUpgradeRequest {

    /** Generous for a WebSocket upgrade, which carries no body and a handful of headers. */
    private static final int MAX_HEADER_BYTES = 8 * 1024;

    private final String method;
    private final String target;
    private final Map<String, String> headers;

    private HttpUpgradeRequest(String method, String target, Map<String, String> headers) {
        this.method = method;
        this.target = target;
        this.headers = headers;
    }

    static HttpUpgradeRequest read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("connection closed during the HTTP upgrade");
            }
            buffer.write(b);
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw new IOException("request header too large");
            }
            if ((byte) b == terminator[matched]) {
                matched++;
            } else {
                matched = (byte) b == terminator[0] ? 1 : 0;
            }
        }
        String raw = buffer.toString(StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\r\n", -1);
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IOException("empty request line");
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            throw new IOException("malformed request line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.putIfAbsent(name, value);
        }
        return new HttpUpgradeRequest(requestLine[0], requestLine[1], headers);
    }

    String target() {
        return target;
    }

    String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * True only for a request shaped like an actual WebSocket upgrade attempt. A plain HTTP
     * probe (a browser hitting the port directly, a port scanner) fails this and never
     * reaches {@link app.seedscout.nav.protocol.HandshakeValidator#validate}.
     *
     * <p>It is NOT free, though, and this class's caller must keep it that way: a failure
     * here is charged to the same per peer and global failure budgets through
     * {@link app.seedscout.nav.protocol.HandshakeValidator#recordPreHandshakeFailure}. An
     * earlier version treated a non-upgrade probe as costless on the grounds that it "was
     * never a token attempt at all", which handed an attacker a flood of half-open sockets
     * that occupied the listener for the whole upgrade timeout without ever touching a rate
     * limit. The peer still sees the one shared rejection response either way, so nothing
     * about which side of this test a request fell on is observable to it.
     */
    boolean isWebSocketUpgrade() {
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        String upgrade = header("upgrade");
        String key = header("sec-websocket-key");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")
                && key != null && !key.isBlank();
    }
}
