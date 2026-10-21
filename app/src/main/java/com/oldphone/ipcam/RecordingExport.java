package com.oldphone.ipcam;

import java.util.Calendar;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Copies land in the public Movies/Afterglow folder. */
public final class RecordingExport {
    public static final String RELATIVE_PATH = "Movies/Afterglow";
    public static final String FOLDER = "Afterglow";

    private RecordingExport() {}

    public static boolean needsLegacyWritePermission(int sdkInt) {
        return sdkInt < 29;
    }

    public static String displayName(long startMs, String id, TimeZone zone) {
        if (zone == null) throw new IllegalArgumentException("zone");
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(startMs);
        return String.format(Locale.US, "Afterglow-%04d%02d%02d-%02d%02d%02d-%s.mp4",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND), shortId(id));
    }

    public static String uniqueName(Set<String> taken, String name) {
        if (taken == null || name == null || name.length() == 0)
            throw new IllegalArgumentException("导出文件名无效");
        if (!taken.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String candidate = stem + "-" + i + extension;
            if (!taken.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("导出文件名已用尽");
    }

    private static String shortId(String id) {
        StringBuilder letters = new StringBuilder();
        if (id != null) {
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                    letters.append(c);
            }
        }
        if (letters.length() == 0) return "clip";
        return letters.substring(Math.max(0, letters.length() - 4));
    }
}
