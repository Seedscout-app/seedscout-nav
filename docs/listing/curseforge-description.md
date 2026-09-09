Seedscout Nav

Seedscout Nav is a Fabric client mod for Minecraft Java Edition that pairs
with the Seedscout Android app over your local network. Seedscout analyzes a
world seed and finds structures and features; this mod lets it show you the
way to them in-game.

Why a companion mod

The Seedscout app already tells you what a seed contains and where. This mod
closes the loop: once paired, the app hands the mod your world seed and a
live position stream, and can draw a route on the ground that leads from
where you are standing to a target the app found. No coordinates to
copy-paste, no alt-tabbing to check a map.

Features

- QR-code based pairing with the Seedscout Android app
- Shares your world seed and player position with the app in real time
- Renders routes sent by the app as a full-bright particle trail (dark to
  bright cyan-teal), sampled every 2 blocks along the ground
- Entirely client-side: no account, no cloud, nothing leaves your local
  network

Installation

1. Install Fabric Loader for Minecraft 26.2 (see fabricmc.net/use).
2. Install Fabric API 0.158.0+26.2 or later in your mods folder.
3. Install this mod's JAR in the same mods folder.
4. Start Minecraft and launch a world.
5. Install the Seedscout app on an Android device on the same local
   network (Google Play: search "Seedscout" or visit
   play.google.com/store/apps/details?id=app.seedscout).

Usage

1. In-game, press N (the default pairing key, rebindable through Mod Menu
   or Minecraft's Controls options) to open the pairing screen.
2. Scan the displayed QR code with the Seedscout app.
3. Confirm the pairing in the in-game prompt.
4. The app can now see your seed and position, and will draw routes on your
   world. Press the pairing key again to close the pairing screen or end an
   active session.

Compatibility

Minecraft: Java Edition 26.2 only, no Bedrock support
Mod loader: Fabric Loader 0.19.3 or later, not Forge
Fabric API: 0.158.0+26.2 or later
Java: 25 or later
Side: Client-only. This mod declares "environment": "client" in its
  fabric.mod.json, so Fabric Loader will refuse to load it on a dedicated
  server. There is nothing to install server-side.
Companion app: Seedscout for Android, same local network

Screenshots

Gallery images are pending capture; the route trail, the target marker, and
the QR pairing screen will be added before this listing is public.

Links

Companion app on Google Play: play.google.com/store/apps/details?id=app.seedscout
Companion app site: seedscout.app
Source code: github.com/Seedscout-app/seedscout-nav
Issues and support: github.com/Seedscout-app/seedscout-nav/issues

Security

The mod opens a WebSocket listener on your local network only while pairing
or an active session is running, closes it automatically when the session
ends, and requires an explicit in-game confirmation before any device can
pair. See SECURITY.md in the source repository for the full threat model.

License

Apache License 2.0. See LICENSE in the source repository.

Disclaimer

This mod is not affiliated with, endorsed by, or associated with Mojang
Studios, Microsoft, or the Minecraft brand.
