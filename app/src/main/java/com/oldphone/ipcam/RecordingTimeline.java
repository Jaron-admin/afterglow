package com.oldphone.ipcam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Codec-independent recording boundaries. Accessed only by the disk worker. */
final class RecordingTimeline {
    static final long PRE_US = 5_000_000L;
    static final long POST_US = 15_000_000L;
    static final long SEGMENT_US = 300_000_000L;
    static final class Sample {
        final boolean video;
        final byte[] data;
        final long ptsUs;
        final int flags;
        Sample(boolean video, byte[] data, long ptsUs, int flags) {
            this.video = video; this.data = data; this.ptsUs = ptsUs; this.flags = flags;
        }
        boolean key() { return video && (flags & 1) != 0; }
    }
    interface Sink {
        void begin(long startUs) throws IOException;
        void sample(Sample sample, long relativeUs) throws IOException;
        void mark(String reason, long ptsUs) throws IOException;
        void finish() throws IOException;
    }
    private final boolean eventMode;
    private final long byteLimit;
    private final long preUs, postUs, segmentUs, maxSegmentBytes;
    private final Sink sink;
    private final List<Sample> ring = new ArrayList<Sample>();
    private long ringBytes, latestUs = -1, segmentStartUs, eventUntilUs = -1, eventFirstUs = -1, segmentBytes;
    private long writtenVideoUs = -1;
    private long writtenMediaUs = -1;
    private boolean ready, active, closed;
    private final List<Marker> pendingMarks = new ArrayList<Marker>();
    private static final class Marker {
        final String reason; final long ptsUs;
        Marker(String reason, long ptsUs) { this.reason = reason; this.ptsUs = ptsUs; }
    }
    RecordingTimeline(boolean eventMode, long byteLimit, Sink sink) {
        this(eventMode, byteLimit, PRE_US, POST_US, SEGMENT_US, Long.MAX_VALUE, sink);
    }
    RecordingTimeline(boolean eventMode, long byteLimit, long preUs, long postUs,
                      long segmentUs, long maxSegmentBytes, Sink sink) {
        this.eventMode = eventMode; this.byteLimit = byteLimit; this.sink = sink;
        this.preUs = preUs; this.postUs = postUs; this.segmentUs = segmentUs;
        this.maxSegmentBytes = maxSegmentBytes;
    }
    long bufferedBytes() { return ringBytes; }
    void setReady() throws IOException {
        ready = true;
        maybeStart();
    }
    void trigger(String reason, long ptsUs) throws IOException {
        if (closed) return;
        if (eventMode) {
            if (eventUntilUs < 0 || ptsUs > eventUntilUs) eventFirstUs = ptsUs;
            eventUntilUs = Math.max(eventUntilUs, ptsUs + postUs);
        }
        if (pendingMarks.size() < 64) pendingMarks.add(new Marker(reason, ptsUs));
        if (active) publishMarksThrough(writtenVideoUs);
        else maybeStart();
    }
    void accept(Sample sample) throws IOException {
        if (closed || sample.data.length == 0 || (sample.flags & 2) != 0) return;
        latestUs = Math.max(latestUs, sample.ptsUs);
        ring.add(sample); ringBytes += sample.data.length;
        trimRing();
        if (!active) { maybeStart(); return; }
        acceptActive(sample);
    }
    private void acceptActive(Sample sample) throws IOException {
        if (sample.ptsUs < segmentStartUs) return;
        if (eventMode && sample.ptsUs > eventUntilUs) {
            // Audio may be ahead of video. Wait for video to end the segment,
            // but never write audio past the event's post-roll deadline.
            if (sample.video) { finish(); eventUntilUs = eventFirstUs = -1; }
            return;
        }
        if (sample.key() && (sample.ptsUs - segmentStartUs >= segmentUs
                || segmentBytes >= maxSegmentBytes - Math.min(maxSegmentBytes / 10, 1024 * 1024))) {
            // The arriving keyframe closes the preceding video's time interval.
            // A sound between its last frame and this key belongs to that old
            // segment; a sound exactly on the boundary belongs to the new one.
            publishMarks(sample.ptsUs, false);
            finish(false);
            begin(sample.ptsUs);
            if (eventMode) sink.mark("事件延续", sample.ptsUs);
        } else if (sample.video && sample.ptsUs - segmentStartUs > segmentUs + 15_000_000L) {
            finish();
            throw new IOException("编码器长时间未提供关键帧，录像已暂停");
        }
        writeSample(sample);
        // Video determines segment ownership. Audio may arrive ahead of a keyframe
        // and must not attach a future trigger to the segment about to be closed.
        if (sample.video) publishMarksThrough(sample.ptsUs);
    }
    private void maybeStart() throws IOException {
        if (!ready || active || closed || (eventMode && eventUntilUs < 0)) return;
        if (eventMode && latestUs > eventUntilUs) {
            pendingMarks.clear(); eventUntilUs = -1; return;
        }
        long keyUs = Long.MAX_VALUE;
        for (Sample s : ring) if (s.key()
                && (!eventMode || preUs > 0 || s.ptsUs >= eventFirstUs)) keyUs = Math.min(keyUs, s.ptsUs);
        if (keyUs == Long.MAX_VALUE) return;
        begin(keyUs);
        List<Sample> ordered = new ArrayList<Sample>(ring);
        Collections.sort(ordered, new Comparator<Sample>() {
            public int compare(Sample a, Sample b) {
                int order = Long.compare(a.ptsUs, b.ptsUs);
                return order != 0 ? order : (a.video == b.video ? 0 : a.video ? -1 : 1);
            }
        });
        for (Sample s : ordered) {
            if (s.ptsUs >= keyUs && (!eventMode || s.ptsUs <= eventUntilUs))
                acceptActive(s);
        }
    }
    private void begin(long ptsUs) throws IOException {
        sink.begin(ptsUs);
        segmentStartUs = ptsUs;
        segmentBytes = 0;
        writtenVideoUs = writtenMediaUs = -1;
        active = true;
    }
    private void writeSample(Sample sample) throws IOException {
        if (segmentBytes > maxSegmentBytes - sample.data.length) {
            finish();
            throw new IOException("单段录像已到大小上限但尚无新关键帧，录像已暂停");
        }
        sink.sample(sample, sample.ptsUs - segmentStartUs);
        segmentBytes += sample.data.length;
        if (sample.video) writtenVideoUs = sample.ptsUs;
        writtenMediaUs = Math.max(writtenMediaUs, sample.ptsUs);
    }
    private void publishMarksThrough(long ptsUs) throws IOException {
        publishMarks(ptsUs, true);
    }
    private void publishMarks(long ptsUs, boolean inclusive) throws IOException {
        Iterator<Marker> iterator = pendingMarks.iterator();
        while (iterator.hasNext()) {
            Marker marker = iterator.next();
            if (marker.ptsUs < ptsUs || (inclusive && marker.ptsUs == ptsUs)) {
                sink.mark(marker.reason, marker.ptsUs);
                iterator.remove();
            }
        }
    }
    private void finish() throws IOException {
        finish(true);
    }
    private void finish(boolean publishTail) throws IOException {
        if (!active) return;
        if (publishTail) publishMarksThrough(writtenMediaUs);
        active = false;
        sink.finish();
    }
    void close() throws IOException {
        if (closed) return;
        closed = true;
        finish();
        ring.clear(); ringBytes = 0; pendingMarks.clear();
    }
    private void trimRing() {
        long keepKeyUs = Long.MIN_VALUE;
        long oldestKeyUs = Long.MAX_VALUE;
        for (Sample s : ring) if (s.key()) {
            oldestKeyUs = Math.min(oldestKeyUs, s.ptsUs);
            if (s.ptsUs <= latestUs - preUs) keepKeyUs = Math.max(keepKeyUs, s.ptsUs);
        }
        if (keepKeyUs == Long.MIN_VALUE) keepKeyUs = oldestKeyUs;
        if (keepKeyUs != Long.MAX_VALUE) removeBefore(keepKeyUs);
        while (ringBytes > byteLimit) {
            long firstKey = Long.MAX_VALUE, nextKey = Long.MAX_VALUE;
            for (Sample s : ring) if (s.key()) firstKey = Math.min(firstKey, s.ptsUs);
            for (Sample s : ring) if (s.key() && s.ptsUs > firstKey)
                nextKey = Math.min(nextKey, s.ptsUs);
            if (nextKey == Long.MAX_VALUE) { ring.clear(); ringBytes = 0; break; }
            removeBefore(nextKey);
        }
    }
    private void removeBefore(long ptsUs) {
        Iterator<Sample> it = ring.iterator();
        while (it.hasNext()) {
            Sample s = it.next();
            if (s.ptsUs < ptsUs) { ringBytes -= s.data.length; it.remove(); }
        }
    }
}
