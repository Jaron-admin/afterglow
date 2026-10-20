package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 owns a separate control looper: callers may synchronously wait on worker. */
@android.annotation.TargetApi(21)
final class Camera2Backend extends CameraCapture {
    private final Handler worker;
    private final Listener listener;
    private final int fps;
    private final HandlerThread controlThread;
    private final Handler control;
    private final CountDownLatch startupDone = new CountDownLatch(1);
    private final CountDownLatch fullyClosed = new CountDownLatch(1);
    private final AtomicBoolean delivering = new AtomicBoolean(false);
    private volatile IOException startupFailure;
    private volatile boolean ready;
    // The following state is owned by control, including callbacks arriving after timeout.
    private boolean openPending, sessionPending, deviceClosePending;
    private CameraDevice closingDevice;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private byte[] nv21;
    private CameraCharacteristics logicalInfo;

    private Camera2Backend(CameraCatalog.Entry entry, int width, int height, int fps,
                           Handler worker, Listener listener) {
        super(entry, width, height);
        this.fps = fps; this.worker = worker; this.listener = listener;
        controlThread = new HandlerThread("afterglow-camera2-control");
        controlThread.start(); control = new Handler(controlThread.getLooper());
    }

    static CameraCapture open(final Context context, CameraCatalog.Entry entry,
                               int outputW, int outputH, int fps, Handler worker,
                               Listener listener) throws IOException {
        int[] size;
        try { size = CameraCatalog.chooseSourceSize(entry, outputW, outputH); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
        final Camera2Backend capture = new Camera2Backend(entry, size[0], size[1], fps, worker, listener);
        capture.control.post(new Runnable() { @Override public void run() { capture.begin(context); } });
        try {
            if (!capture.startupDone.await(8, TimeUnit.SECONDS)) {
                capture.close();
                if (!capture.awaitClosed(1500))
                    throw new CameraCapture.UnsettledOpenException("镜头打开超时，正在等待系统释放", capture);
                throw new IOException("镜头打开超时");
            }
        } catch (InterruptedException interrupted) {
            capture.close(); Thread.currentThread().interrupt();
            if (capture.fullyClosed.getCount() != 0)
                throw new CameraCapture.UnsettledOpenException("镜头打开已取消，正在等待系统释放", capture);
            throw new IOException("镜头打开已取消", interrupted);
        }
        if (capture.startupFailure != null) {
            capture.close();
            if (!capture.awaitClosed(1500))
                throw new CameraCapture.UnsettledOpenException(capture.startupFailure.getMessage(), capture);
            throw capture.startupFailure;
        }
        if (capture.closed.get()) {
            // A device error can race the failure check above. Keep the lease until
            // the same terminal onClosed acknowledgement as every other exit.
            capture.close();
            if (!capture.awaitClosed(1500))
                throw new CameraCapture.UnsettledOpenException("镜头已关闭，正在等待系统释放", capture);
            throw new IOException("镜头已关闭");
        }
        return capture;
    }

    private void begin(Context context) {
        if (closed.get()) { cleanup(); return; }
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new IOException("系统未提供相机服务");
            logicalInfo = manager.getCameraCharacteristics(entry.id);
            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(images, control);
            nv21 = new byte[width * height * 3 / 2];
            openPending = true;
            try { manager.openCamera(entry.id, deviceEvents, control); }
            catch (SecurityException denied) {
                openPending = false;
                throw new IOException("未授予摄像头权限", denied);
            } catch (Throwable failure) { openPending = false; throw failure; }
        } catch (Throwable failure) {
            fail("打开镜头失败：" + message(failure));
        }
    }

