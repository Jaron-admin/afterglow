# Validation — Afterglow 1.1.0

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