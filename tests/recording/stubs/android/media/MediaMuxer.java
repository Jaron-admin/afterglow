package android.media;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fake native boundary only: all recording state, queue and storage are production code. */
public class MediaMuxer {
    public static final class OutputFormat { public static final int MUXER_OUTPUT_MPEG_4 = 0; }
    public static final class Sample {
        public final int track; public final long ptsUs;
        Sample(int track, long ptsUs) { this.track = track; this.ptsUs = ptsUs; }
    }
    public static final List<MediaMuxer> instances = Collections.synchronizedList(new ArrayList<MediaMuxer>());
    public static volatile boolean failConstruct, failWrite, failStop;
    public static volatile CountDownLatch blockWrite, enteredWrite;
    public final List<Sample> samples = new ArrayList<Sample>();
    public int tracks;
    public boolean started, stopped, released;
    private final FileOutputStream output;
    public static void reset() {
        instances.clear(); failConstruct = failWrite = failStop = false;
        blockWrite = enteredWrite = null;
    }
    public MediaMuxer(String path, int format) throws IOException {
        if (failConstruct) throw new IOException("fake constructor failure");
        output = new FileOutputStream(path); instances.add(this);
    }
    public int addTrack(MediaFormat format) {
        if (started || format == null) throw new IllegalStateException("track format unavailable");
        return tracks++;
    }
    public void start() { if (tracks == 0) throw new IllegalStateException(); started = true; }
    public void writeSampleData(int track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (enteredWrite != null) enteredWrite.countDown();
        if (blockWrite != null) try { blockWrite.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (failWrite) throw new IllegalStateException("fake write failure");
        if (!started || stopped) throw new IllegalStateException();
        samples.add(new Sample(track, info.presentationTimeUs));
        try { output.write(new byte[info.size]); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public void stop() {
        if (failStop) throw new IllegalStateException("fake stop failure");
        stopped = true;
        try { output.flush(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public void release() {
        released = true;
        try { output.close(); } catch (IOException ignored) { }
    }
}
