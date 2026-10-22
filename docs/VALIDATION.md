# Validation — v2.2 stream-first home

Checked on 2026-10-22. `TriggerPolicyTest`, `LightPolicyTest`, `StreamPresetsTest` and `TimelineSustainTest` pass with the existing recording suite. Home Assistant `unittest` stayed at 29. `lintDebug` completed with 0 errors.

Roborazzi renders every handoff state at 360dp, light and dark: `home-idle`, `home-streaming`, `home-recording`, `home-stream-rec`, `home-paused`, `home-res`, `settings`, `settings-light`, `settings-recording`, `settings-advanced`, `list`, `list-empty`, `list-select`, `stream`, `stream-push`. These are test renders, not device captures. The pages are the full scroll content, so tall screens are longer than the 800dp viewport.

# Validation — shorter home screen and one sound switch

Checked on 2026-10-21 with JDK 17. The JVM suites in `tests/run.ps1` passed, including `RecordingAudioTest` (one microphone switch, the silent-file upgrade, and the disabled sound trigger). Home Assistant `unittest` stayed at 29. `lintDebug` completed with 0 errors and 129 warnings.

Home now shows the preview, 「录制声音」, status, the camera, and three entry cards. Quality, output, addresses and keep-alive are on 「画面与推流」. 「锁定当前方向」 is a switch and still uses `direction_lock`. The file-audio checkbox is gone. `recording_audio` is still read and is rewritten only when the user moves 「录制声音」, so an older silent recording stays silent until that gesture.

The new screenshots are the same Robolectric 4.17 native + Roborazzi path: main, 画面与推流, and recording settings, light and dark.

# Validation — recording list, settings and color

Checked on 2026-10-21 with JDK 17. The same JVM suites as `tests/run.ps1` passed, including `RecordingLibraryTest` (reason chips, day folders, export file names). Home Assistant `unittest` stayed at 29. `lintDebug` completed with 0 errors.

The interface follows the system light and dark theme. Warm orange `#C2410C` is the filled button color with white text. Small orange text uses `#9A3412` on the light background and `#FDBA74` on the dark background. Cyan is the second accent. Reason chips are orange, cyan and indigo so the three reasons stay distinct.

No phone was used. API 33 and API 30 x86_64 emulators were installed. `emulator -accel-check` reported KVM usable, and the API 30 image was cold-booted with `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -wipe-data` and `hw.bluetooth=no`. Neither guest printed a kernel log or became an adb device. The screenshots on the pull request are from `RecordingUiShots` (Robolectric 4.17 native graphics + Roborazzi), not from a device framebuffer.

# Validation — HA audio and recording playback

Checked on 2026-10-21 with JDK 17, Android SDK Platform/Build-Tools 36 and Python 3.12. No phone or Home Assistant frontend was available, so the numbers below are from the unit fixtures, not a live player.

Commands (Linux equivalents of `tests/run.ps1`, which itself calls `javac.exe`):

- JDK 17 `javac`/`java` for the suites in `tests/run.ps1`: network (68), frame pacing, discovery, output, geometry, local preview, lifecycle, detection, audio capture, camera, codec API 19 and 21, viewer (including the node browser test), RTSP audio, and recording. All passed.
- `python3 -m unittest discover -s homeassistant/tests -v`: 29 tests, OK. The previous 27 still pass; two new cases cover an old phone without the day index, and the authenticated thumbnail proxy.
- `./gradlew --no-daemon assembleDebug lintDebug`: BUILD SUCCESSFUL, lint report 104 warnings and 0 errors.

First-load measurements from `RecordingPlaybackTest` (printed as `MEASURE` lines):

- MP4 header. Method: build a synthetic file `ftyp + 256 KiB mdat + moov`, then count the bytes that must be read before `moov` is complete. Before faststart: 262236 (the whole file). After `Mp4FastStart`: 84 (`ftyp` + `moov`). The media bytes and the `stco` chunk offset were checked after the move. A second pass leaves the file alone.
- Recording list. Method: 200 clips across 10 days, compare HTTP body sizes. `GET /api/recordings?offset=0&limit=100` (what the media browser used to request first): 27451 bytes. `GET /api/recordings/days` (the first screen now): 330 bytes.

