# Seedscout Nav link protocol, version 1

The wire contract between the Seedscout app and the Seedscout Nav mod running
inside Minecraft. The mod draws a route on the ground; the app decides where
that route goes.

**Scope: the Java link only.** Bedrock does not use this protocol. Spike B
(2026-08-16/17, Bedrock 1.26.44.3) established that a vanilla Bedrock client
cannot report player position (`/querytarget` is gone), cannot subscribe to
events without encryption, and cannot hold a stable unencrypted session, so
Bedrock navigation runs entirely inside a behavior pack with a pasted
`/scriptevent` destination. See the Phase 3 section of the nav plan.

This document is normative. Where it and any implementation disagree, this
document is right and the implementation has a bug. It is authored here and
mirrored into the public mod repository, which carries no other Seedline
contract: the mod knows nothing about seeds, scoring, indexes, or the vault.

## 1. Design rules

1. **The mod is a renderer and a reporter, nothing else.** It draws what it is
   told and reports where the player is. Every decision that needs seed
   knowledge happens in the app. This is what lets the mod be open source and
   free while the corpus stays private.
2. **The message set is an allowlist, not a command channel.** Two messages
   flow to the mod, ever: `route` and `clear`. The mod MUST NOT gain a code
   path that executes text from the socket as a game command. Anything that
   would need a third verb needs a protocol version bump and a fresh look at
   the threat model.
3. **The mod listens, the app connects.** A PC has a stable LAN address and no
   background socket restrictions; a phone has neither. The mod is therefore
   the server and the app is the client.
4. **A route is a polyline, and a straight line is a two point polyline.** The
   mod never learns whether the app planned around a mountain or gave up and
   drew a straight line. This is deliberate: it lets the router ship after the
   mod without the mod changing at all.
5. **Vertical belongs to the mod.** Waypoints are horizontal only. The app's
   heightmap is about 3 blocks off in the vertical (measured, see
   `packages/seedline_route`), so the mod snaps to real ground in loaded
   chunks rather than trusting a height off the wire.

## 2. Transport

WebSocket over the local network. The mod binds to the LAN interface on an
ephemeral port. Text frames, one JSON object per frame, UTF-8.

Off-LAN operation is out of scope for version 1. There is no relay, no cloud
hop, and no account: pairing is two devices on the same wifi and nothing else.

## 3. Pairing

The mod renders a QR code on an in-game screen. The payload is a single URI:

```
seedscout://pair?h=<lan-ip>&p=<port>&t=<token>&v=1
```

| Field | Meaning |
|---|---|
| `h` | The mod's LAN address, as dotted quad or bracketed IPv6 |
| `p` | TCP port the mod is listening on |
| `t` | Base64url pairing token, 32 bytes from a cryptographic RNG |
| `v` | Protocol version, `1` for this document |

Rules:

1. The token is generated per pairing session and lives only in the QR code
   and in memory. It MUST NOT be written to disk or to a log.
2. The token expires when the pairing screen closes, on first successful use,
   or after 120 seconds, whichever comes first.
3. A connection presenting a valid token still requires an explicit in-game
   confirmation from the player before any route is accepted. Scanning is not
   consent; confirming is.
4. A failed token MUST close the connection without indicating whether the
   token was wrong or expired, and MUST be rate limited.

### 3.1 Presenting the token

The token is presented as a query parameter on the WebSocket connect URL:

```
ws://<h>:<p>/?t=<token>
```

where `h` and `p` are the values read from the pairing URI. The mod validates
`t` during the HTTP upgrade and rejects a bad or expired token per rule 4
above, so a rejected client never reaches the frame layer at all.

Rationale, and the constraint it creates: the token is deliberately NOT sent as
a frame. Section 1 rule 2 makes the app-to-mod message set an allowlist of
exactly `route` and `clear`, and an auth frame would be a third verb. Carrying
the token in the handshake keeps that allowlist intact.

Because the token now appears in the request line, rule 1's "MUST NOT be
written to disk or to a log" binds the transport as well as the application:

