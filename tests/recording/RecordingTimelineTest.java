package com.oldphone.ipcam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RecordingTimelineTest {
    static final class Sink implements RecordingTimeline.Sink {
        final List<Long> starts = new ArrayList<Long>();
        final List<Long> video = new ArrayList<Long>();
        final List<Long> audio = new ArrayList<Long>();
        final List<List<String>> segmentMarks = new ArrayList<List<String>>();
        int ends, marks;
        public void begin(long startUs) { starts.add(startUs); segmentMarks.add(new ArrayList<String>()); }
        public void sample(RecordingTimeline.Sample s, long relativeUs) {
            if (relativeUs < 0) throw new AssertionError("negative PTS");
            (s.video ? video : audio).add(relativeUs);
        }
        public void mark(String reason, long ptsUs) {
            marks++; segmentMarks.get(segmentMarks.size() - 1).add(reason + "@" + ptsUs);
        }
        public void finish() { ends++; }
    }
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
    static RecordingTimeline.Sample frame(long seconds, boolean key) {
        return new RecordingTimeline.Sample(true, new byte[20], seconds * 1000000L, key ? 1 : 0);
    }
    public static void main(String[] args) throws Exception {
        Sink eventSink = new Sink();
        RecordingTimeline event = new RecordingTimeline(true, 1024, eventSink);
        event.setReady();
        for (int i = 0; i <= 10; i++) event.accept(frame(i, i % 2 == 0));
        event.trigger("motion", 10000000L);
        check(eventSink.starts.size() == 1 && eventSink.starts.get(0) == 4000000L,
                "pre-roll must start at a retained decodable keyframe");
        check(eventSink.video.get(0) == 0L, "segment starts at zero");
        for (int i = 11; i <= 20; i++) event.accept(frame(i, i % 2 == 0));
        event.trigger("sound", 20000000L);
        for (int i = 21; i <= 36; i++) event.accept(frame(i, i % 2 == 0));
        check(eventSink.ends == 1 && eventSink.starts.size() == 1,
                "repeated triggers extend one event until 15 seconds after last trigger");
        check(eventSink.marks == 2, "event reasons retained");

        Sink continuousSink = new Sink();
        RecordingTimeline continuous = new RecordingTimeline(false, 1024, continuousSink);
        continuous.accept(frame(0, true));
        continuous.accept(new RecordingTimeline.Sample(false, new byte[4], 1000, 0));
        check(continuousSink.starts.isEmpty(), "all configured track formats required before begin");
        continuous.setReady();
        for (int i = 1; i <= 302; i++) continuous.accept(frame(i, i % 2 == 0));
        check(continuousSink.starts.size() == 2 && continuousSink.starts.get(1) == 300000000L,
                "continuous five-minute rollover begins at keyframe");
        check(continuousSink.audio.get(0) == 1000L, "audio shares the video segment origin");
        continuous.close();
        check(continuousSink.ends == 2, "close finalizes active segment once");

        Sink boundedSink = new Sink();
        RecordingTimeline bounded = new RecordingTimeline(true, 70, boundedSink);
        bounded.setReady();
        for (int i = 0; i <= 20; i++) bounded.accept(frame(i, i % 4 == 0));
        check(bounded.bufferedBytes() <= 70, "pre-roll bounded independently of capture duration");
        bounded.trigger("motion", 20000000L);
        check(boundedSink.starts.size() == 1 && boundedSink.starts.get(0) == 20000000L,
                "oversized GOPs cannot leave a dependent frame at pre-roll head");
        Sink noPreSink = new Sink();
        RecordingTimeline noPre = new RecordingTimeline(true, 4096, 0, 2_000_000,
                10_000_000, 1024 * 1024, noPreSink);
        noPre.setReady();
        noPre.accept(frame(0, true)); noPre.accept(frame(1, false));
        noPre.trigger("motion", 1_000_000);
        check(noPreSink.starts.isEmpty(), "zero pre-roll must not include pre-trigger frames");
        noPre.accept(frame(2, true));
        noPre.accept(frame(4, true));
        check(noPreSink.starts.size() == 1 && noPreSink.starts.get(0) == 2_000_000
                && noPreSink.ends == 1, "custom post-roll honored after next keyframe");
        Sink smallSink = new Sink();
        RecordingTimeline small = new RecordingTimeline(false, 4096, 5_000_000, 15_000_000,
                300_000_000, 100, smallSink);
        small.setReady();
        for (int i = 0; i <= 6; i++) small.accept(frame(i, true));
        check(smallSink.starts.size() == 2 && smallSink.starts.get(1) == 5_000_000,
                "file-size limit triggers keyframe rollover before the duration limit");
        Sink shortSink = new Sink();
        RecordingTimeline shortSegment = new RecordingTimeline(true, 4096, 30_000_000, 15_000_000,
                10_000_000, 1024 * 1024, shortSink);
        shortSegment.setReady();
        for (int i = 0; i <= 24; i++) shortSegment.accept(frame(i, i % 2 == 0));
        shortSegment.trigger("motion", 24_000_000);
        check(shortSink.starts.size() == 3, "large configured pre-roll also follows the segment duration");
        check(!shortSink.segmentMarks.get(0).contains("motion@24000000")
                && !shortSink.segmentMarks.get(1).contains("motion@24000000")
                && shortSink.segmentMarks.get(2).contains("motion@24000000"),
                "trigger at 24 seconds belongs to the 20-second segment, not the first pre-roll segment");
        Sink boundarySink = new Sink();
        RecordingTimeline boundary = new RecordingTimeline(false, 4096, 5_000_000, 15_000_000,
                10_000_000, 1024 * 1024, boundarySink);
        boundary.setReady();
        boundary.accept(frame(0, true)); boundary.accept(frame(9, false));
        boundary.trigger("sound-before", 9_500_000);
        boundary.trigger("sound-at", 10_000_000);
        // Audio can arrive ahead of the next video keyframe; it must not move an
        // exact-boundary event into the preceding segment.
        boundary.accept(new RecordingTimeline.Sample(false, new byte[4], 10_200_000, 0));
        boundary.accept(frame(10, true));
        check(boundarySink.segmentMarks.get(0).contains("sound-before@9500000")
                && !boundarySink.segmentMarks.get(0).contains("sound-at@10000000")
                && boundarySink.segmentMarks.get(1).contains("sound-at@10000000"),
                "9.5-second trigger belongs before the 10-second boundary; exact boundary belongs after");
        Sink tailSink = new Sink();
        RecordingTimeline tail = new RecordingTimeline(false, 4096, tailSink);
        tail.setReady(); tail.accept(frame(0, true)); tail.accept(frame(9, false));
        tail.trigger("sound-tail", 9_500_000);
        tail.trigger("not-recorded", 10_000_000);
        tail.accept(new RecordingTimeline.Sample(false, new byte[4], 9_600_000, 0));
        tail.close();
        check(tailSink.segmentMarks.get(0).contains("sound-tail@9500000")
                && !tailSink.segmentMarks.get(0).contains("not-recorded@10000000"),
                "close publishes pending sound through the written audio tail, excluding future events");
        Sink eventTailSink = new Sink();
        RecordingTimeline eventTail = new RecordingTimeline(true, 4096, 5_000_000, 1_000_000,
                10_000_000, 1024 * 1024, eventTailSink);
        eventTail.setReady(); eventTail.accept(frame(0, true)); eventTail.accept(frame(9, false));
        eventTail.trigger("sound-tail", 9_500_000);
        eventTail.accept(new RecordingTimeline.Sample(false, new byte[4], 9_600_000, 0));
        eventTail.accept(frame(11, false));
        check(eventTailSink.ends == 1 && eventTailSink.segmentMarks.get(0).contains("sound-tail@9500000"),
                "event expiration publishes the valid final audio-tail marker");

        Sink blocked = new Sink();
        RecordingTimeline blockedTimeline = new RecordingTimeline(false, 4096, blocked);
        blockedTimeline.setReady();
        blockedTimeline.setArmed(false);
        blockedTimeline.accept(frame(0, true));
        blockedTimeline.accept(frame(2, true));
        check(blocked.starts.isEmpty(), "continuous recording stays idle outside the window");

        Sink window = new Sink();
        RecordingTimeline windowed = new RecordingTimeline(false, 4096, 0, 15_000_000,
                3_600_000_000L, 1024 * 1024, window);
        windowed.setReady();
        windowed.accept(frame(0, true));
        windowed.accept(frame(1, false));
        windowed.setArmed(false);
        windowed.accept(frame(2, false));
        check(window.ends == 0 && window.video.size() == 3,
                "outside the window the open segment continues until a keyframe");
        windowed.accept(frame(4, true));
        check(window.ends == 1 && window.starts.size() == 1 && window.video.size() == 3,
                "the next keyframe finishes the segment and is not written into it");
        windowed.accept(frame(6, true));
        windowed.accept(frame(8, false));
        check(window.starts.size() == 1 && window.video.size() == 3,
                "a closed window does not open another segment");
        windowed.setArmed(true);
        windowed.accept(frame(10, true));
        check(window.starts.size() == 2 && window.starts.get(1) == 10_000_000L,
                "re-entering the window starts at the next keyframe");

        Sink outside = new Sink();
        RecordingTimeline outsideEvent = new RecordingTimeline(true, 4096, 0, 15_000_000,
                3_600_000_000L, 1024 * 1024, outside);
        outsideEvent.setReady();
        outsideEvent.setArmed(false);
        outsideEvent.accept(frame(0, true));
        outsideEvent.trigger("motion", 0);
        outsideEvent.accept(frame(2, true));
        check(outside.starts.isEmpty(), "events outside the window do not start a clip");

        Sink mid = new Sink();
        RecordingTimeline midEvent = new RecordingTimeline(true, 4096, 0, 30_000_000,
                3_600_000_000L, 1024 * 1024, mid);
        midEvent.setReady();
        midEvent.accept(frame(0, true));
        midEvent.accept(frame(1, false));
        midEvent.trigger("motion", 1_000_000);
        midEvent.accept(frame(2, true));
        check(mid.starts.size() == 1, "event starts while the window is open");
        midEvent.setArmed(false);
        midEvent.accept(frame(3, false));
        midEvent.accept(frame(4, true));
        check(mid.ends == 1 && mid.starts.size() == 1,
                "leaving the window finishes the event at the next keyframe");
        midEvent.accept(frame(20, true));
        check(mid.starts.size() == 1 && mid.ends == 1,
                "post-roll does not continue after the window has closed");
        System.out.println("RecordingTimelineTest PASS: keyframes, shared PTS, merged events, segmentation, bounded pre-roll, schedule window");
    }
}
