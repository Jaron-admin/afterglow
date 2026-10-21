# Integration verification

From the repository root, with Python 3.12+:

```powershell
python -m pip install --target homeassistant/tests/.deps -r homeassistant/tests/requirements.txt
python -m unittest discover -s homeassistant/tests -v
python -m compileall -q homeassistant/custom_components/afterglow
```

`test_http.py` uses real loopback aiohttp servers and real integration HTTP code. It checks public identity probes, credential handling, paging, bounded JSON, redirects, Range/206/416, HEAD and streaming header isolation. Address-reassignment regressions change the peer UUID before any coordinator poll and verify that status, recording lists, snapshots, clip and MJPEG proxies send only public identity probes, with no authenticated request to the new occupant. A verified API client cannot rebind its UUID; an unavailable identity endpoint fails closed.

`test_boundaries.py` uses those HTTP servers plus small doubles for Home Assistant's imported framework classes. It checks integration decisions, discovery identity and IP update ordering, setup/unload, camera capability choices and native media URLs. These tests do **not** run the Home Assistant auth middleware, frontend, entity registry, Zeroconf stack, HLS/RTSP pipeline or real Android recordings. No full Home Assistant runtime was available in this environment.

The implementation was compared with official Home Assistant `2026.10.2` source. The schema import falls back from `probatio` to `voluptuous` for older installations; this is not a claim that every older HA release was tested.

Before release, install on the target HA version, confirm Media → Afterglow browses and seeks an actual completed MP4 over LAN and Tailscale, reject unauthenticated `/api/afterglow/recordings/...`, test signed playback URL expiry, change the phone IP on the same multicast LAN, test Basic auth/reauth, and test RTSP-only and MJPEG-only modes. Do not expose phone ports to the Internet.
