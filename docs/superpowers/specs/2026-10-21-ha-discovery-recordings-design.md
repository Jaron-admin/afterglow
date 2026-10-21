# Stable HA identity and recording playback

The user wants Afterglow to survive phone address changes and expose existing phone recordings in Home Assistant. The user already reaches the home LAN with Tailscale and delegated the connectivity choice. Keep that route; build native HA integration and phone APIs. Success is one persistent HA device whose current address can change, with live video and completed recording browsing/playback through HA.

## Decisions and limits

- Android 4.4/API19 minimum, target33, compile36; no Android third-party runtime dependencies. Keep existing package and signing strategy.
- DHCP still controls the phone's IP. Persist a random UUID in private preferences; advertise `_afterglow._tcp.` on HTTP port8080 with `Afterglow-<UUID>` instance name and API21+ TXT id/api fields. HA keys entries/entities by UUID and verifies `/api/info` before adopting a discovered address. Discovery is local multicast, not VPN broadcast forwarding or a guaranteed `.local` hostname.
- `/api/info` is public minimal identity/capability metadata. All recordings, snapshots and MJPEG retain optional existing HTTP Basic authentication. HTTP discovery identity is not cryptographic authentication; retain the trusted LAN/VPN deployment model and never follow redirects with stored credentials.
- Every network output mode exposes the HTTP identity/recording service. RTSP-only does not produce MJPEG or snapshots. Local-only exposes no network listener or discovery. Stopping capture stops these services, matching existing lifecycle.
- Only the currently selected recording store's indexed completed clips are readable. No file paths, partial clips, deletion, lock changes, recording uploads or cloud copies are exposed.
- HA Media Source browses devices then paginated newest-first recordings. Playback returns an authenticated/signed HA-relative proxy URL; HA forwards byte ranges and streams in bounded chunks. The browser never receives phone credentials or a phone LAN playback URL. Tailscale only needs access to HA for playback.
- No router reconfiguration, HA deployment, public port mapping, automatic installation on the phone, publishing a release or merge is included. Deliver a development branch/PR and installable artifacts with honest validation limits.

## HTTP contract (API version 1)

`GET/HEAD /api/info`: object with `api_version:1`, `device_id` (canonical UUID), `name`, `http_port`, `rtsp_port`, `rtsp_enabled`, `mjpeg_enabled`, `recordings_enabled:true`, `http_auth_required`. No filesystem details. Nearby-phone viewers use the nonsecret authentication flag to prefer RTSP when protected MJPEG would otherwise fail without a login UI.

`GET/HEAD /api/recordings?offset=0&limit=100`: object with `recordings`, `total`, `offset`, `limit`; limit 1..200, nonnegative offset. Each recording has `id`, `start_time_ms`, `duration_ms`, `bytes`, `locked`, `with_audio`, `reasons`. Newest first. Storage unavailable returns503, no silent empty success.

`GET/HEAD /api/recordings/<id>`: `video/mp4`, Content-Length, Accept-Ranges, ETag, Last-Modified. Single byte range yields206; unsatisfiable/multi-range requests yield416 with total length. If-Range mismatch serves whole clip. HEAD returns full headers without body and ignores Range. Unknown/invalid/unregistered clips return404. POST/DELETE return405. No whole-video buffering; stop closes active sockets.

## Verification

Test persisted identity, stale registration callback cleanup and retry behavior; HTTP auth, strict IDs/path isolation, pagination, file deletion/unavailable storage, byte ranges/HEAD/If-Range and server shutdown; HA discovery identity mismatch and rediscovery, configuration, browse/resolve and real aiohttp range proxy behavior. Run existing JVM/socket/browser suite, Android build/lint, and Python integration tests. Actual old-phone NSD behavior, hardware recording playback and the user's HA/Tailscale deployment remain device acceptance checks unless those devices become available.

## Added scope: frame cadence and preview cost

The user reports that both the phone preview and remote playback fall short of the selected15 fps, and authorizes CPU/memory/smoothness optimization. Source inspection establishes software causes without attributing the report to a particular camera driver: each accepted-frame timestamp resets a strict minimum interval, local preview is capped at8 fps, and MJPEG at12 fps. Cascaded gates can halve a nominal15 fps camera cadence under ordinary timing jitter.

- Use cumulative monotonic deadlines with bounded early tolerance; never replay a backlog after a pause. MJPEG and local preview follow the configured capture rate, subject to actual device throughput.
- Admit at most one local preview through display handoff. Copy/rotate/downscale the full field of view into one reusable NV21 buffer bounded to960 pixels on its long edge. Keep original geometry for the exact output crop overlay. Encode/decode this local JPEG on the existing preview worker, not the capture/encoder worker. Retain reusable bitmaps and avoid copying the compressed local JPEG.
- Preserve selected RTSP/MJPEG/recording dimensions and H.264 bitrate. Do not hide performance issues by reducing recorded output quality.
- Bulk-copy contiguous YUV luma rows while respecting padding, pixel stride, direct buffers and positions. Release completed encoder output before requesting another input buffer.
- Test nominal/jittered cadence, changing rates, slow sources and pauses; compare bounded preview pixels/chroma across rotations; test codec output backpressure and buffer ownership. Desktop simulations/benchmarks demonstrate algorithm behavior only, not phone CPU/RAM, actual fps or thermal performance.
- Leave RTSP congestion policy unchanged pending a GOP-safe queue design and actual network measurements.
