package com.oldphone.ipcam;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** One-line status on the home entry cards. Dates follow the local calendar day. */
public final class HomeEntrySummaries {
    public static final class Clip {
        public final long startMs;
        public final long bytes;
        public Clip(long startMs, long bytes) {
            this.startMs = startMs;
            this.bytes = bytes;
        }
    }

    private HomeEntrySummaries() {}

    public static String recording(int mode, boolean motion, boolean sound) {
        if (mode <= 0) return "录像已关闭";
        if (mode == 1) return "连续录像";
        if (motion && sound) return "事件录像 · 画面+声音";
        if (motion) return "事件录像 · 画面变动";
        if (sound) return "事件录像 · 声音触发";
        return "事件录像 · 未设触发";
    }

    public static String stream(int width, int height, String modeLabel) {
        String mode = modeLabel == null ? "" : modeLabel;
        return width + "×" + height + " · " + mode;
    }

    public static String today(List<Clip> clips, long nowMs, TimeZone zone) {
        if (zone == null) throw new IllegalArgumentException("zone");
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMs);
        Calendar stamp = Calendar.getInstance(zone);
        int count = 0;
        long bytes = 0;
        if (clips != null) {
            for (Clip clip : clips) {
                if (clip == null) continue;
                stamp.setTimeInMillis(clip.startMs);
                if (now.get(Calendar.YEAR) != stamp.get(Calendar.YEAR)
                        || now.get(Calendar.DAY_OF_YEAR) != stamp.get(Calendar.DAY_OF_YEAR)) continue;
                count++;
                if (clip.bytes > 0) bytes += clip.bytes;
            }
        }
        if (count == 0) return "今天还没有录像";
        return "今天 " + count + " 段 · " + amount(bytes);
    }

    static String amount(long bytes) {
        double mib = bytes / (1024d * 1024d);
        if (mib < 10 && Math.abs(mib - Math.rint(mib)) >= 0.05)
            return String.format(Locale.US, "%.1f MiB", mib);
        return String.format(Locale.US, "%.0f MiB", mib);
    }
}
