package com.oldphone.ipcam;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One camera session. NV21 storage is borrowed only until onFrame returns. */
public abstract class CameraCapture {
    static final class UnsettledOpenException extends IOException {
        final CameraCapture capture;
        UnsettledOpenException(String message, CameraCapture capture) {
            super(message); this.capture = capture;
        }
    }
    public interface Listener {
        void onFrame(byte[] nv21, int width, int height);
        void onError(String message);
    }
    public final CameraCatalog.Entry entry;
    public final int width, height;
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
    private volatile CameraCatalog.Reservation reservation;

    protected CameraCapture(CameraCatalog.Entry entry, int width, int height) {
        this.entry = entry; this.width = width; this.height = height;
    }

    public static CameraCapture open(Context context, String cameraKey, int outputW, int outputH,
                                     int fps, Handler worker, Listener listener) throws IOException {
        if (context == null || worker == null || listener == null)
            throw new IllegalArgumentException("Camera context, worker and listener are required");
        if (outputW <= 0 || outputH <= 0 || fps <= 0)
            throw new IllegalArgumentException("Camera dimensions and frame rate must be positive");
        String key = cameraKey == null || cameraKey.length() == 0 ? "auto" : cameraKey;
        CameraCatalog.Reservation reservation = CameraCatalog.captureStarted();
        boolean handedOff = false;
        try {
            CameraCapture capture;
            if (key.startsWith("legacy:") || Build.VERSION.SDK_INT < 21) {
                capture = LegacyCameraBackend.open(key, outputW, outputH, fps, worker, listener);
            } else {
                List<CameraCatalog.Entry> entries = CameraCatalog.list(context);
                CameraCatalog.Entry selected = CameraCatalog.find(entries, key);
                if (selected == null && "auto".equals(key)) {
                    capture = LegacyCameraBackend.open("auto", outputW, outputH, fps, worker, listener);
                } else if (selected == null) {
                    throw new IOException("找不到所选镜头：" + key);
                } else if (selected.legacy) {
                    capture = LegacyCameraBackend.open(selected.key, outputW, outputH, fps, worker, listener);
                } else {
                    try {
                        capture = Camera2Backend.open(context, selected, outputW, outputH, fps, worker, listener);
                    } catch (IOException failure) {
                        if (!"auto".equals(key) || failure instanceof UnsettledOpenException)
                            throw failure;
                        capture = LegacyCameraBackend.open("auto", outputW, outputH, fps, worker, listener);
                    }
                }
            }
            capture.registerCapture(reservation);
            handedOff = true;
            return capture;
        } catch (UnsettledOpenException pending) {
            // Keep the catalog reservation until the late native callback is closed.
            pending.capture.registerCapture(reservation);
            handedOff = true;
            throw pending;
        } finally {
            if (!handedOff) reservation.close();
        }
    }

    private void registerCapture(CameraCatalog.Reservation reservation) {
        this.reservation = reservation;
        if (resourcesReleased.get()) reservation.close();
    }
    protected final void finishClose() {
        resourcesReleased.set(true);
        CameraCatalog.Reservation owned = reservation;
        if (owned != null) owned.close();
    }
    public abstract void close();
}
