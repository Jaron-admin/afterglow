# Local recording, audio and detection Implementation Plan

> **For agentic workers:** Follow the confirmed spec and implement independently owned tasks with tests and a final integrated review. Root integrates service/UI and handles delivery.

**Goal:** Deliver API19-compatible continuous/event MP4 recording on phone or exposed SD storage, optional microphone audio in MP4/RTSP, and offline change/sound triggers.

**Architecture:** Reuse one camera and one H.264 encoder. Fan out timestamped encoded data to RTSP and a bounded disk writer; feed basic detectors from downsampled video and borrowed PCM. Reconfigure complete sessions when track topology changes.

**Tech Stack:** Java 8, platform Android MediaCodec/MediaMuxer/AudioRecord, existing socket RTSP, no new runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-10-20-local-recording-detection-design.md`

## Global constraints

- minSdk 19, targetSdk 33; no new external service or network inference.
- Microphone and recording default off; configurable 300-second default segments, ~5-second pre-roll, ~15-second post-roll.
- Follow-up scope: segment duration/size, quota/reserve/retention, loop-or-stop, pre/post-roll and recording audio all configurable in a separate settings page.
- Default quota 2 GiB; reserve at least 256 MiB; delete only owned, complete, unlocked clips.
- Application-directory recordings disappear on uninstall; export is explicit and user-directed.
- Storage failure must not stop streaming; audio failure is explicit and stops the requested AV session.
- All new Android version-specific methods guarded; raw camera/PCM buffers are borrowed synchronously.

## Review focus

- Slow or removed SD: bounded queues, explicit recording error, camera and RTSP remain responsive.
- Track/format changes: no old samples enter the new muxer; audio and video share a monotonic clock.
- Event boundaries: start at an available keyframe, preserve pre-roll within bounds, merge repeated triggers.
- Cleanup: protect locked, partial and foreign files; canonicalize all managed source paths.
- Silence/lighting/rotation: no permanent trigger storm; rebuild visual background after geometry changes.

## Task 1 — Recording and storage

Files: `RecordingManager.java`, `RecordingStore.java`, `RecordingsActivity.java`,
supporting pure recording state classes, `tests/recording/*`.

Inputs: MediaFormat callbacks and immutable encoded samples with monotonic microsecond PTS.
Outputs: asynchronous status listener, storage target inventory, owned clip listing and actions.
The implementer publishes exact Java signatures before service integration.

- Write/run failing tests for keyframe pre-roll, post-roll extension, segmentation and queue overflow.
- Implement one writer worker; MediaMuxer track formats supplied separately from media samples.
- Write/run cleanup tests using temporary directories: locked, foreign and incomplete files survive.
- Implement target enumeration, write checks, indexed clips, quota/reserve checks and error states.
- Implement local list/play/export/delete/lock UI with API19 document export and clear uninstall notice.
- Run focused tests and compile against Android36 with API19 guards.

## Task 2 — RTSP dual-track audio

Files: `RtspServer.java`, optional pure RTP helper, `tests/viewer/RtspAudioTest.java`.

Preserve existing public methods; add:
```java
void setAudioConfig(int sampleRate, int channels, byte[] audioSpecificConfig);
void pushAudio(byte[] rawAac, long ptsUs);
```

- Write failing real-socket tests: two SETUP orders, distinct interleaved channels, audio-only setup,
      disabled-audio SDP, and original video compatibility.
- Keep transport, sequence, SSRC, clocks and sender reports per track.
- Encode AAC MPEG4-GENERIC RTP access-unit headers, packet boundaries and shared AV time mapping.
- Test UDP and TCP routing plus stop/restart disposal; do not regress handshake timeouts.

## Task 3 — Capture and basic detectors

Files: `AudioCapture.java`, `MotionDetector.java`, `SoundDetector.java`, `tests/detection/*`.

Interfaces:
```java
AudioCapture.start(AudioCapture.Listener listener); // throws startup exception
AudioCapture.close();
// listener: onFormat(MediaFormat), onEncodedFrame(byte[], long ptsUs),
// onPcm(short[], int count, long ptsUs), onError(String)
boolean MotionDetector.update(byte[] nv21, int width, int height,
                              long ptsUs, long geometryRevision, int sensitivity);
boolean SoundDetector.update(short[] samples, int count, long ptsUs, int sensitivity);
```

- Write failing tests with moving patches, static/noisy frames, exposure changes and geometry resets.
- Implement bounded downsampling/background state and warmup; return only sustained triggers.
- Write failing silence/impulse/sustained-noise/cooldown tests using PCM arrays.
- Implement AudioRecord mono16k and AAC-LC capture, shared monotonic PTS, explicit startup/runtime errors.
- Ensure close unblocks reads and releases resources on the owning worker, including partial starts.

## Task 4 — Integrate service and settings

Files: `CameraStreamService.java`, `H264Encoder.java`, `MainActivity.java`,
`ViewerActivity.java`, `Prefs.java`, manifest/resources, new `RecordingSettingsActivity.java`.

- Extend H264 listener with the actual output format and guard sample buffer release in finally.
- Start H264 when RTSP or recording needs it; allow only-local mode without servers.
- Construct recording/audio/detector sessions with captured generation; route callbacks safely.
- Reset motion on geometry revisions; annotate continuous clips or trigger event mode.
- Close acquisition, encoder output, recording queues and servers in defined order.
- Add compact recording entry/status, settings with storage/quota/mode/sensitivity/microphone,
      microphone permission handling and local recording navigation.
- Make the existing viewer's RTSP path explicitly selectable for sound; preserve MJPEG fallback.
- Add manifest microphone service capability and declare the new internal activities.

## Task 5 — Verify and deliver

- Include all focused runners in `tests/run.ps1`, passing JDK and AndroidJar.
- Run `tests/run.ps1 -JavaHome F:\SDK\jdk-17`; inspect every result.
- Run offline Gradle `assembleDebug lintDebug` with the existing ignored local repository init.
- Review complete changes against spec, resolve real findings and repeat affected tests.
- Build signed APK with `build-apk.ps1`, inspect version/certificate and compute checksum.
- Update README/validation to distinguish automated evidence from pending physical-device tests.
- Copy APK/checksum into original dist; commit/push private repository, publish release and verify CI.
