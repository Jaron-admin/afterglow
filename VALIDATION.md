# Validation — Afterglow 1.3.0

Checked on 2026-10-20 with JDK 17 and Android SDK Platform / Build-Tools 36.

- `tests/run.ps1`: all suites passed, including existing geometry/camera/lifecycle/browser
  regressions and new recording, detector, microphone, codec EOS and dual-track RTSP tests.
  Recording tests execute the real queue, worker, timeline and file index, substituting
  Android MediaMuxer and disk failure boundaries. They cover keyframe starts, common AV
  timestamps, pre/post-roll, merged events, configurable segment limits, quota/retention,
  locked/foreign/partial protection, slow-disk failure, full disks and read-only storage.
- Optional `tests/rtsp-audio/run-ffmpeg.ps1`: passed with real FFmpeg. Each transport
  decoded five seconds of synthetic H.264/AAC: TCP 63 video frames / 79 audio frames,
  UDP 62 / 79. Both decoded 320×240 video and 16000 Hz mono audio, producing 160000 bytes
  of nonzero PCM; FFmpeg warning/error logs were empty. This uses the production
  RTSP server and Annex-B splitter. Joining a live GOP explains fewer than 75 video frames.
- Gradle `assembleDebug lintDebug`: successful, 0 errors and 99 warnings.
- `build-apk.ps1`: successful; version 1.3.0 / code 5, minSdk 19, targetSdk 33,
  package `com.oldphone.ipcam`. v1/v2/v3 signatures verified; certificate remains
  `dd4508eb3d4c3698c4be1c828b1171c250f0820cbdc5caf2cb6a8e9198f7c239`.
- Independent reviews identified full-disk management being gated on a write probe,
  event-marker ownership across segments, and FFmpeg's explicit `RTP/AVP/UDP` transport
  header. Regression tests cover these fixes. No signing material or real recordings
  are included in the repository.

No physical camera or microphone was opened during these tests. Native Android
MediaMuxer output, the built-in Android player, real sensor/microphone latency and
long-term clock drift are not established by the JVM or FFmpeg transport tests.

## Device acceptance — 1.3.0

1. Upgrade with the existing signature; verify recording and microphone remain opt-in.
2. Record continuous MP4 with microphone off/on, stop normally, and play/export completed
   clips; confirm upright cropped output and audible, synchronized sound.
3. Test a short custom segment, a long segment, size limit, total quota, reserve,
   retention and stop/loop policy. Locked clips must survive automatic cleanup.
4. Use event mode with pre-roll zero and nonzero, repeated motion/sound and custom
   post-roll. Check trigger labels in the correct segment, including segment boundaries.
5. Remove/reinsert or fill the selected SD card. Recording must pause visibly, network
   streams must remain available, and completed files must remain readable/exportable.
   Read-only media must be readable but reject write/delete.
6. Toggle microphone and recording while running, rotate/lock/calibrate, and rapidly
   stop/restart. Confirm old sessions release resources and never write into new clips.
7. Verify long-running screen-off behavior and device-specific background restrictions
   on the intended Android 4.4 and newer phones. Power loss can lose the active segment.

# Previous validation — Afterglow 1.2.1

Checked on 2026-10-20 with JDK 17 and Android SDK Platform / Build-Tools 36.

- `tests/run.ps1`: successful. New output-menu tests verify exact aspect-ratio filtering, deduplication, source-independent 16:9 presets, portrait transposition, pixel-count-based selection, new-install defaults, and preservation of saved/custom/legacy settings. Existing geometry, camera, lifecycle, protocol and browser suites also pass.
- Gradle `assembleDebug lintDebug`: successful, 0 errors and 88 warnings.
- `build-apk.ps1`: successful. `Afterglow-1.2.1.apk` is version code 4, minSdk 19, targetSdk 33; v1/v2/v3 signatures verified.
- Layout XML parses with all previous view IDs present and no duplicate IDs. Review covered pane reparenting on configuration changes, preview sizing, Spinner state and ratio selection; no blocking findings remain.
- No physical-device installation or UI rendering test was performed. Extremely short landscape screens and larger font settings may require scrolling the left preview pane as well as the right settings pane.

Device checks still needed: rotate between all four directions while streaming,
confirm left preview/right settings in landscape and a single column in portrait,
preserve an in-progress custom-size edit during rotation, check exact ratio-filtered
choices, and verify crop masks without a border. Upgrade preserves an explicit
existing output size; 1280×720 is the new-install default.

# Previous validation — Afterglow 1.2.0

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
