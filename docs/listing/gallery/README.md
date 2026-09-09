# Gallery shot list

Screenshots to capture in-game before the Modrinth and CurseForge listings
go public. None of these exist yet; this file only records what to take and
where they get used. Save the finished PNGs into this directory once
captured (they are not committed here as placeholders).

Suggested size: 1920x1080 (16:9), PNG, under 5 MB each. Modrinth's gallery
accepts smaller images but 1920x1080 reads well as both a gallery thumbnail
and a full-size view; keep the game's GUI scale at a normal size so text in
the shot stays legible.

## Shots needed

1. **The route trail** - a world screenshot showing the cyan-teal dust
   particle trail leading across terrain toward a target, ideally in a
   setting (cave, ravine, or dusk lighting) where the full-bright particles
   read clearly against the background. This is the mod's headline feature
   and should be the first/cover image in both listings.

2. **The target marker** - a close-up of where the route trail ends at or
   near a structure the Seedscout app found, showing the hand-off between
   "follow the trail" and "you have arrived."

3. **The seed hand-off (QR pairing screen)** - the in-game pairing screen
   (opened with the **N** key) showing the QR code, paired with a second
   frame or a composite showing the Seedscout Android app mid-scan. This is
   the shot that explains how the mod and the app connect to someone who has
   never seen either product.

## Optional / nice to have

4. A shot of the rebind entry in Minecraft's Controls options or Mod Menu,
   for players wondering how to change the pairing key.
5. A shot of the in-game confirmation prompt shown after a scan, to make the
   "pairing always requires explicit confirmation" security claim visible,
   not just documented.

## Usage

- Modrinth: upload all captured images to the project's Gallery tab; set
  shot 1 (the route trail) as the featured image.
- CurseForge: attach the same images to the project's media gallery when
  creating it (see the Publishing section of the repository README for the
  human steps).

## Captured (2026-09-09, Minecraft 26.2)

All five shots below are real in-game captures, not mockups. There is no
phone on this machine, so the app side of pairing was simulated with a
short-lived script that speaks the same `shared/nav_protocol.md` WebSocket
handshake a real phone would (see the note on shot 3 for exactly what that
does and does not stand in for).

- `01-route-trail.png` - the cyan-teal dust trail crossing daylight
  grassland toward a ravine cut, with sheep, a cow, ocean and savanna trees
  in frame. World seed `9223372036854775807` (the seed named in
  `fixtures/wire/world.json`), Creative, cheats on, `/time set day` and
  `/weather clear`.
- `02-target-marker.png` - a close-up of the trail's particles mid-transition
  (dark to bright cyan-teal), showing the dust up close partway along the
  route. The literal final waypoint sits on a much taller, steeper stretch of
  terrain than the rest of the route generates near, so this crop favors a
  clean, unobstructed read of the particle effect itself over the exact last
  block.
- `03-seed-handoff-qr.png` - the in-game pairing screen (**N** key) showing
  the live `seedscout://pair` QR code. The gallery brief also asks for "a
  second frame or a composite showing the Seedscout Android app mid-scan":
  there is no phone or emulator on this machine and touching the Android
  device/emulator used by another session was out of bounds for this task,
  so that half of the shot is not included here rather than staged with a
  fake screenshot. The QR code half is completely real: it was decoded with
  `tools/decode_qr.swift` (the same Vision-framework oracle the mod's own
  round-trip test uses) and the payload was used to open a real WebSocket
  handshake against the running mod, exactly as a phone would.
- `04-keybind-entry.png` - the "Open pairing screen" row in Minecraft's own
  Controls -> Key Binds screen, bound to **N**. In the course of finding this
  screenshot we noticed the category header above it renders as the raw,
  untranslated string `key.category.seedscout-nav.pair` instead of "Seedscout
  Nav" (the lang file defines `key.categories.seedscout-nav.pair`, plural
  "categories" - a one-character mismatch against whatever key Minecraft
  reads for a category label). Filed here as an observation; not fixed by
  this capture pass.
- `05-scan-confirmation.png` - the in-game Accept/Decline prompt shown once a
  peer's handshake is accepted, before any route can be drawn. This is real
  too: it appears the instant the simulated "app" connects with a valid
  token from the QR code above, which is the same trigger a real phone's
  scan produces, so this is the closest truthful stand-in for "the
  confirmation prompt after a real QR scan" without a phone in the loop.
