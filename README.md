# MeshCore Coverage Mapper

Android companion app for mapping MeshCore mesh network coverage via BLE-connected nodes.

![Screenshot placeholder](docs/screenshot.png)

## Build

### Requirements

- Android Studio (Hedgehog or newer)
- JDK 17
- Android NDK (for H3 hex indexing native library)
- Android SDK 35

### Steps

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Build and run on a device with Bluetooth LE support (emulators won't work for BLE)

For release builds, set these environment variables:

```
RELEASE_STORE_FILE=/path/to/your.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## How it works

1. **BLE Connection** -- The app connects to a MeshCore node (companion device) over Bluetooth Low Energy using the Nordic UART Service (NUS).
2. **GPS Tracking** -- While connected, the app continuously tracks your location using Android's fused location provider.
3. **Hex Mapping** -- Each GPS position is converted to an H3 hexagonal index (resolution 10, ~15m edge length) using the H3 library built from C source via NDK.
4. **Coverage TX** -- The app periodically sends coverage test messages through the mesh network and listens for responses from nearby repeaters.
5. **Upload** -- Collected coverage samples (hex index, signal strength, repeater info) are uploaded to the [mapme.sh](https://mapme.sh) backend for visualization on the public coverage map.
6. **Verification** -- Device identity is cryptographically verified using Ed25519 challenge-response signing to prevent spoofed data.

## Privacy Modes

The app supports three privacy modes that control when coverage data is uploaded:

| Mode | Label | Behavior |
|------|-------|----------|
| `live` | **Live** | Instant upload with adaptive TX timing |
| `normal` | **Normal** | Data uploaded with a 3-hour delay |
| `anonym` | **Ghost** | Data uploaded with a 24-hour delay |

All modes collect the same data. The delay modes batch uploads so your movement patterns are harder to correlate with real-time location.

## Android Auto

The app includes Android Auto support for hands-free coverage mapping while driving.

## License

MIT License. See [LICENSE](LICENSE).

## Links

- [mapme.sh](https://mapme.sh) -- Live coverage map
