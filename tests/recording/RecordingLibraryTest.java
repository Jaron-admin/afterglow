package com.oldphone.ipcam;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

/** Reason chips, day folders and the public export name. */
public final class RecordingLibraryTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        labels();
        days();
        exportNames();
        System.out.println("PASS: reason labels, day groups and export names");
    }

    static void labels() {
        List<RecordingReasonLabels.Label> both = RecordingReasonLabels.labels("sound,motion", "ignored");
        check(both.size() == 2, "both triggers");
        check("motion".equals(both.get(0).code) && "画面变动".equals(both.get(0).text), "motion first");
        check("sound".equals(both.get(1).code) && "声音触发".equals(both.get(1).text), "sound second");
        List<RecordingReasonLabels.Label> fromText = RecordingReasonLabels.labels("",
                "画面变动 · 12 秒\n声响 · 0.4 秒");
        check(fromText.size() == 2, "duration lines still map");
        check("画面变动".equals(fromText.get(0).text) && !fromText.get(0).text.contains("秒"),
                "motion chip has no duration");
        check("声音触发".equals(fromText.get(1).text) && !fromText.get(1).text.contains("0.4"),
                "sound chip has no duration");
        List<RecordingReasonLabels.Label> timed = RecordingReasonLabels.labels("schedule", "画面变动 · 3 秒");
        check(timed.size() == 1 && "定时录制".equals(timed.get(0).text), "stored code wins over the sentence");
        check(RecordingReasonLabels.labels("", "commotion").isEmpty(), "motion is not a substring");
        check(RecordingReasonLabels.labels("manual", "").isEmpty(), "unknown code is not a reason");
        check(RecordingReasonLabels.labels(null, null).isEmpty(), "missing fields");
    }

    static void days() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        long now = at(zone, 2026, 10, 21, 1, 0, 0);
        long today = at(zone, 2026, 10, 21, 0, 30, 0);
        long yesterday = at(zone, 2026, 10, 20, 23, 30, 0);
        long earlier = at(zone, 2026, 10, 2, 8, 0, 0);
        long lastYear = at(zone, 2025, 12, 31, 12, 0, 0);
        List<RecordingDayGroups.Section> sections = RecordingDayGroups.group(Arrays.asList(
                new RecordingDayGroups.Item("early", earlier),
                new RecordingDayGroups.Item("y", yesterday),
                new RecordingDayGroups.Item("old", lastYear),
                new RecordingDayGroups.Item("t", today)), now, zone);
        check(sections.size() == 4, "four local days");
        check("今天".equals(sections.get(0).title) && "2026-10-21".equals(sections.get(0).day)
                && "t".equals(sections.get(0).items.get(0).id), "today is first even just after midnight");
        check("昨天".equals(sections.get(1).title) && "y".equals(sections.get(1).items.get(0).id),
                "yesterday is the calendar day, not the last 24 hours");
        check("10月2日".equals(sections.get(2).title), "same year omits the year");
        check("2025年12月31日".equals(sections.get(3).title) && "2025-12-31".equals(sections.get(3).day),
                "another year keeps the year");
        check(RecordingDayGroups.group(null, now, zone).isEmpty(), "empty list");
        List<RecordingDayGroups.Section> ordered = RecordingDayGroups.group(Arrays.asList(
                new RecordingDayGroups.Item("older", today),
                new RecordingDayGroups.Item("newer", today + 60_000L)), now + 3_600_000L, zone);
        check(ordered.size() == 1 && "newer".equals(ordered.get(0).items.get(0).id)
                && "older".equals(ordered.get(0).items.get(1).id), "newest clip inside the day is first");
    }

    static void exportNames() {
        check("Movies/Afterglow".equals(RecordingExport.RELATIVE_PATH), "gallery folder");
        check(RecordingExport.needsLegacyWritePermission(19)
                && RecordingExport.needsLegacyWritePermission(28)
                && !RecordingExport.needsLegacyWritePermission(29)
                && !RecordingExport.needsLegacyWritePermission(33), "permission only before Android 10");
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        String name = RecordingExport.displayName(at(zone, 2026, 10, 21, 13, 40, 5), "1700-abcd", zone);
        check("Afterglow-20261021-134005-abcd.mp4".equals(name), "stable file name: " + name);
        check(!name.contains("/") && !name.contains(" "), "name is a single file");
        check(name.equals(RecordingExport.uniqueName(Collections.<String>emptySet(), name)), "unused name stays");
        check("Afterglow-20261021-134005-abcd-2.mp4".equals(
                RecordingExport.uniqueName(new HashSet<String>(Collections.singleton(name)), name)),
                "collision gets a suffix");
        HashSet<String> taken = new HashSet<String>();
        taken.add(name);
        taken.add("Afterglow-20261021-134005-abcd-2.mp4");
        check("Afterglow-20261021-134005-abcd-3.mp4".equals(RecordingExport.uniqueName(taken, name)),
                "later collisions keep incrementing");
    }

    static long at(TimeZone zone, int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }
}
