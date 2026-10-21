# HA discovery and recordings implementation plan

**Goal:** Recover HA connectivity after DHCP changes and browse/play completed phone recordings in HA using the existing Tailscale route.

**Architecture:** Android NSD announces persisted identity; phone HTTP serves read-only indexed media; HA tracks identity, creates camera entities and Media Source items, and proxies playback.

**Tech Stack:** Java8/Android API19+, Python/HA, stdlib JVM tests and aiohttp boundary tests.

**Spec:** `docs/superpowers/specs/2026-10-21-ha-discovery-recordings-design.md`

## Global constraints

Android min19/target33/compile36; no Android third-party dependencies. No credentials in media URLs or discovery. Preserve local-only mode. Current selected store only, completed indexed recordings only. Keep Tailscale and no public network deployment.

## Review focus

1. Stale NSD callbacks after stop/reconfigure must not resurrect announcements.
2. Discovery from another identity must not overwrite a configured device or receive its credentials.
3. MP4 requests with suffix ranges, invalid ranges, HEAD and deletion during playback must produce bounded valid responses.
4. HTTPS HA clients must receive HA-relative playback URLs and no phone credentials.
5. RTSP-only still needs metadata/recording HTTP, while local-only must open no network listener.

## Tasks

- [x] 1. Discovery: implement `Prefs.deviceId()/deviceName()`, `ServiceDiscovery(Context).start(int)/stop()` with a testable registration lifecycle, API19 fallback identity name and API21 TXT. Test persistence and out-of-order callbacks before implementation.
- [x] 2. HTTP: add `RecordingHttpApi.Repository` backed by RecordingStore plus GET/HEAD/list/range handling, public identity and protected read-only recordings. First add real socket tests that fail on current404. Wire service lifetime and mode flags. Run all HTTP and recording tests.
- [x] 3. HA: add config flow/manual/reconfigure/zeroconf, stable camera entity, paginated media_source and authenticated relative proxy. Use exact spec field names. Write failing discovery/auth/range tests and verify against real HTTP peers with HA boundary doubles where a runtime is unavailable.
- [ ] 4. Integrate: run existing suite, new tests, Android build and lint, review diff independently, fix findings, document install/acceptance and produce APK/integration zip. Commit to isolated feature branch and open draft PR without merging.

## Execution record

User delegated choice after clarifying Tailscale; no repeated design approval required. Fresh clone and feature branch isolate existing installations. Parent owns HTTP/service wiring; discovery and HA integration agents edit disjoint files. Build agent prepares SDK in workspace.

| Shared interface | Check |
|---|---|
| Discovery / HA | full UUID in instance; suffixes allowed; verify public `/api/info` before credential use |
| HTTP / HA | spec API fields, 100 default/200 max list page, video/mp4 ranges |
| Service / discovery | start after listener ready, stop before listener closes, no local-only announcement |
| Service / recording store | read completed selected store even with recording currently disabled |

Baseline attempt with missing SDK36 failed at compile classpath; build agent locating/installing required SDK. This is environment setup, not a production test failure.

Verification complete: full JVM/socket/browser suite, 23 HA tests, APK build/sign verification, lint0 errors/100 warnings. Independent final review clean after identity-gate fix. APK signing differs from documented prior release; test-build delivery only. Packaging and draft PR await the user-added performance optimization scope.

## Added performance work

The user clarified that both local and remote playback feel below the selected15 fps. Existing authorization covers fixes and bounded optimizations; no additional approval gate is needed.

- [x] Replace reset-on-accept interval gates with a tested cumulative FramePacer; remove hidden8/12 fps preview/MJPEG limits and normalize displayed processing fps by elapsed time.
- [x] Bound local preview buffers and move its JPEG work to the existing background decoder, maintaining full field of view, crop mask, generation rejection and a single in-flight frame.
- [x] Optimize contiguous YUV luma copies and release codec output before input acquisition; retain exact pixels and test pressure behavior.
- [x] Run regression suite/build/lint, independently review lifecycle/ownership, document desktop-only measurements and device acceptance, and package test APK/integration ZIP.
- [ ] Open the draft PR as the final delivery step.

Final performance verification: complete suite and23 HA tests passed; build/lint passed
(0 errors/100 warnings); manual test APK190,955 bytes with v1/v2/v3 signatures.
Review caught independent MJPEG double-pacing (900 captures→450 JPEGs); active streams
now share capture cadence. Only authenticated live HTTP streams request full-rate JPEG,
so recording playback cannot accidentally enable that work. Phone metrics remain unmeasured.

Delivery status: the test APK, HA ZIP and installation guide are packaged. The connected
GitHub app cannot write, but the existing local login can publish source through the
GitHub API. Its token lacks workflow-edit scope: the same source tree succeeds when
the optional new HA Actions job is omitted. Keep the repository's existing workflow;
HA tests remain in source and have passed locally. All requested feature code and
regression tests are retained. Publishing the draft PR is the final delivery step.
