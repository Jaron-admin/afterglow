package com.oldphone.ipcam;

import java.util.Calendar;
import java.util.TimeZone;

/** Local daily recording window. An absent window records all day. */
public final class RecordingSchedule {
    private final boolean limited;
    private final int startMinute;
    private final int endMinute;

    private RecordingSchedule(boolean limited, int startMinute, int endMinute) {
        this.limited = limited;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    public static RecordingSchedule allDay() {
        return new RecordingSchedule(false, 0, 0);
    }

    /** Start is included and end is excluded. Equal endpoints are not a window. */
    public static RecordingSchedule daily(int startMinute, int endMinute) {
        if (!validMinute(startMinute) || !validMinute(endMinute) || startMinute == endMinute)
            throw new IllegalArgumentException("录像时段的开始和结束要落在 00:00–23:59，并且不能相同");
        return new RecordingSchedule(true, startMinute, endMinute);
    }

    public boolean limited() { return limited; }
    public int startMinute() { return startMinute; }
    public int endMinute() { return endMinute; }

    public boolean allows(int minuteOfDay) {
        if (!validMinute(minuteOfDay)) throw new IllegalArgumentException("分钟超出一天");
        if (!limited) return true;
        if (startMinute < endMinute)
            return minuteOfDay >= startMinute && minuteOfDay < endMinute;
        return minuteOfDay >= startMinute || minuteOfDay < endMinute;
    }

    public boolean allowsAt(long epochMs, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone == null ? TimeZone.getDefault() : zone);
        calendar.setTimeInMillis(epochMs);
        return allows(calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE));
    }

    public boolean allowsNow(long epochMs) {
        return allowsAt(epochMs, TimeZone.getDefault());
    }

    private static boolean validMinute(int minute) {
        return minute >= 0 && minute <= 1439;
    }
}
