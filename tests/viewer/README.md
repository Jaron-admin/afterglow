# Viewer regression tests

From the repository root, with JDK 17 and Node on PATH:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\viewer\run.ps1 -JavaHome F:\SDK\jdk-17
```

The Java tests compile the production MJPEG server/client and RTSP server, and connect through ephemeral loopback ports. Android-only logging, Base64, local address discovery and camera metadata are replaced by small test stubs; networking and MJPEG framing are real. The RTSP lifecycle tests never send H.264 payloads, so the encoder boundary is a stub that throws if invoked. A restricted sandbox may need permission to connect to loopback.

The suite checks that invalid HTTP authentication changes retain the previous protection, and that partial RTSP handshakes expire and all control sockets close across 40 start/stop cycles. It takes about ten seconds because it exercises the real handshake inactivity timeout.

The server's actual HTML and initial status response are saved under `app/build/viewer-tests`. Node executes that HTML's script with controlled browser events, image state, XHR responses and timers, verifying disconnect/restart recovery and cancellation on page exit. It does not assert on source text.

These checks do not run Android activities, decode pixels, or exercise a real browser's multipart image renderer. Physical-device testing is still needed for Android lifecycle behavior and MediaPlayer RTSP handling.
