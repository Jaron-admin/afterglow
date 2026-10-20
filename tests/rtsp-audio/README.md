# Optional FFmpeg interoperability check

`run.ps1` is the required Java socket regression suite. The separate optional
`run-ffmpeg.ps1` needs `ffmpeg` and `ffprobe` on PATH (or their explicit parameters)
and an Android SDK platform jar; it is not a dependency of the normal test suite.

```powershell
.\tests\rtsp-audio\run-ffmpeg.ps1 -JavaHome F:\SDK\jdk-17 `
  -AndroidJar F:\SDK\platforms\android-36\android.jar
```

It generates eight seconds of synthetic 320×240, 15 fps baseline H.264 and a
16 kHz mono AAC-LC sine tone. It uses the production `RtspServer` and production
H.264 Annex-B splitter, replacing only Android logging/base64/network-address
helpers for the JVM. No camera or microphone is opened.

A real-time producer uses a common monotonic PTS origin. FFmpeg joins the live
stream separately over RTSP TCP and RTSP UDP, decodes five seconds into FFV1 video
and PCM audio, and fails on FFmpeg warnings/errors. FFprobe verifies both decoded
tracks, dimensions, sample rate and nonzero frame counts; a PCM export verifies
nonzero audio samples. Joining mid-GOP can delay the first decoded video until
the next keyframe, so the expected video frame count is at least 50, not exactly
75.

Fixtures, decoded files, process logs and `RESULTS.txt` are saved under the ignored
`app/build/rtsp-interop/` directory. This verifies transport and decoder
interoperability; it does not validate a phone encoder, Android MediaPlayer,
physical capture latency, long-running drift or perceptual lip sync.
