package com.oldphone.ipcam;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Start, stop and reconfigure without dropping a running stream on a lens change. */
public final class StreamControls {
    private StreamControls() {}

    public static void start(Context context) {
        Intent intent = new Intent(context, CameraStreamService.class).setAction(CameraStreamService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, CameraStreamService.class)
                .setAction(CameraStreamService.ACTION_STOP));
    }

    public static void reconfigure(Context context) {
        context.startService(new Intent(context, CameraStreamService.class)
                .setAction(CameraStreamService.ACTION_RECONFIGURE));
    }

    public static boolean streaming() {
        return CameraStreamService.running && Prefs.mode() != Prefs.MODE_LOCAL;
    }

    public static void setStreaming(Context context, boolean on) {
        if (on) {
            Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_BOTH);
            if (CameraStreamService.running) reconfigure(context);
            else start(context);
            return;
        }
        if (Prefs.recordingMode() != 0 && CameraStreamService.running) {
            Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_LOCAL);
            reconfigure(context);
            return;
        }
        stop(context);
    }

    public static void setRecording(Context context, boolean on) {
        if (on) {
            if (Prefs.recordingMode() == 0) Prefs.setInt(Prefs.KEY_RECORDING, 2);
            if (!CameraStreamService.running) {
                if (!streaming()) Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_LOCAL);
                start(context);
            } else {
                reconfigure(context);
            }
            return;
        }
        Prefs.setInt(Prefs.KEY_RECORDING, 0);
        if (!CameraStreamService.running || Prefs.mode() == Prefs.MODE_LOCAL) stop(context);
        else reconfigure(context);
    }
}
