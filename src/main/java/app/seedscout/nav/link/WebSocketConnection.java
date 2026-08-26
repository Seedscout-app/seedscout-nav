package app.seedscout.nav.link;

import app.seedscout.nav.protocol.NavProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A minimal RFC 6455 WebSocket data-frame connection over an already upgraded {@link Socket}.
 *
 * <p>Deliberately not a general purpose WebSocket implementation: it speaks exactly what
 * {@code shared/nav_protocol.md} needs and nothing else.
 *
 * <ul>
 *   <li>Only text frames are meaningful on this link (section 2: "Text frames, one JSON
 *       object per frame"); binary frames are read (so they cannot desynchronize the
 *       stream) and then ignored.</li>
 *   <li>Message fragmentation (a frame with FIN unset, continued by continuation frames) is
 *       not supported. Every frame this protocol ever carries is a small JSON object, at
 *       most {@link NavProtocol#MAX_FRAME_BYTES}, which every WebSocket client sends as a
 *       single frame in practice. A fragmented message is treated as an unsupported opcode
 *       and ignored rather than corrupting later reads.</li>
 *   <li>Frames from the peer are read as masked (RFC 6455 requires a client to mask; an
 *       unmasked inbound frame is unmasked in place with a no-op mask, which costs nothing
 *       and never misparses a masked one).</li>
 *   <li>Frames this class sends are never masked, as RFC 6455 requires of a server.</li>
 *   <li>A payload length above {@link NavProtocol#MAX_FRAME_BYTES} closes the connection
 *       before the payload is even allocated. ERRATA: section 4.2 states no frame size
 *       bound; this is the transport-level half of that gap, matching the same bound
 *       {@link app.seedscout.nav.protocol.RouteSanitizer} enforces on the decoded text.</li>
 * </ul>
 */
final class WebSocketConnection {

    /** Called from this connection's own read thread; never the game thread. */
    interface Listener {
        void onText(String text);

        /** The peer closed the connection, or the socket failed. Terminal: called once. */
        void onClosed();
    }

    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    private volatile long lastActivityMillis = System.currentTimeMillis();
    private volatile boolean closed;

    WebSocketConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Starts the blocking read loop on a fresh virtual thread. Call at most once. */
    void startReading(Listener listener) {
        Thread.startVirtualThread(() -> readLoop(listener));
    }

    /** Milliseconds since any inbound frame (text, ping or pong) was last seen. */
    long idleMillis() {
        return System.currentTimeMillis() - lastActivityMillis;
    }

    boolean isClosed() {
        return closed;
    }

    void sendText(String text) {
        writeFrame(OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    void sendPing() {
        writeFrame(OPCODE_PING, new byte[0]);
    }

    /** Sends a close frame (best effort) and tears down the socket. Idempotent. */
    void close() {
        if (closed) {
            return;
        }
        writeFrame(OPCODE_CLOSE, new byte[] {0x03, (byte) 0xE8}); // 1000, normal closure
        closeSocket();
    }

    private void closeSocket() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing meaningful to do with a failure to close an already-dead socket.
        }
    }

    private void writeFrame(int opcode, byte[] payload) {
        if (closed) {
            return;
        }
        synchronized (writeLock) {
            try {
                out.write(0x80 | opcode); // FIN set, no extension bits, server never masks
                int len = payload.length;
                if (len <= 125) {
                    out.write(len);
                } else if (len <= 0xFFFF) {
                    out.write(126);
                    out.write((len >>> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) (len >>> shift) & 0xFF);
                    }
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                closeSocket();
            }
        }
    }

    private void readLoop(Listener listener) {
        try {
            while (!closed) {
                Frame frame = readFrame();
                if (frame == null) {
                    break;
                }
                lastActivityMillis = System.currentTimeMillis();
                if (frame.opcode == OPCODE_TEXT) {
                    listener.onText(new String(frame.payload, StandardCharsets.UTF_8));
                } else if (frame.opcode == OPCODE_PING) {
                    writeFrame(OPCODE_PONG, frame.payload);
                } else if (frame.opcode == OPCODE_CLOSE) {
                    writeFrame(OPCODE_CLOSE, frame.payload);
                    closeSocket();
                    break;
                }
                // OPCODE_PONG: activity timestamp already updated above, nothing else to do.
                // OPCODE_BINARY / OPCODE_CONTINUATION: not part of this protocol, ignored.
            }
        } catch (IOException ignored) {
            // A read failure ends the connection the same way a clean close does.
        } finally {
            closeSocket();
            listener.onClosed();
        }
    }

    private record Frame(int opcode, byte[] payload) {
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = readByte();
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte();
            }
        }
        if (len < 0 || len > NavProtocol.MAX_FRAME_BYTES) {
            throw new IOException("frame exceeds the maximum size");
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return new Frame(opcode, payload);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("connection closed mid-frame");
        }
        return b;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n == -1) {
                throw new EOFException("connection closed mid-frame");
            }
            offset += n;
        }
    }
}