Device and Home Assistant checks still open:

1. Mic on, confirm the phone or VLC still has sound, then open the HA camera player (not the MJPEG card) and confirm HA has sound too. The picture card / MJPEG path is still silent.
2. Record a new clip. In HA → Media → Afterglow it should sit under today's date, with a thumbnail, time, duration and a reason (画面变动, 声音触发, or 定时 when a daily window is on). Dragging the progress bar still works.
3. An older clip without a thumbnail still plays. The camera entity id is unchanged.
4. 仅本地录像 still has no recording HTTP API.

# Validation — recording settings and external playback

Checked on 2026-10-21 with JDK 21 (RTSP audio rechecked on JDK 17), Android SDK Platform/Build-Tools 36 and Python 3.12.

- The same JVM suites listed in `tests/run.ps1` were run on Linux. `tests/run.ps1` itself calls `javac.exe` and is the Windows CI entry; it was not executed on this machine.
- New checks cover the daily window (including overnight and equal start/end), preset versus 自定义 values, sound-sensitivity fallback, content URI segment encoding, and timeline behavior: idle outside the window, finish the open segment on the next keyframe, and start again only after the window opens.
- `python -m unittest discover -s homeassistant/tests -v`: all 27 tests passed. Home Assistant media browsing was not modified.
- Gradle `assembleDebug lintDebug`: successful, 0 errors and 104 warnings.
- No phone, player app, or Home Assistant frontend was exercised. The manual checks below are still open.

Device checks for this change:

1. Upgrade over an existing install. Custom segment length, quota, pre/post-roll and retention that are not on a preset must still show as 自定义 and keep the old number. Sound sensitivity must match the previous single sensitivity until it is changed.
2. All-day continuous recording behaves as before. With a daily window, live view and RTSP/MJPEG keep running outside the window. The active clip ends on the next keyframe (about one second at the current encoder interval) and no new clip opens until the window starts again.
3. Event mode does not start a clip outside the window. A clip already running ends at the next keyframe instead of using the full post-roll.
4. The recordings list has no in-app player. 用其他应用打开 plays in another app. 分享 sends the file. 导出, 锁定 and 删除 still work. With no video app installed, the chooser or the export prompt appears, and Android 7+ does not crash on a file URI.
5. Home Assistant → Media → Afterglow still lists and seeks completed clips while the phone is streaming. 仅本地录像 still has no recording HTTP API.

# Previous validation — Afterglow 1.4.2

Checked on 2026-10-21 with JDK 17, Android SDK Platform/Build-Tools 36 and Python 3.12.

- Complete `tests/run.ps1` passed, including the existing 68 network-address checks,
  frame pacing, buffer ownership, codec, discovery, HTTP range and recording suites.
- `python -m unittest discover -s homeassistant/tests -v`: all 27 tests passed.
  Four new layout tests cover root placement, HACS configuration, manifest metadata
  and the bundled brand icon. They failed before migration and passed afterward.
- Final Gradle `assembleDebug lintDebug` passed: 0 errors and 99 warnings.
- Manual APK version 1.4.2/code 9, package `com.oldphone.ipcam`, min SDK 19/target 33,
  passed v1/v2/v3 signature verification using the certificate listed for 1.4.1 below.
- Source review confirmed four Generic Camera steps and three common questions in
  both user guides and the App; installation/maintenance detail is separate.
  The open guide refreshes displayed/copied addresses after network changes and
  disables unavailable addresses. Address explanation views were removed.
- Runtime integration files were moved without behavior changes; the manifest version
  is now 1.4.2. Local structural checks are not full HACS validation. GitHub Actions
  runs the official HACS validator, ignoring only the license policy because this
  repository has not chosen an open-source license. Support is for custom repositories,
  not inclusion in the default HACS catalog. See the release commit's Actions results.

