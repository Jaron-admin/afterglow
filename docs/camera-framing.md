# Camera framing and orientation

The selected output width and height are independent of the UI orientation.
Every input frame uses one immutable `FrameGeometry`: physical camera pixels →
upright full frame → centered, chroma-aligned crop → selected output dimensions.
RTSP, MJPEG and snapshots consume that cropped output. The local preview instead
uses the complete upright input and the **same** crop coordinates for its mask.

`OrientationEventListener` supplies a clockwise physical device angle, quantized
to four positions with a 50° hysteresis threshold and 350 ms stable interval.
Unknown (for example flat) poses retain the previous committed angle. For a
non-mirrored output the clockwise pixel rotation is:

- Back camera: sensor orientation + device angle + manual calibration.
- Front camera: sensor orientation − device angle + manual calibration.

Angles are normalized to 0–359°. Sources: [Camera1 rotation example](https://developer.android.com/reference/android/hardware/Camera.Parameters#setRotation(int)),
[Camera2 JPEG orientation](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#JPEG_ORIENTATION).
These describe the geometry; our code rotates actual NV21 pixels, rather than
depending on EXIF or a player's orientation handling.

The UI requests `fullSensor`. A direction lock stores the current display
rotation and the current stable device angle separately. While locked, moving
the device changes neither the interface nor pixel correction. Calibration is an
explicit exception and remains available. Unlocking restores physical tracking.
Calibration belongs to the resolved camera key, including a physical member key,
not to the automatic-selection placeholder.

Capture and encoding settings use the existing serialized `StreamLifecycle`.
Pose/lock/calibration changes update geometry only at frame boundaries, with a
separate increasing revision. Preview delivery must match both the current
session generation and geometry revision. Old asynchronous frames are discarded.

Camera2 uses a separate control thread for asynchronous device/session callbacks;
the lifecycle worker must never wait on callbacks scheduled on itself. Input
planes are read with their actual row/pixel strides and converted to borrowed
NV21. Android 4.4 continues using Camera1. Explicit camera selection never silently
switches to a different lens on failure.

Source stream sizes prioritize the sensor's native aspect ratio where available.
The complete input is limited to what the hardware actually exposes as a stream;
sensor cropping and vendor restrictions cannot be recovered in software.
CPU transforms reuse buffers and precompute sample offsets; local full-frame
JPEG work is skipped without a preview listener. Large outputs can reduce the
frame rate on older phones and do not create detail absent from the input.
