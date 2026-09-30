# NudeBlur Guard

Android screen filter using MediaProjection + local ONNX inference.

## Privacy

- The installed APK does **not** request `INTERNET`.
- Screen frames are processed locally on the device.
- The ONNX model is bundled into the APK at build time.
- The app copies the bundled model into private app storage and verifies its SHA-256.
- No runtime model download, telemetry, upload, or cloud inference is implemented.

## Stability

Detection masks are prepared off the UI thread. The overlay only draws already prepared
mosaic patches, and a successful detection is held briefly across missed detector frames
to reduce flicker.

The GitHub Actions workflow downloads the pinned model **during the build only** and
verifies the expected SHA-256 before packaging the APK.
