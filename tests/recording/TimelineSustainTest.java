package com.oldphone.ipcam;

/** Continuous-only conditions record without an instant event, and stop when released. */
public final class TimelineSustainTest {
    public static void main(String[] args) throws Exception {
        RecordingTimelineTest.Sink sink = new RecordingTimelineTest.Sink();
        RecordingTimeline timeline = new RecordingTimeline(true, 1024, sink);
        timeline.setReady();
        timeline.setSustain(true);
        for (int i = 0; i <= 4; i++) timeline.accept(RecordingTimelineTest.frame(i, i % 2 == 0));
        if (sink.starts.size() != 1) throw new AssertionError("sustain starts without an instant event");
        timeline.setSustain(false);
        timeline.accept(RecordingTimelineTest.frame(6, true));
        if (sink.ends != 1) throw new AssertionError("releasing sustain stops at the next keyframe");
        timeline.accept(RecordingTimelineTest.frame(8, true));
        if (sink.starts.size() != 1) throw new AssertionError("recording stays stopped after sustain ends");
    }
}
