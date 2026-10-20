package com.oldphone.ipcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;
import android.view.WindowManager;

/** Physical device angle, independent of the system screen-rotation preference. */
public final class DeviceOrientationMonitor {
    public interface Listener { void onOrientation(int degrees); }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OrientationEventListener sensor;
    private final Listener listener;
    private final OrientationStability stability;
    private boolean started;
    private final Runnable commit = new Runnable() {
        @Override public void run() {
            if (started && stability.commit()) listener.onOrientation(stability.current());
        }
    };
    public DeviceOrientationMonitor(Context context, Listener listener) {
        this.listener = listener;
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int display = manager == null ? 0 : manager.getDefaultDisplay().getRotation();
        stability = new OrientationStability((360 - display * 90) % 360);
        sensor = new OrientationEventListener(context.getApplicationContext()) {
            @Override public void onOrientationChanged(int angle) {
                int previous = stability.pending();
                stability.observe(angle);
                if (stability.pending() != previous) {
                    handler.removeCallbacks(commit);
                    if (stability.pending() != stability.current()) handler.postDelayed(commit, 350);
                }
            }
        };
    }
    public void start() {
        if (started) return;
        started = true;
        if (sensor.canDetectOrientation()) sensor.enable();
        listener.onOrientation(stability.current());
    }
    public void stop() {
        started = false;
        sensor.disable();
        handler.removeCallbacks(commit);
        stability.cancelPending();
    }
    public int current() { return stability.current(); }
}
