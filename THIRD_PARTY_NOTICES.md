# Third-Party Notices

Seedscout Nav's own code is open source under the Apache License 2.0 (see LICENSE). This file lists third-party components and the licenses under which they are used.

## Fabric Loader

- **Component**: Fabric Loader
- **License**: Apache License 2.0
- **Upstream**: `fabricmc/fabric-loader` (https://github.com/FabricMC/fabric-loader)
- **Usage**: Runtime dependency for mod loading and entrypoint discovery
- **License verification**: DONE. Confirmed Apache-2.0 at upstream repository.

## Fabric API

- **Component**: Fabric API
- **License**: Apache License 2.0
- **Upstream**: `fabricmc/fabric` (https://github.com/FabricMC/fabric)
- **Version**: 0.158.0+26.2 or later
- **Usage**: Provides common utilities and compatibility layer for Minecraft modding
- **License verification**: DONE. Confirmed Apache-2.0 at upstream repository.

## QR Code Generator

This project vendors NO third-party QR code library. `client/qr/QrCode.java` is original
project code, written directly from the public ISO/IEC 18004 algorithm, not a copy or
transcription of any existing library (in particular, not of Nayuki's QR-Code-generator
project, which was the library originally proposed for this slot but was never fetched or
transcribed for the reasons documented in that file's header). It is licensed under this
project's own Apache License 2.0, the same as the rest of the mod, and has no separate
upstream, license, or attribution entry to track here.

## Native Binaries

This project bundles NO native binaries. The bundled native binaries rule (see the parent Seedline project's CLAUDE.md) does not apply to this repository.

## Minecraft

Minecraft is not vendored or redistributed with this mod. Players must own a copy of Minecraft Java Edition and use the Fabric Loader to load this mod into a legitimate client installation.
