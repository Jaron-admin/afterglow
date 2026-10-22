package com.oldphone.ipcam;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Fans encoded samples into a bounded, independent disk worker. Never owns capture. */
public final class RecordingManager implements AutoCloseable {
    public static final int MODE_CONTINUOUS = 1;
    public static final int MODE_EVENT = 2;
    private static final int QUEUE_BYTES = 8 * 1024 * 1024;
    private static final int RING_BYTES = 8 * 1024 * 1024;
    private static final long MP4_HEADROOM = 256 * 1024;
    public interface Listener { void onStatus(String status, boolean failed); }
    private final Context context;
    private final String targetId;
    private final RecordingOptions options;
    private final Listener listener;
    private final RecordingWorkQueue queue = new RecordingWorkQueue(QUEUE_BYTES, 512);
    private final Thread worker;
    private volatile boolean closing, failed, armed = true, scheduledWindow;
    private volatile byte[] latestStill;
    private volatile String lastStatus = "正在准备录像";
    private RecordingStore store;
    private RecordingTimeline timeline;
    private MediaFormat videoFormat, audioFormat;
    private MediaMuxer muxer;
    private RecordingStore.Pending pending;
    private int videoTrack, audioTrack, videoSamples, audioSamples;
    private boolean segmentScheduled;
    private long segmentStartUs, lastVideoUs, lastAudioUs, lastRelativeUs, segmentBytes;
    private long checkedBytes, checkedPtsUs, firstSampleUs = -1;
    private final long clockOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000L;
    private final StringBuilder markers = new StringBuilder();

