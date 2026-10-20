# Validation — Afterglow 1.2.0

Checked on 2026-10-20 with JDK 17 and Android SDK Platform / Build-Tools 36.

- `tests/run.ps1`: successful. Includes four sensor/device rotations, fixed output dimensions, centered crop boundaries, independent full-frame vs fused NV21 transforms, V/U ordering, sensor hysteresis/unknown poses/stop-start cancellation, Camera2 plane strides and buffer types, source size choice, exclusive camera reservation with timeout/real-release handoff, and the existing lifecycle, codec discovery, TCP protocol and browser suites.
- Gradle `assembleDebug lintDebug`: successful, 0 errors and 82 warnings. API21 backends have explicit version boundaries; camera permission rejection is handled explicitly. No lint baseline or blanket suppression was added.
- `build-apk.ps1`: successful. `Afterglow-1.2.0.apk` is version code 3, package `com.oldphone.ipcam`, minSdk 19, targetSdk 33, label 余光. v1/v2/v3 signatures verified, with the same certificate as 1.1.0.
- Independent review: direction-monitor resume, Camera2 actual `onClosed` handoff, startup-disconnect races and pending Camera1 cancellation were fixed and re-reviewed with no remaining blockers.

Local Gradle verification reused the ignored official-artifact Maven cache
described in the 1.1.0 record below; committed repositories are unchanged.

The tests run real pixel transforms, selection/reservation code and network
protocols. They do **not** execute Android's camera HAL or prove physical-device
UI rendering, lens availability, hardware encoder behavior, actual sensor
metadata, or long-running background operation. No APK was installed on a
physical phone during this revision.

## Device acceptance — 1.2.0

1. Enable the system rotation lock, then hold the phone in all four directions.
   Confirm the app UI follows; check upright text in both MJPEG and RTSP output.
2. Choose 1280×720 while holding the phone vertically. Confirm the full portrait
   input remains visible locally, the top/bottom are shaded, and the remote stream
   contains only the unshaded crop with exact 1280×720 dimensions.
3. Rotate to landscape and upside down; confirm output dimensions and connections
   remain fixed and the crop changes coherently with the preview.
4. Lock direction, move the phone, leave/reopen the app, then unlock. Confirm both
   UI orientation and correction pose remain fixed until unlocking.
5. Calibrate each available front/back lens separately, return to another lens,
   then restore automatic calibration. Confirm offsets do not leak between lenses.
6. Test portrait and custom output sizes; reject odd/oversized inputs without
   changing the saved configuration. Unsupported H.264 configurations must show
   an error, without silently dropping RTSP.
7. Rapidly switch lenses and stop/start. Confirm old cameras are released, late
   callbacks cannot replace the current stream, and a missing/occupied selected
   camera reports an error instead of silently using a different camera.
8. Repeat on the intended Android 4.4 and newer target phones; verify physical
   multi-camera exposure and device-specific screen-off/background policies.

# Previous validation — Afterglow 1.1.0

Checked on 2026-10-20 with JDK 17 and Android SDK Platform / Build-Tools 36.

- Native SDK build: `build-apk.ps1`, successful; APK signatures v1, v2 and v3 verified. Package ID `com.oldphone.ipcam`, version code 2, minSdk 19, targetSdk 33. Signing certificate matches the previous 1.0.0 APK.
- Gradle: `assembleDebug lintDebug`, successful. Local Java TLS downloads failed for three official Google Maven artifacts; the files were downloaded from the same official URLs, checksum-verified, and supplied through an ignored local repository for this check. The committed Gradle configuration uses the normal official repositories.
- Regression runner: `tests/run.ps1`, successful. Covers lifecycle request coalescing, stop/start/failure cleanup, codec discovery on simulated API 19 and 21, actual HTTP/MJPEG sockets, invalid-auth fail-closed behavior, RTSP handshake timeout and 40 restart cycles, and browser reconnect/stale-image behavior.
- Independent review: authentication validation, RTSP shutdown registration and API 19 codec enumeration findings were corrected and re-reviewed with no unresolved blocking findings.

Lint has 0 errors and 71 warnings. Remaining warnings include hardcoded left/right layout alignment, unused resources, foreground-service wake-lock duration, and the intentionally retained target SDK. No blanket lint suppression or baseline was added.

Physical-device UI rendering, hardware camera/codec compatibility, MediaPlayer RTSP playback and long-duration background streaming have not been tested in this change. The JVM tests and APK build do not substitute for those checks.

## Device acceptance checks

1. Install over 1.0.0 with the same signing key; confirm existing settings remain.
2. Start streaming, change back/front camera and all four rotations. Preview and remote output must reflect the latest selection, with swapped dimensions for 90°/270°.
3. Rapidly change controls, then stop. Confirm no stale preview, no unexpected restart, and no retained camera use.
4. Reconnect from a browser and built-in viewer after settings changes. Check RTSP separately with a third-party player.
5. Enable HTTP authentication, try a blank username, and confirm anonymous snapshot/video requests stay rejected.
6. Verify screen-off/background behavior on the intended phone and its battery-management settings.