Device UI, physical camera performance, HA frontend playback and the user's Tailscale
route still require target-device acceptance. No hardware performance result is claimed.
The APK uses the same signing certificate as the delivered 1.4.0/1.4.1 test packages,
but differs from the public 1.3.1 certificate recorded below. Export important recordings
and note settings before any reinstall; uninstalling removes app-private recordings.

# Previous validation — Afterglow 1.4.1

Checked on 2026-10-21 with the same JDK 17 / Android 36 toolchain.

- The complete `tests/run.ps1` suite passed, including 68 new address checks:
  LAN/VPN enumeration order, ordinary 10.x LAN addresses, multiple interfaces,
  duplicate addresses, interface state, unusable IPv4/IPv6 filtering and empty results.
- Gradle `assembleDebug lintDebug` passed with 0 errors and 99 warnings.
- The manual APK is version 1.4.1 / code 8; v1/v2/v3 signatures verified with
  certificate `749cb6ed850f0ac415ffd34d4fdee7ced400730619344cc6ba9134168e8fd7e4`,
  matching the previously delivered local 1.4.0 test APK.
- Address selection, disconnected-state copying and the in-app HA tutorial were
  reviewed against the API 19 boundary. Both READMEs document installation from
  the current development branch, HA OS/Container setup and recording playback.

Interface labels are name-based hints, not a measured connectivity result. The
selector only changes displayed/copied URLs; it does not change routing or DHCP.
Device UI rendering, actual interface names, HA installation and playback still
require verification on the intended phone and HA instance. Existing signature
and recording-preservation cautions below continue to apply.

# Previous validation — Afterglow 1.4.0

Checked on 2026-10-21 with JDK17, Android SDK Platform/Build-Tools36, Gradle8.13 and Python3.12.

- The complete `tests/run.ps1` suite passed, including new persistent device identity,
  pending/stale DNS-SD registration callbacks, public capability discovery and protected
  viewer fallback. Real HTTP socket tests cover Basic auth, public nonsecret identity,
  RTSP-only capability isolation, read-only clip paths, pagination/JSON escaping,
  inclusive/open/suffix byte ranges, HEAD, If-Range, unavailable/deleted recordings and stop.
- `python -m unittest discover -s homeassistant/tests -v`: 23 tests passed, using real
  aiohttp peers and HA framework boundary doubles. Includes address reuse before polling:
  changed/unavailable identity blocks every new credentialed request and RTSP source.
  These tests do not run HA's auth middleware, frontend or real Zeroconf stack.
- Gradle `assembleDebug lintDebug` succeeded; final `lintDebug` has 0 errors and 100
  warnings. Baseline2683340 had 100 warnings on this toolchain. An additional guarded
  API21 `TargetApi` annotation uses the same no-AndroidX pattern as existing code;
  the cached dependency-version warning is absent. No blanket suppression was added.
- `build-apk.ps1` produces version1.4.0/code7, package `com.oldphone.ipcam`, min19/target33;
  v1/v2/v3 signature verification passed. The local debug certificate is
  `749cb6ed850f0ac415ffd34d4fdee7ced400730619344cc6ba9134168e8fd7e4`, which differs from
  the certificate recorded below for1.3.1. Treat this APK as a test build until the
  installed app certificate is checked; it cannot overwrite an app signed with the old
  recorded certificate. Do not uninstall an existing app merely to bypass this conflict:
  app-private recordings would be removed.
- Source review found a saved-credential path after IP reuse. The central API now binds
  the expected UUID and rechecks public identity before each new protected request;
  regression tests exercise list, snapshot, status and media proxy paths.

## Performance regression evidence

- Synthetic60-second timing traces use the production FramePacer: 15fps input with
  ±2ms jitter accepted600 frames with the old gate and900 with the fix; 30→15fps
  accepted601 vs900. This is deterministic scheduling evidence, not measured phone FPS.