    public RecordingManager(Context context, String targetId, RecordingOptions options, Listener listener) {
        this.context = context.getApplicationContext();
        this.targetId = targetId;
        this.options = options;
        this.listener = listener;
        worker = new Thread(new Runnable() {
            public void run() { runWriter(); }
        }, "Afterglow-recording");
        worker.start();
    }
    public void setVideoFormat(final MediaFormat format) {
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException { videoFormat = format; readyIfPossible(); }
        }, 0);
    }
    public void setAudioFormat(final MediaFormat format) {
        if (!options.withAudio) return;
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException { audioFormat = format; readyIfPossible(); }
        }, 0);
    }
    public void onVideo(byte[] data, long ptsUs, int flags) { sample(true, data, ptsUs, flags); }
    public void onAudio(byte[] data, long ptsUs, int flags) {
        if (options.withAudio) sample(false, data, ptsUs, flags);
    }
    public boolean hasFailed() { return failed; }
    public String status() { return lastStatus; }
    /** Daily window, not all-day. Snapshotted when a segment starts. Does not change saved settings. */
    public void setScheduledWindow(boolean value) { scheduledWindow = value; }
    /** Latest camera JPEG. Copied so the capture buffer can be reused. */
    public void noteStill(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > 512 * 1024) return;
        if ((jpeg[0] & 255) != 0xFF || (jpeg[1] & 255) != 0xD8) return;
        latestStill = java.util.Arrays.copyOf(jpeg, jpeg.length);
    }
    public void setSustain(final boolean value) {
        if (closing || failed) return;
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException {
                if (timeline != null) timeline.setSustain(value);
            }
        }, 0);
    }
    public void setArmed(final boolean value) {
        if (armed == value || closing || failed) return;
        armed = value;
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException {
                if (timeline != null) timeline.setArmed(value);
                refreshScheduleStatus();
            }
        }, 0);
    }
    public void trigger(final String reason, final long ptsUs) {
        if (reason == null) return;
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException { timeline.trigger(reason, ptsUs); }
        }, 0);
    }
    private void sample(final boolean video, byte[] data, final long ptsUs, final int flags) {
        if (closing || failed || data == null || data.length == 0 || (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0)
            return;
        if (data.length > QUEUE_BYTES) { fail("单帧超过录像缓冲上限，录像已暂停"); return; }
        // Capture buffers may be reused immediately after this callback.
        final byte[] owned = data.clone();
        offer(new RecordingWorkQueue.Work() {
            public void run() throws IOException {
                if (firstSampleUs < 0) firstSampleUs = ptsUs;
                if (ptsUs - firstSampleUs > 10_000_000L
                        && (videoFormat == null || (options.withAudio && audioFormat == null)))
                    throw new IOException("等待编码音视频格式超时，录像已暂停");
                timeline.accept(new RecordingTimeline.Sample(video, owned, ptsUs, flags));
                refreshScheduleStatus();
            }
        }, owned.length);
    }
    private void offer(RecordingWorkQueue.Work work, int bytes) {
        if (closing || failed) return;
        if (!queue.offer(work, bytes) && !closing && !failed)
            fail("存储写入过慢，录像缓冲已满；当前片段未保存，推流继续");
    }
    private void readyIfPossible() throws IOException {
        if (videoFormat != null && (!options.withAudio || audioFormat != null)) timeline.setReady();
    }
    private void runWriter() {
        try {
            if (options.maxSegmentBytes < 16L * 1024 * 1024)
                throw new IOException("单段文件上限至少为 16 MiB");
            store = RecordingStore.open(context, targetId, options);
            store.prepareWriter(MP4_HEADROOM);
            timeline = new RecordingTimeline(options.mode == MODE_EVENT, RING_BYTES,
                    options.preRollSeconds * 1_000_000L, options.postRollSeconds * 1_000_000L,
                    options.segmentSeconds * 1_000_000L, options.maxSegmentBytes - MP4_HEADROOM,
                    new RecordingTimeline.Sink() {
                        public void begin(long ptsUs) throws IOException { beginSegment(ptsUs); }
                        public void sample(RecordingTimeline.Sample sample, long relativeUs) throws IOException {
                            writeSample(sample, relativeUs);
                        }
                        public void mark(String reason, long ptsUs) {
                            if (markers.length() < 8192) {
                                if (markers.length() > 0) markers.append('\n');
                                markers.append(reason).append(" · ")
                                        .append(String.format(Locale.ROOT, "%.1f", Math.max(0, ptsUs - segmentStartUs) / 1_000_000.0))
                                        .append(" 秒");
                            }
                        }
                        public void finish() throws IOException { finishSegment(); }
                    });
            status(options.mode == MODE_EVENT ? "事件录像待命" : "等待视频关键帧");
            RecordingWorkQueue.Work work;
            while (!failed && (work = queue.take()) != null) work.run();
            if (!failed) {
                timeline.close();
                status("录像已停止");
            }
        } catch (Exception e) {
            fail("录像已暂停：" + explanation(e));
        } finally {
            queue.abort();
            abortSegment();
        }
    }
    private void beginSegment(long ptsUs) throws IOException {
        if (failed) throw new IOException("录像已暂停");
        store.ensureSpace(MP4_HEADROOM + 1024 * 1024);
        pending = store.begin(clockOffsetMs + ptsUs / 1000L, options.withAudio);
        try {
            muxer = new MediaMuxer(pending.file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrack = muxer.addTrack(videoFormat);
            audioTrack = options.withAudio ? muxer.addTrack(audioFormat) : -1;
            muxer.start();
        } catch (RuntimeException e) { throw new IOException("无法建立 MP4 音视频轨道", e); }
        segmentScheduled = scheduledWindow;
        segmentStartUs = ptsUs;
        lastVideoUs = lastAudioUs = -1;
        lastRelativeUs = segmentBytes = checkedBytes = 0;
        checkedPtsUs = ptsUs;
        videoSamples = audioSamples = 0;
        markers.setLength(0);
        status(options.mode == MODE_EVENT ? "正在保存事件录像" : "正在连续录像");
    }
    private void writeSample(RecordingTimeline.Sample sample, long relativeUs) throws IOException {
        if (failed) throw new IOException("录像已暂停");
        if (relativeUs < 0) return;
        long last = sample.video ? lastVideoUs : lastAudioUs;
        if (relativeUs <= last)
            throw new IOException("编码时间戳未递增，当前片段未保存");
        if (segmentBytes - checkedBytes >= 512 * 1024 || sample.ptsUs - checkedPtsUs >= 1_000_000L) {
            store.ensureSpaceForActive(pending, segmentBytes + MP4_HEADROOM, 1024 * 1024);
            checkedBytes = segmentBytes; checkedPtsUs = sample.ptsUs;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.data.length, relativeUs, sample.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
        try {
            muxer.writeSampleData(sample.video ? videoTrack : audioTrack, ByteBuffer.wrap(sample.data), info);
        } catch (RuntimeException e) { throw new IOException("写入录像失败，检查存储后重试", e); }
        segmentBytes += sample.data.length;
        lastRelativeUs = Math.max(lastRelativeUs, relativeUs);
        if (sample.video) { lastVideoUs = relativeUs; videoSamples++; }
        else { lastAudioUs = relativeUs; audioSamples++; }
    }
    private void finishSegment() throws IOException {
        if (muxer == null) return;
        if (failed) throw new IOException("录像已暂停");
        MediaMuxer finished = muxer;
        muxer = null;
        try {
            if (videoSamples == 0 || (options.withAudio && audioSamples == 0))
                throw new IOException("音视频样本尚未就绪，此段未保存");
            finished.stop();
        } catch (RuntimeException e) { throw new IOException("MP4 未能完整结束，此段未保存", e); }
        finally { try { finished.release(); } catch (RuntimeException ignored) { } }
        if (failed) throw new IOException("录像已暂停");
        try { Mp4FastStart.relocate(pending.file); }
        catch (IOException ignored) { /* Keep the clip even if moov stays at the end. */ }
        byte[] still = latestStill;
        if (still != null) try { store.writeThumbnail(pending, still); }
        catch (IOException ignored) { /* Playback does not depend on the thumbnail. */ }
        pending.properties.setProperty("triggers", RecordingReasons.codes(markers.toString(), segmentScheduled));
        store.complete(pending, Math.max(1, lastRelativeUs / 1000L), markers.toString());
        pending = null;
        if (options.mode == MODE_EVENT) status("事件录像待命");
    }
    private void abortSegment() {
        MediaMuxer unfinished = muxer;
        muxer = null;
        if (unfinished != null) try { unfinished.release(); } catch (RuntimeException ignored) { }
        if (store != null && pending != null) store.discard(pending);
        pending = null;
    }
    private void refreshScheduleStatus() {
        if (failed || timeline == null || timeline.isActive()) return;
        status(timeline.isArmed()
                ? (options.mode == MODE_EVENT ? "事件录像待命" : "等待视频关键帧")
                : "未到录像时间");
    }
    private synchronized void fail(String message) {
        if (failed) return;
        failed = true;
        lastStatus = message;
        queue.abort();
        if (listener != null) try { listener.onStatus(message, true); } catch (RuntimeException ignored) { }
    }
    private synchronized void status(String message) {
        if (failed || message.equals(lastStatus)) return;
        lastStatus = message;
        if (!failed && listener != null)
            try { listener.onStatus(message, false); } catch (RuntimeException ignored) { }
    }
    private static String explanation(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.length() == 0 ? exception.getClass().getSimpleName() : message;
    }
    @Override public void close() {
        closing = true;
        queue.close();
        if (Thread.currentThread() == worker) return;
        try {
            worker.join(5000);
            if (worker.isAlive()) {
                fail("存储未及时完成写入，当前片段未保存");
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("录像结束被中断，当前片段未保存");
        }
    }
}
