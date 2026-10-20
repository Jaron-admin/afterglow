package com.oldphone.ipcam;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** KitKat Camera1 path; all camera operations and borrowed frame callbacks use worker. */
final class LegacyCameraBackend extends CameraCapture implements Camera.PreviewCallback {
    private Camera camera;
    private TextureDrainer drainer;
    private SurfaceTexture texture;
    private final Handler worker;
    private final Listener listener;

    private LegacyCameraBackend(CameraCatalog.Entry entry, int width, int height,
                                 Handler worker, Listener listener, Camera camera) {
        super(entry, width, height);
        this.worker = worker; this.listener = listener; this.camera = camera;
    }

    static CameraCapture open(final String key, final int outputW, final int outputH,
                              final int fps, final Handler worker, final Listener listener) throws IOException {
        if (Looper.myLooper() == worker.getLooper()) return openOnWorker(key, outputW, outputH, fps, worker, listener);
        final Object guard = new Object();
        final CameraCapture[] result = new CameraCapture[1];
        final IOException[] failure = new IOException[1];
        final boolean[] expired = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        final PendingCapture pending = new PendingCapture(key);
        if (!worker.post(new Runnable() {
            @Override public void run() {
                synchronized (guard) {
                    if (expired[0]) { pending.settled(); done.countDown(); return; }
                }
                CameraCapture capture = null;
                try { capture = openOnWorker(key, outputW, outputH, fps, worker, listener); }
                catch (IOException error) { failure[0] = error; }
                synchronized (guard) {
                    if (expired[0]) {
                        if (capture != null) capture.close();
                        pending.settled();
                    } else {
                        result[0] = capture;
                        if (capture == null) pending.settled();
                    }
                }
                done.countDown();
            }
        })) throw new IOException("相机工作线程已停止");
        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                abandon(guard, expired, result, worker, pending);
                throw new CameraCapture.UnsettledOpenException("打开摄像头超时，等待工作线程释放", pending);
            }
        } catch (InterruptedException interrupted) {
            abandon(guard, expired, result, worker, pending);
            Thread.currentThread().interrupt();
            throw new CameraCapture.UnsettledOpenException("打开摄像头已取消，等待工作线程释放", pending);
        }
        if (failure[0] != null) throw failure[0];
        return result[0];
    }

    private static final class PendingCapture extends CameraCapture {
        PendingCapture(String key) {
            super(new CameraCatalog.Entry(key, "等待镜头释放", "", null, 2, 0, 0, 0, new int[0][], true), 0, 0);
        }
        void settled() { closed.set(true); finishClose(); }
        @Override public void close() { /* Only the actual worker completion can settle ownership. */ }
    }

    private static void abandon(Object guard, boolean[] expired, CameraCapture[] result,
                                Handler worker, final PendingCapture pending) {
        synchronized (guard) {
            expired[0] = true;
            final CameraCapture ready = result[0];
            if (ready != null) worker.post(new Runnable() {
                @Override public void run() { ready.close(); pending.settled(); }
            });
        }
    }

    private static CameraCapture openOnWorker(String key, int outputW, int outputH, int fps,
                                              Handler worker, Listener listener) throws IOException {
        Camera camera = null;
        LegacyCameraBackend capture = null;
        try {
            int id;
            if ("auto".equals(key)) {
                CameraPicker.Pick pick = CameraPicker.open(Prefs.CAM_AUTO);
                camera = pick.camera; id = pick.id;
            } else if ("facing:front".equals(key) || "facing:back".equals(key)) {
                int wanted = "facing:front".equals(key) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
                id = -1;
                for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                    Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(i, info);
                    if (info.facing == wanted) { id = i; break; }
                }
                if (id < 0) throw new IOException("找不到所选朝向的镜头");
                camera = Camera.open(id);
            } else {
                if (!key.startsWith("legacy:")) throw new IOException("此系统不支持所选镜头：" + key);
                id = Integer.parseInt(key.substring("legacy:".length()));
                camera = Camera.open(id);
            }
            Camera.CameraInfo info = new Camera.CameraInfo();
            if (id >= 0) Camera.getCameraInfo(id, info);
            Camera.Parameters params = camera.getParameters();
            CameraCatalog.Entry entry = CameraCatalog.legacyEntry(id, info, params);
            int[] size = CameraCatalog.chooseSourceSize(entry, outputW, outputH);
            params.setPreviewSize(size[0], size[1]);
            List<Integer> formats = params.getSupportedPreviewFormats();
            if (formats != null && !formats.contains(ImageFormat.NV21)) throw new IOException("镜头不支持 NV21 视频帧");
            params.setPreviewFormat(ImageFormat.NV21);
            int[] range = closestFps(params.getSupportedPreviewFpsRange(), fps);
            if (range != null) params.setPreviewFpsRange(range[0], range[1]);
            List<String> focus = params.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            if (params.isVideoStabilizationSupported()) params.setVideoStabilization(false);
            try { params.setRecordingHint(true); } catch (Throwable ignored) { }
            camera.setParameters(params);
            Camera.Size actual = camera.getParameters().getPreviewSize();
            if (actual.width < 2 || actual.height < 2 || actual.width > 4096 || actual.height > 4096
                    || (long) actual.width * actual.height > CameraCatalog.MAX_SOURCE_PIXELS
                    || (actual.width & 1) != 0 || (actual.height & 1) != 0)
                throw new IOException("镜头返回的流尺寸超出可处理范围");
            capture = new LegacyCameraBackend(entry, actual.width, actual.height, worker, listener, camera);
            capture.start();
            return capture;
        } catch (Throwable failure) {
            if (capture != null) capture.close();
            else if (camera != null) try { camera.release(); } catch (Throwable ignored) { }
            throw failure instanceof IOException ? (IOException) failure : new IOException("打开镜头失败：" + failure.getMessage(), failure);
        }
    }

    private void start() throws IOException {
        camera.setDisplayOrientation(0);
        drainer = new TextureDrainer(); drainer.start();
        texture = drainer.awaitTexture(3000);
        if (texture == null) throw new IOException("无法建立摄像头预览缓冲");
        texture.setDefaultBufferSize(width, height);
        camera.setPreviewTexture(texture);
        camera.setErrorCallback(new Camera.ErrorCallback() {
            @Override public void onError(int error, Camera source) {
                if (!closed.get()) {
                    listener.onError("摄像头错误：" + error);
                    close();
                }
            }
        });
        camera.setPreviewCallbackWithBuffer(this);
        int length = width * height * 3 / 2;
        camera.addCallbackBuffer(new byte[length]); camera.addCallbackBuffer(new byte[length]);
        camera.startPreview();
    }

    @Override public void onPreviewFrame(byte[] frame, Camera source) {
        if (closed.get() || source != camera || frame == null) return;
        try { listener.onFrame(frame, width, height); }
        finally {
            if (!closed.get() && source == camera) try { source.addCallbackBuffer(frame); } catch (Throwable ignored) { }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (Looper.myLooper() == worker.getLooper()) closeOnWorker();
        else {
            final CountDownLatch done = new CountDownLatch(1);
            if (worker.post(new Runnable() {
                @Override public void run() { try { closeOnWorker(); } finally { done.countDown(); } }
            })) {
                try { done.await(1500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else closeOnWorker();
        }
    }

    private void closeOnWorker() {
        Camera old = camera; camera = null;
        if (old != null) {
            try { old.setPreviewCallbackWithBuffer(null); } catch (Throwable ignored) { }
            try { old.setErrorCallback(null); } catch (Throwable ignored) { }
            try { old.stopPreview(); } catch (Throwable ignored) { }
            try { old.release(); } catch (Throwable ignored) { }
        }
        if (drainer != null) { drainer.stop(); drainer = null; }
        if (texture != null) { try { texture.release(); } catch (Throwable ignored) { } texture = null; }
        finishClose();
    }

    private static int[] closestFps(List<int[]> ranges, int fps) {
        if (ranges == null) return null;
        int[] best = null; long bestScore = Long.MAX_VALUE; long target = fps * 1000L;
        for (int[] range : ranges) {
            long score = Math.abs(range[1] - target) + Math.abs(range[0] - target);
            if (score < bestScore) { best = range; bestScore = score; }
        }
        return best;
    }
}