1. The mod MUST NOT log HTTP request lines, request URIs, or query strings for
   this listener, at any log level, including on the rejection path. A
   rejection may be logged, but only as the fact of a rejection.
2. The app MUST NOT log the connect URL. Where a connection failure is
   reported, the token is elided.
3. Neither side may include the connect URL in a crash report, telemetry
   payload, or diagnostic bundle.

Off-LAN operation being out of scope (section 2) is what makes a query
parameter acceptable here: there is no proxy, no relay and no gateway between
the two devices that could log the URL on their behalf. If a future version
introduces any intermediary, this mechanism MUST be revisited rather than
inherited, and that revision is a protocol version bump.

## 4. Messages

Every frame is a JSON object with a `type` field. Unknown types MUST be
ignored rather than treated as an error, so a newer app can talk to an older
mod without breaking the link.

### 4.1 Mod to app

`world`, sent immediately after the player confirms pairing, and resent if the
loaded save changes under a live link. A dimension change is NOT such a change
and does not resend: the save identity the mod compares is deliberately
dimension-free, so walking through a nether portal leaves it equal. The app
must therefore accept more than one `world` frame per session, and treat the
newest as authoritative. Where the mod cannot determine whether the save
changed, it resends: a redundant frame carrying an unchanged seed is harmless,
whereas a missed one leaves the app showing the previous world indefinitely.

```json
{"type":"world","edition":"java","mc":"1.21.11","dimension":"overworld",
 "seed":"-4172144997902289642","spawnX":112,"spawnZ":-208}
```

`seed` is a DECIMAL STRING, never a JSON number. Seeds exceed 2^53 and would
be silently mangled by any JSON parser that reads them as doubles. This is the
same rule `app/lib/data/saved_repository.dart` already follows for storage.

`seed` is null when the client genuinely does not know it, which is every
multiplayer server. The app MUST treat a null seed as "linked but no map"
rather than guessing.

`pos`, sent while linked, throttled to at most 5 Hz:

```json
{"type":"pos","x":134.5,"y":71.0,"z":-902.25,"yaw":47.5,"dimension":"overworld"}
```

`unlink`, sent when the player ends the session or changes world:

```json
{"type":"unlink","reason":"player_left_world"}
```

### 4.2 App to mod

`route`, replacing any route currently drawn:

```json
{"type":"route","id":7,"dimension":"overworld","label":"Woodland Mansion",
 "points":[[0,0],[240,96],[512,96]]}
```

| Field | Rule |
|---|---|
| `id` | Monotonic per session. The mod draws the highest id it has seen and ignores late arrivals from an earlier plan. |
| `dimension` | The mod MUST ignore a route whose dimension is not the one the player is currently in, rather than drawing it in the wrong world. |
| `label` | Display text only. Never parsed, never used to look anything up. |
| `points` | Array of `[x, z]` integer block pairs, first is the origin, last is the destination. At least 2, at most 512. |

`clear`, removing the drawn route:

```json
{"type":"clear"}
```

## 5. What the mod does with a route

1. Snap each waypoint to the real surface height in loaded chunks. Waypoints
   outside loaded chunks are drawn at the interpolated height of the nearest
   snapped neighbours and re-snapped as chunks load.
2. Draw the polyline as a ground ribbon, plus an off-screen edge indicator and
   a distance readout to the destination.
3. Never move, steer, or otherwise control the player. The mod renders; the
   player walks. This boundary is what keeps Nav a navigation aid rather than
   an automation tool.

## 6. Honest labeling obligations

These are protocol-level because the app cannot claim more than the link can
deliver:

- The Nether has no usable offline heightmap, so a Nether route is a two point
  straight line and the app MUST label it as a heading, not a route.
- Underground destinations are routed to the surface point above them. The app
  MUST show the handoff plainly rather than implying it can route through
  caves.
- A long-range route is planned on approximate terrain, about 3 blocks off in
  the vertical and reliable to about 98% on land against water clear of the
  shoreline. Shoreline calls are explicitly not guaranteed.
