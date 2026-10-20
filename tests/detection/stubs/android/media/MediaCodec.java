package android.media;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Boundary double retaining real queue/drain ordering and buffer ownership. */
public final class MediaCodec {
    public static final int CONFIGURE_FLAG_ENCODE = 1, BUFFER_FLAG_CODEC_CONFIG = 2,
            BUFFER_FLAG_END_OF_STREAM = 4, INFO_TRY_AGAIN_LATER = -1,
            INFO_OUTPUT_FORMAT_CHANGED = -2, INFO_OUTPUT_BUFFERS_CHANGED = -3;
    public static volatile MediaCodec last;
    public static volatile boolean failStart, failEncode, wrongFormat, wrongConfig, prematureEos;
    public static final class BufferInfo {
        public int offset, size, flags;
        public long presentationTimeUs;
    }
    private static final class Frame {
        byte[] data;
        long pts;
        int flags;
    }
    public final List<Long> inputPts = new ArrayList<Long>();
    public final List<Integer> inputSizes = new ArrayList<Integer>();
    private final ByteBuffer input = ByteBuffer.allocate(4096);
    private final ByteBuffer output = ByteBuffer.allocate(4096);
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
    public volatile boolean started, released;
    public volatile int releaseCount;
    public volatile Thread releaseThread;
    private boolean formatSent;
    private MediaFormat format;
    public static void reset() {
        last = null;
        failStart = failEncode = wrongFormat = wrongConfig = prematureEos = false;
    }
    public static MediaCodec createEncoderByType(String mime) throws IOException { return last = new MediaCodec(); }
    public void configure(MediaFormat format, Object surface, Object crypto, int flags) { this.format = format; }
    public void start() {
        if (failStart) throw new IllegalStateException("encoder start failed");
        started = true;
    }
    public ByteBuffer[] getInputBuffers() { return new ByteBuffer[]{input}; }
    public ByteBuffer[] getOutputBuffers() { return new ByteBuffer[]{output}; }
    public int dequeueInputBuffer(long timeout) {
        if (failEncode) throw new IllegalStateException("encoder failed");
        return 0;
    }
    public void queueInputBuffer(int index, int offset, int size, long pts, int flags) {
        Frame frame = new Frame();
        frame.data = new byte[size == 0 ? 0 : 6];
        for (int i = 0; i < frame.data.length; i++) frame.data[i] = (byte) inputPts.size();
        frame.pts = pts;
        frame.flags = prematureEos ? BUFFER_FLAG_END_OF_STREAM : flags;
        frames.add(frame);
        if (size > 0) { inputPts.add(pts); inputSizes.add(size); }
    }
    public int dequeueOutputBuffer(BufferInfo info, long timeout) {
        if (!formatSent && !frames.isEmpty()) {
            formatSent = true;
            return INFO_OUTPUT_FORMAT_CHANGED;
        }
        Frame frame = frames.poll();
        if (frame == null) return INFO_TRY_AGAIN_LATER;
        output.clear();
        output.put(frame.data);
        info.offset = 0;
        info.size = frame.data.length;
        info.presentationTimeUs = frame.pts;
        info.flags = frame.flags;
        return 0;
    }
    public MediaFormat getOutputFormat() {
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, wrongFormat ? 44100 : 16000);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{wrongConfig ? (byte) 0x12 : 0x14, 0x08}));
        return format;
    }
    public void releaseOutputBuffer(int index, boolean render) { }
    public void stop() { started = false; }
    public void release() { released = true; releaseCount++; releaseThread = Thread.currentThread(); }
}
