# 0.1.1

Released 2026-09-09

## Fixed

- The keybind category in Controls showed the raw translation key instead of Seedscout Nav

## Added

- Gallery screenshots for the store listings (no runtime change)

# 0.1.0

First published release of Seedscout Nav.

## Added

- QR-code based pairing with the Seedscout Android app over the local
  network, with an explicit in-game confirmation required before any device
  can pair
- Live sharing of the world seed and player position with the paired app
- In-game rendering of routes sent by the app as a full-bright, two-tone
  dust particle trail (dark to bright cyan-teal), sampled every 2 blocks and
  snapped to block centres
- Rebindable pairing keybind (default **N**), listed under the Seedscout Nav
  category in Mod Menu and Minecraft's own Controls options
- Automatic close of the network listener when a pairing screen or session
  ends, and automatic clearing of the route trail on unlink

## Compatibility

- Minecraft Java Edition 26.2, Fabric Loader 0.19.3 or later, Fabric API
  0.158.0+26.2 or later, Java 25 or later
- Client-side only; not loaded on a dedicated server

## Notes

- This is a 0.x release. The pairing/link protocol and route rendering are
  functional and covered by an automated test suite, but the mod has had
  only limited testing on real hardware. Expect the protocol and rendering
  details to keep changing before a 1.0.
