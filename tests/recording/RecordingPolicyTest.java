package com.oldphone.ipcam;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

public final class RecordingPolicyTest {
    public static void main(String[] args) {
        RecordingSchedule allDay = RecordingSchedule.allDay();
        check(!allDay.limited() && allDay.allows(0) && allDay.allows(23 * 60 + 59),
                "missing window records all day");
        RecordingSchedule night = RecordingSchedule.daily(22 * 60, 7 * 60);
        check(night.allows(22 * 60) && night.allows(23 * 60) && night.allows(0),
                "overnight window includes the start and the hours after midnight");
        check(!night.allows(7 * 60) && !night.allows(12 * 60),
                "overnight window excludes the end minute and midday");
        RecordingSchedule day = RecordingSchedule.daily(8 * 60, 18 * 60);
        check(day.allows(8 * 60) && day.allows(17 * 60 + 59) && !day.allows(18 * 60)
                        && !day.allows(7 * 60 + 59),
                "same-day window includes the start and excludes the end");
        expectInvalid(new Runnable() { public void run() { RecordingSchedule.daily(10, 10); } },
                "equal start and end");
        expectInvalid(new Runnable() { public void run() { RecordingSchedule.daily(-1, 10); } },
                "negative minute");
        expectInvalid(new Runnable() { public void run() { RecordingSchedule.daily(0, 24 * 60); } },
                "minute past 23:59");
        expectInvalid(new Runnable() { public void run() { night.allows(1440); } },
                "minute of day past 23:59");

        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar clock = Calendar.getInstance(utc);
        clock.set(2026, Calendar.JANUARY, 15, 23, 30, 0);
        clock.set(Calendar.MILLISECOND, 0);
        check(night.allowsAt(clock.getTimeInMillis(), utc), "23:30 UTC is inside 22:00–07:00");
        clock.set(Calendar.HOUR_OF_DAY, 12);
        check(!night.allowsAt(clock.getTimeInMillis(), utc), "12:00 UTC is outside 22:00–07:00");

        check(RecordingChoices.indexOf(RecordingChoices.SEGMENT_SECONDS, 300) == 1,
                "five minutes is the second segment preset");
        check(RecordingChoices.indexOf(RecordingChoices.SEGMENT_SECONDS, 137) < 0,
                "a saved custom segment stays off the preset list");
        check(RecordingChoices.indexOf(RecordingChoices.QUOTA_GIB, 2) >= 0
                        && RecordingChoices.indexOf(RecordingChoices.RETENTION_DAYS, 9) < 0,
                "known quota is a preset and an unsaved retention day is custom");
        check(RecordingChoices.indexOf(null, 1) < 0, "missing presets are custom");

        check(DetectionLevels.soundOrMotion(false, 2, 0) == 0,
                "unset sound sensitivity keeps the current motion level");
        check(DetectionLevels.soundOrMotion(true, 2, 0) == 2,
                "a saved sound sensitivity is independent of motion");
        check(DetectionLevels.soundOrMotion(true, 9, 1) == 2, "sound level is clamped");

        String target = "/storage/emulated/0/Android/data/com.oldphone.ipcam/files/Movies/afterglow-recordings";
        String clip = "1700000000000-12345678-1234-1234-1234-123456789abc";
        RecordingUris.Parsed parsed = RecordingUris.parse(Arrays.asList(
                RecordingUris.encodeTarget(target), clip));
        check(target.equals(parsed.targetId) && clip.equals(parsed.clipId),
                "target paths round-trip as one URI segment");
        expectInvalid(new Runnable() {
            public void run() {
                RecordingUris.parse(Arrays.asList(RecordingUris.encodeTarget(target), "not-a-clip"));
            }
        }, "bad clip id");
        expectInvalid(new Runnable() {
            public void run() { RecordingUris.parse(Arrays.asList("zz", clip)); }
        }, "odd hex target");
        System.out.println("PASS RecordingPolicyTest");
    }

    private static void expectInvalid(Runnable action, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + " must be rejected");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
