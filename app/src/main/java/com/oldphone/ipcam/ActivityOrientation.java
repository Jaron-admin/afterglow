package com.oldphone.ipcam;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.Surface;

/** Applies the user's four-way UI lock to secondary screens as well as capture. */
final class ActivityOrientation {
    private ActivityOrientation() { }
    static void apply(Activity activity) {
        if (!Prefs.orientationLocked()) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            return;
        }
        int current = activity.getWindowManager().getDefaultDisplay().getRotation();
        boolean portrait = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        boolean naturalPortrait = ((current == Surface.ROTATION_0 || current == Surface.ROTATION_180) && portrait)
                || ((current == Surface.ROTATION_90 || current == Surface.ROTATION_270) && !portrait);
        int[] requests = naturalPortrait ? new int[] {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } : new int[] {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        };
        activity.setRequestedOrientation(requests[Math.max(0, Math.min(3, Prefs.lockedDisplayRotation()))]);
    }
}
