package app.seedscout.nav.link;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses the ONE real LAN interface the pairing listener binds to.
 *
 * <p>Section 2 says the mod "binds to the LAN interface on an ephemeral port" without
 * saying how to pick it. A naive pick (first interface, or first with any IPv4 address) is
 * a known real-world failure for this exact feature: a VPN {@code utun} adapter or a
 * Docker/bridge virtual adapter often outranks the real wifi adapter in enumeration order
 * or address count, and a QR code pointing at a VPN tunnel address is unreachable from the
 * phone that just scanned it.
 *
 * <p>This selector therefore requires, in order:
 *
 * <ol>
 *   <li>The interface is up, not loopback, not point-to-point (VPN tunnels are almost
 *       always point-to-point interfaces) and not {@link NetworkInterface#isVirtual()}.</li>
 *   <li>Its name and display name do not match a known virtual/tunnel adapter pattern
 *       (docker, veth, bridges, {@code utun}/{@code tun}/{@code tap}, vmnet/vboxnet,
 *       zerotier, wireguard, tailscale, ppp). This catches adapters the JDK does not
 *       itself flag as virtual or point-to-point, which in practice is most of them.</li>
 *   <li>It carries an IPv4 address that is site-local (the RFC1918 ranges), which is what
 *       an actual home or venue wifi network hands out.</li>
 * </ol>
 *
 * <p>The address returned is deliberately narrower than
 * {@link app.seedscout.nav.protocol.BoundInterface#isLanRange}, which also allows CGNAT
 * and link-local ranges for the purpose of judging an already-connected PEER. Choosing
 * which interface to BIND is a different question: a genuine site-local address is the
 * one a real wifi/ethernet adapter hands out, so requiring it here is what steers the
 * choice away from a CGNAT carrier adapter or a link-local fallback address on an
 * interface that never got a real lease.
 */
final class InterfaceSelector {

    private InterfaceSelector() {
    }

    /** Substrings (checked against the lower-cased interface name and display name). */
    private static final List<String> VIRTUAL_NAME_FRAGMENTS = List.of(
            "docker", "veth", "br-", "bridge", "utun", "tun", "tap", "vmnet", "vboxnet",
            "zerotier", "zt", "wireguard", "wg", "tailscale", "ppp", "vnic", "vmware");

    /** A candidate LAN address to bind to, before the ephemeral port is known. */
    record Candidate(InetAddress address, int prefixLength) {
    }

    static Optional<Candidate> choose() {
        List<NetworkInterface> interfaces;
        try {
            interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            return Optional.empty();
        }
        for (NetworkInterface nic : interfaces) {
            Optional<Candidate> candidate = fromInterface(nic);
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private static Optional<Candidate> fromInterface(NetworkInterface nic) {
        try {
            if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint() || nic.isVirtual()) {
                return Optional.empty();
            }
            if (looksVirtual(nic)) {
                return Optional.empty();
            }
            for (InterfaceAddress ifAddr : nic.getInterfaceAddresses()) {
                InetAddress address = ifAddr.getAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    return Optional.of(new Candidate(address, ifAddr.getNetworkPrefixLength()));
                }
            }
            return Optional.empty();
        } catch (SocketException e) {
            // A single interface that fails to report its flags must not abort the whole
            // scan; move on to the next one.
            return Optional.empty();
        }
    }

    private static boolean looksVirtual(NetworkInterface nic) {
        String name = lower(nic.getName());
        String display = lower(nic.getDisplayName());
        for (String fragment : VIRTUAL_NAME_FRAGMENTS) {
            if (name.contains(fragment) || display.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