- A second independently anchored15fps MJPEG gate reproduced450 published frames
  from900 accepted captures. Active MJPEG now follows every accepted capture; only
  idle snapshots use a2-second timer. Client-join jitter and idle/active transitions
  have regression coverage. Real socket tests also confirm that idle requests,
  unauthorized video and recording reads do not request continuous JPEG production.
- Local preview keeps original crop metadata, uses a reusable ≤960-edge NV21 buffer,
  and moves JPEG encoding and decoding off the capture worker. Tests cover exact
  rotated/downscaled luma/chroma, JPEG length/capacity reuse, compression failure and
  protecting the displayed bitmap when newer decoded frames are discarded.
- For1920×1080 capture, local-preview NV21 storage is777,600 bytes instead of3,110,400
  bytes:75% smaller for this buffer. This is allocation arithmetic, not total app RAM.
- Expanded YUV tests cover direct/heap/read-only buffers, padding, nonzero positions,
  marks and truncated planes. Codec simulations on API19/21 verify that releasing
  completed output before input acquisition avoids dropping the next frame.
- Optional desktop JDK17 converter benchmark (7×160 frames, warmed, alternating order)
  measured median scalar→bulk luma conversion:720p heap1,944→657µs, padded direct720p
  1,505→573µs, padded direct1080p3,348→1,275µs. A duplicate view adds56–64 bytes/frame on
  that JVM; no second full-frame buffer. These values do not establish Android CPU
  usage, battery life, temperature, end-to-end latency or hardware camera throughput.
- After all performance edits, the complete `tests/run.ps1` suite and23 HA boundary
  tests passed again. Gradle `assembleDebug lintDebug` passed (0 errors,100 warnings),
  and the190,955-byte manual APK verified with the certificate recorded above.
  Independent review found and resolved the double-pacing issue; no further concrete
  P1/P2 findings remained. The UI FPS label explicitly describes processing throughput.

## Device/HA acceptance still required

1. Check the installed APK signature and preserve existing recordings before upgrading.
2. Install the custom integration in the target HA version; browse and seek a real MP4.
3. Confirm unauthenticated HA proxy access is rejected and signed media URLs expire.
4. Change the phone DHCP address on the same multicast LAN; keep the same device/entity
   and verify automatic recovery. Confirm manual reconfiguration on isolated networks.
5. Play over the user's Tailscale route; test RTSP-only, MJPEG-only, protected HTTP,
   SD removal, stop/restart, local-only isolation and Android19 NSD on actual hardware.
6. At the same light level, lens, resolution and bitrate, compare selected15fps local
   preview, VLC RTSP and MJPEG separately; repeat with preview hidden and recording
   on/off. Sample actual FPS, CPU/RAM and temperature over at least10 minutes. Low-light
   exposure, hardware encoding limits and congestion can still reduce delivered FPS.

# Previous validation — Afterglow 1.3.1

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
- `build-apk.ps1`: version 1.3.1 / code 6, minSdk 19, targetSdk 33,
  package `com.oldphone.ipcam`. v1/v2/v3 signatures verified; certificate remains
  `dd4508eb3d4c3698c4be1c828b1171c250f0820cbdc5caf2cb6a8e9198f7c239`.
- Independent reviews identified full-disk management being gated on a write probe,
  event-marker ownership across segments, and FFmpeg's explicit `RTP/AVP/UDP` transport
  header. Regression tests cover these fixes. No signing material or real recordings
  are included in the repository.

No physical camera or microphone was opened during these tests. Native Android
MediaMuxer output, the built-in Android player, real sensor/microphone latency and
long-term clock drift are not established by the JVM or FFmpeg transport tests.

The first cloud run for 1.3.0 exposed a PLAY response/first-frame acceptance race
that local scheduling had not triggered. Version 1.3.1 adds a deterministic
response-flush latch regression for initial PLAY and resumed playback.

## Device acceptance — 1.3.x

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
