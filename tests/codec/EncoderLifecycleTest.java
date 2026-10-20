package com.oldphone.ipcam;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

public final class EncoderLifecycleTest {
    public static void main(String[] args) throws Exception {
        delayedFramesDrainBeforeRelease();
        listenerFailureReleasesNativeBuffer();
        outputAcquisitionFailureReleasesNativeBuffer();
        eosTimeoutIsVisibleAndStillReleases();
        System.out.println("PASS EncoderLifecycleTest API " + Build.VERSION.SDK_INT
                + ": delayed output/EOS, callback failure, buffer acquisition failure, timeout cleanup");
    }
    private static void delayedFramesDrainBeforeRelease() throws Exception {
        MediaCodec.reset();
        MediaCodec.delayUntilEos = true;
        Probe probe = new Probe();
        H264Encoder encoder = new H264Encoder();
        encoder.start(4, 4, 15, 100000, probe);
        encoder.encode(new byte[24], 1000000);
        encoder.encode(new byte[24], 1066666);
        check(probe.frames.isEmpty(), "test codec retains data before EOS");
        encoder.stop();
        check(probe.frames.size() == 2 && probe.frames.get(1) == 1066666L, "stop delivers retained encoded frames");
        check(probe.formats == 1 && probe.configs == 1, "real output format and CSD reach consumers");
        check(MediaCodec.last.eosCount == 1 && MediaCodec.last.released, "EOS is queued once before release");
        encoder.stop();
        check(MediaCodec.last.releaseCount == 1, "stop is idempotent");
    }
    private static void listenerFailureReleasesNativeBuffer() throws Exception {
        MediaCodec.reset();
        H264Encoder encoder = new H264Encoder();
        encoder.start(4, 4, 15, 100000, new Probe() {
            @Override public void onEncodedFrame(byte[] data, long ptsUs, boolean key) {
                throw new IllegalStateException("consumer failure");
            }
        });
        try {
            encoder.encode(new byte[24], 1000000);
            throw new AssertionError("consumer failure must propagate");
        } catch (IllegalStateException expected) { }
        check(MediaCodec.last.releasedBuffers == 1, "failed consumer cannot pin codec output buffer");
        encoder.stop();
    }
    private static void outputAcquisitionFailureReleasesNativeBuffer() throws Exception {
        if (Build.VERSION.SDK_INT < 21) return;
        MediaCodec.reset();
        MediaCodec.failOutputBuffer = true;
        H264Encoder encoder = new H264Encoder();
        encoder.start(4, 4, 15, 100000, new Probe());
        try {
            encoder.encode(new byte[24], 1000000);
            throw new AssertionError("native output acquisition failure must propagate");
        } catch (IllegalStateException expected) { }
        try {
            check(MediaCodec.last.releasedBuffers == 1, "dequeued output still needs release when getOutputBuffer throws");
        } finally {
            MediaCodec.failOutputBuffer = false;
            encoder.stop();
        }
    }
    private static void eosTimeoutIsVisibleAndStillReleases() throws Exception {
        MediaCodec.reset();
        H264Encoder encoder = new H264Encoder();
        encoder.start(4, 4, 15, 100000, new Probe());
        MediaCodec.neverFinish = true;
        try {
            encoder.stop();
            throw new AssertionError("EOS timeout must not be reported as normal completed recording");
        } catch (IllegalStateException expected) { }
        check(MediaCodec.last.released && !encoder.isStarted(), "timed out encoder still releases");
    }
    private static class Probe implements H264Encoder.Listener {
        final List<Long> frames = new ArrayList<Long>();
        int formats, configs;
        @Override public void onEncodedFrame(byte[] data, long ptsUs, boolean key) { frames.add(ptsUs); }
        @Override public void onCodecConfig(byte[] sps, byte[] pps) { configs++; }
        @Override public void onOutputFormat(MediaFormat format) { formats++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
