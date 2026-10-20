package com.oldphone.ipcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启。老手机做监控通常是插着电丢在角落，开机后自动开流才省心。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            return;
        }

        Prefs.init(context);
        if (!Prefs.getBool(Prefs.KEY_BOOT, false)) {
            return;
        }

        try {
            Intent service = new Intent(context, CameraStreamService.class);
            service.setAction(CameraStreamService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.i(TAG, "auto start on boot");
        } catch (Throwable t) {
            Log.e(TAG, "boot start failed", t);
        }
    }
}
