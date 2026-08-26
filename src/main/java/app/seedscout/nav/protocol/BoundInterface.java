package app.seedscout.nav.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Objects;

/**
 * The LAN address, subnet and port the pairing listener is bound to.
 *
 * <p>Section 2 says the mod "binds to the LAN interface on an ephemeral port" and stops
 * there. ERRATA: it never says to reject a peer from OUTSIDE that interface's own subnet,
 * and that gap matters more here than in a typical localhost design. The phone is a
 * different device, so loopback is not available as a boundary: this listener is genuinely
 * reachable by every other device on the wifi, and on a hotel, dorm, cafe or conference
 * network that is an arbitrary set of strangers. The subnet test below is the boundary
 * that a loopback bind would otherwise have given for free.
 *
 * <p>{@link #admits} therefore requires BOTH conditions, not either:
 *
 * <ol>
 *   <li>the peer is inside this interface's own subnet, and</li>
 *   <li>the peer's address is itself a private, CGNAT or link local address.</li>
 * </ol>
 *
 * <p>The second condition is not redundant. If the machine has a globally routable address
 * (an ISP that hands out real addresses, a cloud VM, a misconfigured router), condition one
 * alone would admit a peer from the public internet. Version 1 is a LAN protocol with no
 * relay and no cloud hop (section 2), so a globally routable peer is out of contract no
 * matter which subnet it sits in. Loopback is not on the allowlist either: this listener is
 * bound to a LAN address, so a loopback peer is already off subnet, and admitting one would
 * hand every process on the host a free bypass.
 */
public record BoundInterface(InetAddress address, int prefixLength, int port) {

    public BoundInterface {
        Objects.requireNonNull(address, "address");
        int bits = address.getAddress().length * 8;
        if (prefixLength < 0 || prefixLength > bits) {
            throw new IllegalArgumentException("prefixLength outside 0.." + bits);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port outside 1..65535");
        }
    }

    /**
     * The exact {@code ip:port} literal a conformant client must send in its {@code Host}
     * header, matching the {@code h} and {@code p} fields of the pairing URI (section 3).
     * IPv6 is bracketed, as the URI form requires.
     */
    public String hostLiteral() {
        String host = address.getHostAddress();
        if (address instanceof Inet4Address) {
            return host + ":" + port;
        }
        // Strip any scope id: the pairing URI carries a bare literal, and the app's own
        // parser treats the host as opaque, so a scope suffix would never match.
        int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        return "[" + host + "]:" + port;
    }

    /** True when a peer at {@code peer} is allowed to reach this listener at all. */
    public boolean admits(InetAddress peer) {
        if (peer == null) {
            return false;
        }
        byte[] peerBytes = normalize(peer);
        byte[] ownBytes = normalize(address);
        if (peerBytes.length != ownBytes.length) {
            // Different family entirely (an IPv6 peer against an IPv4 bind). Not on this
            // subnet by definition.
            return false;
        }
        return isLanRange(peerBytes) && sharesPrefix(peerBytes, ownBytes, prefixLength);
    }

    /**
     * Collapses an IPv4 mapped IPv6 address ({@code ::ffff:a.b.c.d}) to its four IPv4
     * bytes. Without this, a peer could present the same address in two shapes and only
     * one of them would be measured against the IPv4 private ranges.
     */
    private static byte[] normalize(InetAddress value) {
        byte[] bytes = value.getAddress();
        if (bytes.length != 16) {
            return bytes;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return bytes;
        }
        return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private static boolean sharesPrefix(byte[] a, byte[] b, int prefixBits) {
        int wholeBytes = prefixBits / 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        int remainder = prefixBits % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainder)) & 0xFF;
        return (a[wholeBytes] & mask) == (b[wholeBytes] & mask);
    }

    /**
     * RFC1918 (10/8, 172.16/12, 192.168/16), CGNAT (100.64/10, RFC6598), IPv4 link local
     * (169.254/16), IPv6 unique local (fc00::/7) and IPv6 link local (fe80::/10).
     *
     * <p>Everything else, including loopback and every globally routable address, is out.
     */
    static boolean isLanRange(byte[] bytes) {
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 10) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            if (first == 192 && second == 168) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            return first == 169 && second == 254;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
            int second = bytes[1] & 0xFF;
            return first == 0xFE && (second & 0xC0) == 0x80;
        }
        return false;
    }
}
