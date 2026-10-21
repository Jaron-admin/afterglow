# Camera backend checks

Run `powershell -ExecutionPolicy Bypass -File tests/camera/run.ps1` with the bundled JDK17 and Android36 jar. The runner compiles the five production camera files against the real Android SDK; it does not build the APK.

`Yuv420ConverterTest` uses known NV21 bytes to check planar and pixel-stride2 input, row padding, nonzero buffer positions, multiple chroma rows, direct/read-only buffers, absent last-row padding, and rejection of truncated input before destination mutation. The planar and padded/interleaved tests were observed red before their implementations and then green.

The luma bulk-copy optimization also runs the same literal pixel fixture across heap/direct, mutable/read-only, packed/padded, and pixel-stride1/2 planes. It preserves positions, limits, and marks and rejects a shortened luma limit before touching the destination. These tests passed the scalar baseline before the optimization and protect unchanged pixel output.

Pass `-Benchmark` to additionally run `Yuv420ConverterBenchmark`. It compares the previous scalar implementation with the production converter using reused 720p/1080p buffers, warm-up, and seven alternating-order batches. It reports median wall time per conversion and thread allocation bytes when the desktop JVM exposes them. The optimized luma path creates one duplicate buffer view per frame; it does not allocate or copy a second full frame. The benchmark is synthetic desktop JVM evidence only, not Android CPU, memory, camera, or delivered-FPS evidence. Device profiling is still required.

`CameraSourceSizeTest` checks native-aspect selection, smallest sufficient source, portrait normalization, oversized-source rejection, and old front/back choice aliases.

`CameraReservationTest` exercises the real global lease with Java threads: the next backend waits while an old device owns its lease, release unblocks it, waiting times out, and repeated close of an old lease cannot release a newer lease.

The JVM tests cannot prove Android HAL callback ordering. On a device, verify rapid start/stop and repeated camera changes during both open and session configuration, then background/foreground transitions. Camera2 deliberately retains its control looper and global lease after `CameraDevice.close()` until `StateCallback.onClosed`; that callback is the terminal release acknowledgement. It clears pending session configuration because Android does not guarantee a later session configuration callback after device close. A late `onOpened` is closed and likewise waits for `onClosed`. A stuck native open retains ownership and subsequent opens fail with a bounded wait instead of overlapping cameras.

Physical cameras are offered only when their public metadata and YUV output sizes can be queried. Actual stream configuration and vendor-exposed lenses require device validation; no hidden/vendor-private camera access is attempted.
