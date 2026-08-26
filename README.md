# Seedscout Nav

A Fabric Loader mod for Minecraft Java Edition that pairs with the Seedscout Android app over the local network. Once paired, the app reads your world seed and player position, and can draw a route on the ground to guide you toward features.

## Features

- QR-code based pairing with the Seedscout Android app
- Share your world seed and player position with the app in real time
- Receive and render routes from the app on your world
- Entirely client-side; no account, no cloud

## Installation

1. **Install Fabric Loader** for Minecraft 1.26.2, following the [official Fabric documentation](https://fabricmc.net/use/).
2. **Install Fabric API** 0.158.0+26.2 or later; place the JAR in your `mods` folder.
3. **Install this mod**: place the Seedscout Nav JAR in your `mods` folder.
4. **Start Minecraft** and launch a world.

## Usage

1. Press the pairing key (configurable in Mod Menu or within the game settings) to open the pairing screen.
2. Use the Seedscout Android app to scan the displayed QR code.
3. Confirm the pairing in the in-game prompt.
4. The app can now see your seed and position, and will draw routes on your world.

Press the key again to close the pairing screen or end an active session.

## Security

- The mod opens a WebSocket listener on your local network only while pairing or an active session is running. It closes automatically when the session ends.
- The QR code is a short-lived secret (120 seconds) that anyone who sees it can use to pair. Keep the pairing screen private.
- Pairing always requires an explicit in-game confirmation from the player; scanning the QR code alone is not sufficient.
- The pairing token is never written to disk or logs.

## License

This project is licensed under the Apache License 2.0, chosen deliberately to ensure the mod remains free and open source. This differs from the Seedscout app's proprietary license because a Minecraft mod must be free and open to comply with Mojang's guidelines and to be credible to the community.

See [LICENSE](LICENSE) for the full license text.

## Disclaimer

This mod is not affiliated with, endorsed by, or associated with Mojang Studios, Microsoft, or the Minecraft brand. All Minecraft trademarks and assets are the property of Mojang Studios and Microsoft.

## Support

For issues, questions, or suggestions, contact support@seedscout.app.

## Building

Requires:
- JDK 25 or later (with `$JAVA_HOME/bin` first on PATH)
- Gradle 9.5.1 (included in the wrapper)

To build:

```bash
export JAVA_HOME=/usr/local/opt/openjdk@25
./gradlew clean build
```

To run the development client:

```bash
./gradlew runClient
```

All compiler flags are pinned in `shared/cflags.txt` to ensure reproducible builds. See `gradle.properties` for the exact toolchain versions.
