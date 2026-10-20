# Recording regression tests

Run `tests/recording/run.ps1 -JavaHome <JDK17> -AndroidJar <android.jar>`.

- `RecordingTimelineTest` verifies decodable keyframe starts, shared AV segment
  timestamps, event extension, configurable pre/post roll (including zero pre-roll),
  duration/size rollover, event marker ownership across pre-roll segments, and
  bounded pre-roll memory.
- `RecordingStoreTest` uses temporary workspace directories to exercise actual
  index/file operations, oldest-unlocked cleanup, stop/retention policies, locked,
  foreign and partial file protection, and path traversal rejection. A native
  storage boundary substitute verifies full-disk management access, cleanup before
  writer probing, and explicit read-only errors without OS-specific file permissions.
- `RecordingWorkQueueTest` covers byte/item admission limits, accepted-work drain,
  and failure cancellation.
- `RecordingManagerTest` runs the production writer, queue, timeline and store.
  Only Android `Context`, storage mount state and the native codec/muxer boundary
  are substituted.
  It checks format readiness, AV timestamp origin, ignored audio when disabled,
  constructor/write/stop failures, orderly close, and a stalled writer overflowing
  its queue without blocking capture callbacks.

The native boundary substitute cannot establish that a real device's encoder,
MP4 muxer, SD volume, or player works correctly. Physical-device acceptance must
cover playback, AV synchronization, removal/full/slow SD behavior and interruption.
Test fixtures are created only under ignored `build/` directories.
