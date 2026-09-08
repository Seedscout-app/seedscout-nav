# Security Policy

Seedscout Nav is a one-person hobby project. This document says what the mod
actually does from a security point of view, so a player can decide whether
to install it, and so a researcher knows where to look and what to report.
It is a supplement to the four-bullet summary in the README's Security
section, not a replacement for it.

## Reporting a vulnerability

Email **support@seedscout.app**. This is the same address the README lists
for general support; there is no separate security inbox, because a second
address that nobody checks is worse than one that does.

Please include enough detail to reproduce the issue (mod version, Fabric and
Minecraft versions, the affected file or behavior) and, if it concerns the
pairing or link protocol, note whether you tested against `main` here or
against the protocol document in `shared/nav_protocol.md`. Do not open a
public GitHub issue for a suspected vulnerability until it is fixed.

This is unpaid work by a single developer. Expect an acknowledgement within
a few days, not a fixed SLA, and there is no bug bounty. A confirmed issue
gets fixed before the next release; I will credit you in the changelog if
you want that and not if you don't.

## Supported versions

The project is pre-release: there is one line of development, `main`, and no
published JAR yet. Only the current `main` branch is supported; there are no
older versions to backport a fix to.

## Threat model

### What the mod does

Seedscout Nav is a Fabric client mod. While the in-game pairing screen is
open, or while a session is linked, it runs a WebSocket listener bound to
one chosen LAN interface address (never `0.0.0.0`). The Seedscout Android
app connects to that listener, scans a QR code to authenticate, and once the
player confirms in game, receives the world seed and a live position stream
and can send back `route` and `clear` frames that the mod draws as particles
on the ground. There is no account, no server component, and no data leaves
the local network. See `link/LinkServer.java`, `link/LinkSession.java`, and
`shared/nav_protocol.md` for the normative protocol.

### Actors and trust boundaries

- **The player**, who owns the device running Minecraft and decides when to
  open the pairing screen and whether to confirm a connection.
- **A device on the same local network**, trusted with nothing by default.
  Being on the wifi is not being paired.
- **The paired app**, meaning whatever device the player scanned the QR code
  with and confirmed. Pairing is with a device that possessed the token and
  was accepted, not provably with a genuine, unmodified Seedscout app.
- **Someone who can see the QR code** without being on the network at all: a
  shoulder surfer, or a stream or screen-share viewer.

### What a device on the same LAN can do without the token

Very little. It can see that a port is open while the pairing screen is up
(the listener does not run otherwise), and it can attempt the WebSocket
handshake. Every attempt is checked by `protocol/HandshakeValidator.java`
before anything else happens:

- Any `Origin` header at all, including an empty one, is rejected outright.
  `dart:io`'s `WebSocket.connect` sends none; every browser does, so this
  alone keeps a page running in someone's browser on the LAN from opening
  the socket.
- The `Host` header must equal the bound `ip:port` literal exactly. This is
  the DNS rebinding defense: a browser can be made to resolve an
  attacker-controlled name to the mod's address, but it cannot be made to
  send a `Host` header of the raw address literal.
- No WebSocket subprotocol may be requested.
- The peer must be inside the bound interface's own subnet **and** privately
  addressed (private, CGNAT, or link-local ranges). Loopback is not on the
  allowlist; the phone is a different device, so loopback is not a
  meaningful boundary here, and admitting it would hand every local process
  a free bypass.
- The request target must be exactly `/?t=<token>`, one parameter, no path,
  no extras, base64url characters only (percent-escapes are refused, not
  decoded, so there is exactly one valid spelling of a token).
- The token itself must match, compared in constant time
  (`MessageDigest.isEqual`, verified by inspecting the compiled bytecode in
  `PairingTokenTest`), and must still be live.

Guessing is rate limited: five failures from one address exhaust that
address for the rest of the pairing window (`NavProtocol.MAX_FAILURES_PER_PEER
= 5`), and twenty failures total, from any mix of addresses, kill the window
outright and force the player to open a fresh QR code
(`NavProtocol.MAX_FAILURES_PER_WINDOW = 20`). A connection that never
completes a handshake at all (a raw TCP probe, a truncated header, a plain
HTTP request) is charged to the same two budgets, so a flood of cheap probes
runs into the same ceiling as a flood of wrong tokens. Pre-handshake
connection slots are separately capped at 8 concurrent globally and 3 per
address, each with a 10 second idle deadline, so holding sockets open
against the listener does not work either. Every rejection, whichever rule
caused it, is the same indistinguishable outcome; a peer cannot learn which
check it failed.

Once one peer's handshake is accepted, the listening socket is closed
immediately, before the player even sees the confirmation prompt. No second
device can attempt to pair against that token again, successfully or not.

### What a device on the same LAN cannot do

It cannot get a session without a live, correctly-presented token: a LAN
position alone grants nothing. It cannot make the mod treat a browser tab as
the app (the `Origin` check), rebind a hostname to reach the socket under a
different name (the `Host` check), or negotiate an alternate protocol on the
same port. It cannot brute force the 256-bit token in any meaningful sense;
the rate limits mean an attacker gets at most 20 guesses against the whole
window before it self-destructs, regardless of how many source addresses it
spreads across. It cannot bypass the in-game confirmation: even a
successful handshake only reaches the `AWAITING_CONFIRMATION` state, and the
player must explicitly accept before any world data is sent or any route
frame is processed.

### What the QR code exposes

The QR payload is a `seedscout://pair` URI carrying the mod's LAN address,
port, protocol version, and the pairing token in plain text
(`protocol/PairingToken.java`, `LinkServer#pairingUri`). Anyone who reads
it, by photographing the screen, watching over a shoulder, or capturing it
from a stream, has everything needed to attempt a connection, for as long as
the token stays live.