    private final CameraDevice.StateCallback deviceEvents = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            openPending = false;
            device = camera;
            if (closed.get()) { cleanup(); return; }
            try {
                sessionPending = true;
                if (entry.physicalId != null) {
                    if (Build.VERSION.SDK_INT < 28) throw new IOException("系统不支持独立物理镜头");
                    OutputConfiguration output = new OutputConfiguration(reader.getSurface());
                    output.setPhysicalCameraId(entry.physicalId);
                    device.createCaptureSessionByOutputConfigurations(
                            Collections.singletonList(output), sessionEvents, control);
                } else {
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()), sessionEvents, control);
                }
            } catch (Throwable failure) {
                sessionPending = false; fail("镜头配置失败：" + message(failure));
            }
        }
        @Override public void onDisconnected(CameraDevice camera) {
            openPending = false; device = camera;
            if (closed.get()) cleanup(); else fail("镜头已断开连接");
        }
        @Override public void onError(CameraDevice camera, int error) {
            openPending = false; device = camera;
            if (closed.get()) cleanup(); else fail("相机错误：" + error);
        }
        @Override public void onClosed(CameraDevice camera) {
            // Device closure is the terminal guarantee; a configuring session need
            // not deliver onConfigured/onConfigureFailed after its device closes.
            sessionPending = false;
            if (camera == closingDevice) {
                closingDevice = null;
                deviceClosePending = false;
            }
            if (camera == device) device = null;
            if (closed.get()) cleanup();
            else fail("镜头已关闭");
        }
    };

    private final CameraCaptureSession.StateCallback sessionEvents = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession configured) {
            sessionPending = false;
            if (closed.get()) { configured.close(); cleanup(); return; }
            session = configured;
            try {
                CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                request.addTarget(reader.getSurface());
                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                int[] focus = logicalInfo.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (contains(focus, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                Range<Integer> range = closestFps(logicalInfo.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), fps);
                if (range != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                Rect active = logicalInfo.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (active != null) request.set(CaptureRequest.SCALER_CROP_REGION, active);
                if (contains(logicalInfo.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF))
                    request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                if (contains(logicalInfo.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF))
                    request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                if (Build.VERSION.SDK_INT >= 31 && contains(
                        logicalInfo.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES),
                        CaptureRequest.SCALER_ROTATE_AND_CROP_NONE))
                    request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
                session.setRepeatingRequest(request.build(), null, control);
                ready = true; startupDone.countDown();
            } catch (Throwable failure) { fail("镜头无法开始采集：" + message(failure)); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession failed) {
            sessionPending = false; failed.close();
            if (closed.get()) cleanup(); else fail("镜头不支持所选流配置");
        }
        @Override public void onClosed(CameraCaptureSession ended) {
            // Some HALs terminate configuration with onClosed after device.close().
            if (closed.get()) { sessionPending = false; cleanup(); }
        }
    };

    private final ImageReader.OnImageAvailableListener images = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader source) {
            Image image = null;
            boolean ownsDelivery = false;
            try {
                image = source.acquireLatestImage();
                if (image == null || closed.get() || !ready) return;
                if (!delivering.compareAndSet(false, true)) return;
                ownsDelivery = true;
                if (image.getWidth() != width || image.getHeight() != height)
                    throw new IOException("镜头帧尺寸发生变化");
                Image.Plane[] planes = image.getPlanes();
                if (planes.length != 3) throw new IOException("镜头未返回标准 YUV 视频帧");
                Yuv420Converter.toNv21(width, height,
                        planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                        planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                        planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(), nv21);
                final byte[] frame = nv21;
                if (worker.post(new Runnable() {
                    @Override public void run() {
                        try { if (!closed.get()) listener.onFrame(frame, width, height); }
                        finally { delivering.set(false); }
                    }
                })) ownsDelivery = false;
            } catch (Throwable failure) {
                if (!closed.get()) fail("镜头帧读取失败：" + message(failure));
            } finally {
                if (image != null) image.close();
                if (ownsDelivery) delivering.set(false);
            }
        }
    };

    private void fail(final String reason) {
        if (startupFailure == null) startupFailure = new IOException(reason);
        boolean notify = ready && !closed.get();
        close(); startupDone.countDown();
        if (notify) worker.post(new Runnable() {
            @Override public void run() { listener.onError(reason); }
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (Looper.myLooper() == control.getLooper()) cleanup();
        else {
            control.post(new Runnable() { @Override public void run() { cleanup(); } });
            awaitClosed(1500);
        }
    }

    private void cleanup() {
        CameraCaptureSession oldSession = session; session = null;
        if (oldSession != null) {
            try { oldSession.stopRepeating(); } catch (Throwable ignored) { }
            try { oldSession.close(); } catch (Throwable ignored) { }
        }
        CameraDevice oldDevice = device; device = null;
        if (oldDevice != null && oldDevice != closingDevice) {
            closingDevice = oldDevice;
            deviceClosePending = true;
            try { oldDevice.close(); } catch (Throwable ignored) { }
        }
        ImageReader oldReader = reader; reader = null;
        if (oldReader != null) try { oldReader.close(); } catch (Throwable ignored) { }
        // Keep the callback looper alive while an open/configure is in flight.
        // A late onOpened/onConfigured closes its own supplied handle above.
        if (!openPending && !sessionPending && !deviceClosePending) {
            finishClose(); fullyClosed.countDown(); controlThread.quitSafely();
        }
    }

    private boolean awaitClosed(long millis) {
        try { return fullyClosed.await(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    private static boolean contains(int[] values, int expected) {
        if (values != null) for (int value : values) if (value == expected) return true;
        return false;
    }
    private static Range<Integer> closestFps(Range<Integer>[] ranges, int fps) {
        if (ranges == null) return null;
        Range<Integer> best = null; long bestScore = Long.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            long score = Math.abs(range.getLower() - (long) fps) + Math.abs(range.getUpper() - (long) fps);
            if (score < bestScore) { best = range; bestScore = score; }
        }
        return best;
    }
    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
