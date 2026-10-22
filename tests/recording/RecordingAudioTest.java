package com.oldphone.ipcam;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.TimeZone;

/** Main-switch merge, the upgrade that keeps silent files, and the disabled sound trigger. */
public final class RecordingAudioTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        merge();
        upgrade();
        soundTrigger();
        summaries();
        System.out.println("PASS: microphone switch merge, silent-file upgrade, sound trigger disabled");
    }

    static void merge() {
        check(RecordingAudio.fileAudio(true, true), "mic and file flag both on");
        check(!RecordingAudio.fileAudio(false, true), "mic off drops the live track and the file");
        check(!RecordingAudio.fileAudio(false, false), "both off");
        check(RecordingAudio.recordAudioAfterUserToggle(true), "turning the switch on opts into file audio");
        check(!RecordingAudio.recordAudioAfterUserToggle(false), "turning the switch off clears file audio");
    }

    static void upgrade() {
        boolean microphone = true;
        boolean recordAudio = false;
        check(!RecordingAudio.fileAudio(microphone, recordAudio),
                "mic on with the old file switch off stays silent");
        check(!RecordingAudio.fileAudio(microphone, recordAudio),
                "opening settings does not rewrite the legacy flag");
        microphone = false;
        recordAudio = RecordingAudio.recordAudioAfterUserToggle(microphone);
        check(!recordAudio && !RecordingAudio.fileAudio(microphone, recordAudio),
                "the first user gesture off keeps the file silent");
        microphone = true;
        recordAudio = RecordingAudio.recordAudioAfterUserToggle(microphone);
        check(recordAudio && RecordingAudio.fileAudio(microphone, recordAudio),
                "the next gesture on makes the one switch own file audio");
    }

    static void soundTrigger() {
        check(!RecordingAudio.soundControlsEnabled(false), "sound trigger is disabled while mic is off");
        check(!RecordingAudio.soundSensitivityEnabled(false, true), "sensitivity follows the disabled trigger");
        String note = RecordingAudio.soundDisabledNote(false);
        check(note.contains("录制声音") && note.contains("声音触发"), "the note points at the main switch: " + note);
        check(RecordingAudio.soundControlsEnabled(true), "sound trigger is available once mic is on");
        check("".equals(RecordingAudio.soundDisabledNote(true)), "the note is hidden while the switch is on");
        check(RecordingAudio.soundSensitivityEnabled(true, true), "sensitivity is usable when sound is checked");
        check(!RecordingAudio.soundSensitivityEnabled(true, false), "an unchecked trigger disables its level");
    }

    static void summaries() {
        check("事件录像 · 画面+声音".equals(HomeEntrySummaries.recording(2, true, true)), "both triggers");
        check("事件录像 · 画面变动".equals(HomeEntrySummaries.recording(2, true, false)), "motion only");
        check("事件录像 · 声音触发".equals(HomeEntrySummaries.recording(2, false, true)), "sound only");
        check("事件录像 · 未设触发".equals(HomeEntrySummaries.recording(2, false, false)), "event without a trigger");
        check("连续录像".equals(HomeEntrySummaries.recording(1, true, true)), "continuous ignores triggers");
        check("录像已关闭".equals(HomeEntrySummaries.recording(0, true, true)), "off");
        check("1280×720 · 仅本地录像".equals(HomeEntrySummaries.stream(1280, 720, "仅本地录像")), "stream line");
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        long now = at(zone, 2026, 10, 21, 1, 0, 0);
        long todayEarly = at(zone, 2026, 10, 21, 0, 10, 0);
        long yesterday = at(zone, 2026, 10, 20, 23, 30, 0);
        String line = HomeEntrySummaries.today(Arrays.asList(
                new HomeEntrySummaries.Clip(todayEarly, 2L * 1024 * 1024),
                new HomeEntrySummaries.Clip(todayEarly + 60_000L, 2L * 1024 * 1024),
                new HomeEntrySummaries.Clip(todayEarly + 120_000L, 2L * 1024 * 1024),
                new HomeEntrySummaries.Clip(yesterday, 9L * 1024 * 1024)), now, zone);
        check("今天 3 段 · 6 MiB".equals(line), "today count skips yesterday: " + line);
        check("今天还没有录像".equals(HomeEntrySummaries.today(
                Collections.<HomeEntrySummaries.Clip>emptyList(), now, zone)), "empty day");
        check("今天还没有录像".equals(HomeEntrySummaries.today(null, now, zone)), "missing list");
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }
}