The token is deliberately short-lived and single-use, and the properties
below are enforced in code and asserted in `PairingTokenTest`, not just
documented: 32 bytes from `SecureRandom`, base64url encoded (43 characters);
expires at exactly 120 seconds from issuance (the boundary itself is
already dead, not the tick after); consumed permanently on the first
handshake that passes every other check, so a second presentation of the
correct value fails; and revoked immediately when the pairing screen closes,
independent of the 120 second clock. The token value never appears in the
mod's own `toString()`, logs, or crash output.

**Residual risk, stated plainly:** the token is a bearer secret with no
binding to a specific device. If someone else captures it and connects
before the player's own device does, they consume the single-use token: the
confirmation prompt names *their* IP address rather than the player's
phone's, and the player's own phone is then rejected (the window already
closed to further peers after the first accepted handshake). The defense
against this is the confirmation prompt itself, which shows the connecting
device's numeric IP address so the player can notice it is not their own
phone (`client/gui/PairScreen.java`) and decline, closing the window and
requiring a fresh QR code. This depends on the player actually checking the
address before pressing Accept; the mod has no way to know which IP belongs
to the player's own phone in advance, since there is no prior pairing to
compare against. Keep the pairing screen private, and treat an unexpected
device on the confirmation prompt as a reason to decline and rescan.

### What a malicious or compromised paired app can attempt

By design, a paired app can see the world seed and a live position stream
for the duration of the session, and can send `route` and `clear` frames
that the mod renders. That access is the feature, not a bug; it is granted
only after an explicit in-game confirmation.

Everything the app sends after pairing is still treated as hostile input.
`protocol/RouteSanitizer.java` enforces, in order: a frame size cap of 64
KiB checked before parsing (`NavProtocol.MAX_FRAME_BYTES`); strict JSON with
a maximum nesting depth of 8 and no duplicate keys; that the frame is a JSON
object of a known type (`route` or `clear`; anything else is silently
ignored, not treated as an error); for a route, between 2 and 512 integer
points (`NavProtocol.MIN_ROUTE_POINTS` / `MAX_ROUTE_POINTS`); every
coordinate within plus or minus 30,000,000, the Minecraft world border
itself (`NavProtocol.MAX_COORDINATE`); and a label passed through
`SafeLabel`, which strips formatting codes and control characters and
truncates to 64 code points before it is ever allowed near a render call.
A frame that fails any check is dropped and logged at debug level with a
reason code only, never the offending bytes.

An earlier version had a real flaw here: `client/render/RouteRenderer.java`
bounded each coordinate but not the distance between two consecutive
in-bounds points, so two points at opposite extremes of the world border
produced roughly 21 million particle samples for one segment, and a full
512-point route could reach on the order of 10^10 samples on the game
thread, freezing it. That is fixed: `clipToRenderDistance` throws away the
part of a segment beyond `MAX_RENDER_DISTANCE` (256 blocks) from the player
before any sampling happens, using a closed-form circle intersection that
costs the same regardless of how far apart the two points actually are.
Samples are then taken every `SAMPLE_SPACING` (2 blocks), capped per segment
at `MAX_SAMPLES_PER_SEGMENT` (257, derived from the render distance and
spacing) and per render pass at `MAX_SAMPLES_PER_PASS` (4096) regardless of
how many of a route's 512 points are hostile. `RouteRendererDosTest` asserts
this bound directly against the exact hostile route that used to hang the
thread.

A malicious app cannot execute a game command, run arbitrary code, read
files, or see anything the protocol does not explicitly carry: the message
set is a fixed allowlist of two inbound frame types, by protocol design
(`shared/nav_protocol.md` section 1), and the mod has no code path that
turns socket data into a command.

### Known limitations and accepted trade-offs

- **No transport encryption.** The link is a plain `ws://` WebSocket, not
  `wss://`. On an open or weakly secured wifi network, or against an
  attacker already positioned to intercept LAN traffic, the pairing token
  and the live position stream can be read off the wire by a third party
  without ever seeing the QR code. This mirrors the risk of most local
  pairing protocols on an untrusted network and is a real limitation, not a
  theoretical one; it is not currently mitigated beyond keeping the protocol
  LAN-only and the token short-lived.
- **The confirmation prompt trusts the player to look at it.** It names the
  connecting device's IP address, but has no way to know in advance which
  address is the player's own phone, so it cannot flag an unexpected device
  automatically. See the residual risk noted above.
- **A hostile paired app can still see live position and seed data for the
  session's duration.** There is no way to grant a session partial access;
  confirming pairing grants the full read side of the protocol.

### Out of scope

- The player's Mojang or Microsoft account, and Minecraft's own
  authentication. This mod never touches either.
- Any multiplayer server the player joins. The pairing listener is unrelated
  to, and does not affect, the player's connection to a server.
- The Seedscout Android app's own codebase and infrastructure; this policy
  covers what runs in this repository.
- Physical or network security the mod cannot influence: a compromised
  device, a malicious access point, or a network-level denial of service
  against the player's wifi.
- A pairing the player did not initiate but chose to confirm anyway. The
  confirmation prompt is the control; a player who accepts a device they do
  not recognize has bypassed it themselves.
- Anything requiring a relay, cloud hop, or off-LAN reachability. Version 1
  of the protocol is LAN-only by design (`shared/nav_protocol.md` section
  2), and a peer outside the bound interface's own private subnet is
  rejected regardless of how it got there.
