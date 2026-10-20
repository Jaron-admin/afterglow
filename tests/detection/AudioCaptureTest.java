package com.oldphone.ipcam;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AudioCaptureTest {
    public static void main(String[] args) throws Exception {
        encodesOwnedFramesOnSampleClock();
        closeUnblocksReadAndReleasesOnOwner();
        startupFailureReleasesResources();
        readAndEncodeFailuresAreVisible();
        rejectsUnexpectedEncoderFormat();
        unexpectedEosCannotSilentlyDropAudio();
        releaseFailureStillReleasesCodec();
        closeInsideCallbackDoesNotDeadlock();
        closeDuringStartDoesNotLeak();
        onlyOneInstanceCanOwnMicrophone();
        System.out.println("PASS audio capture: owned frames, sample PTS, blocked-read stop, startup/runtime failures, format validation, reentrant/concurrent close");
    }

    private static void encodesOwnedFramesOnSampleClock() throws Exception {
        reset();
        AudioCapture capture = new AudioCapture();
        Probe probe = new Probe();
        long before = System.nanoTime() / 1000;
        capture.start(probe);
        try {
            for (int i = 0; i < 5; i++) {
                AudioRecord.SAMPLES.offer(new short[320]);
                Thread.sleep(i * 7);
            }
            awaitSize(probe.aac, 5);
            check(probe.formats == 1, "actual encoder format is delivered once");
            check(probe.pcmPts.size() == 5, "PCM feeds the detector");
            long origin = probe.pcmPts.get(0);
            check(origin >= before && origin <= System.nanoTime() / 1000, "PTS shares monotonic video clock");
            for (int i = 0; i < 5; i++) {
                check(probe.pcmPts.get(i) == origin + i * 20000, "PCM PTS advances by samples, not read arrival time");
                check(probe.aacPts.get(i).equals(probe.pcmPts.get(i)), "AAC input PTS matches PCM");
                if (i > 0) check(probe.aac.get(i) != probe.aac.get(i - 1), "AAC byte ownership must be independent");
            }
            byte[] config = AudioCapture.audioSpecificConfig();
            check(config.length == 2 && config[0] == 0x14 && config[1] == 8, "AAC-LC 16 kHz mono ASC");
            config[0] = 0;
            check(AudioCapture.audioSpecificConfig()[0] == 0x14, "caller cannot mutate shared config");
        } finally { capture.close(); }
        check(probe.errors.isEmpty(), "normal stop must not emit failure");
    }

    private static void closeUnblocksReadAndReleasesOnOwner() throws Exception {
        reset();
        AudioCapture capture = new AudioCapture();
        capture.start(new Probe());
        check(AudioRecord.last.readEntered.await(1, TimeUnit.SECONDS), "read has blocked");
        capture.close();
        check(AudioRecord.last.stopCount > 0 && AudioRecord.last.released, "close stops and releases capture");
        check(MediaCodec.last.released, "close releases codec");
        check(AudioRecord.last.releaseThread == MediaCodec.last.releaseThread
                && MediaCodec.last.releaseThread != Thread.currentThread(), "worker owns resource release");
        capture.close();
        check(AudioRecord.last.releaseCount == 1 && MediaCodec.last.releaseCount == 1, "close is idempotent");
    }

    private static void startupFailureReleasesResources() throws Exception {
        reset();
        MediaCodec.failStart = true;
        try {
            new AudioCapture().start(new Probe());
            throw new AssertionError("codec startup failure must throw");
        } catch (IOException expected) { }
        check(MediaCodec.last.released, "failed encoder must be released");
        if (AudioRecord.last != null) check(AudioRecord.last.released, "partial capture must be released");
        reset();
        AudioRecord.denyPermission = true;
        try {
            new AudioCapture().start(new Probe());
            throw new AssertionError("permission failure must throw");
        } catch (IOException expected) { }
        if (MediaCodec.last != null) check(MediaCodec.last.released, "permission failure releases any codec");
    }

    private static void readAndEncodeFailuresAreVisible() throws Exception {
        for (boolean failRead : new boolean[]{true, false}) {
            reset();
            AudioCapture capture = new AudioCapture();
            Probe probe = new Probe();
            capture.start(probe);
            if (failRead) AudioRecord.failRead = true;
            else {
                MediaCodec.failEncode = true;
                AudioRecord.SAMPLES.offer(new short[320]);
            }
            check(probe.error.await(2, TimeUnit.SECONDS), "runtime failure must be reported");
            capture.close();
            check(probe.errors.size() == 1, "one failure callback");
            check(AudioRecord.last.released && MediaCodec.last.released, "runtime failure releases devices");
        }
    }

    private static void rejectsUnexpectedEncoderFormat() throws Exception {
        for (boolean wrongRate : new boolean[]{true, false}) {
            reset();
            MediaCodec.wrongFormat = wrongRate;
            MediaCodec.wrongConfig = !wrongRate;
            AudioCapture capture = new AudioCapture();
            Probe probe = new Probe();
            capture.start(probe);
            AudioRecord.SAMPLES.offer(new short[320]);
            check(probe.error.await(2, TimeUnit.SECONDS), "unexpected format/config must be reported");
            capture.close();
            check(probe.formats == 0 && probe.aac.isEmpty(), "mismatched stream must never be advertised or sent");
        }
    }

    private static void unexpectedEosCannotSilentlyDropAudio() throws Exception {
        reset();
        MediaCodec.prematureEos = true;
        AudioCapture capture = new AudioCapture();
        Probe probe = new Probe();
        capture.start(probe);
        try {
            AudioRecord.SAMPLES.offer(new short[320]);
            check(probe.error.await(2, TimeUnit.SECONDS), "unexpected codec end must be reported");
        } finally { capture.close(); }
    }

    private static void releaseFailureStillReleasesCodec() throws Exception {
        reset();
        AudioCapture capture = new AudioCapture();
        Probe probe = new Probe();
        capture.start(probe);
        AudioRecord.failRelease = true;
        capture.close();
        check(MediaCodec.last.released, "recorder release error must not skip codec release");
        check(probe.error.await(1, TimeUnit.SECONDS), "release failure is visible");
    }

    private static void closeInsideCallbackDoesNotDeadlock() throws Exception {
        reset();
        final AudioCapture capture = new AudioCapture();
        final CountDownLatch closed = new CountDownLatch(1);
        capture.start(new Probe() {
            @Override public void onPcm(short[] samples, int count, long ptsUs) {
                capture.close();
                closed.countDown();
            }
        });
        AudioRecord.SAMPLES.offer(new short[320]);
        check(closed.await(1, TimeUnit.SECONDS), "close in PCM callback cannot join itself");
        capture.close();
        check(AudioRecord.last.released && MediaCodec.last.released, "self-close eventually releases");
    }

    private static void closeDuringStartDoesNotLeak() throws Exception {
        reset();
        AudioRecord.startGate = new CountDownLatch(1);
        final AudioCapture capture = new AudioCapture();
        final List<Throwable> results = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread starter = new Thread(new Runnable() {
            @Override public void run() {
                try { capture.start(new Probe()); results.add(new AssertionError("cancelled start succeeded")); }
                catch (IOException expected) { }
            }
        });
        starter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (AudioRecord.last == null && System.nanoTime() < deadline) Thread.sleep(2);
        check(AudioRecord.last != null, "startup reached recorder");
        Thread closer = new Thread(new Runnable() { @Override public void run() { capture.close(); } });
        closer.start();
        Thread.sleep(30);
        AudioRecord.startGate.countDown();
        starter.join(2000);
        closer.join(2000);
        check(!starter.isAlive() && !closer.isAlive(), "concurrent close/start completes");
        check(results.isEmpty(), "start surfaces cancellation");
        check(AudioRecord.last.released && MediaCodec.last.released, "concurrent close/start releases resources");
    }

    private static void onlyOneInstanceCanOwnMicrophone() throws Exception {
        reset();
        AudioCapture first = new AudioCapture(), second = new AudioCapture();
        first.start(new Probe());
        try {
            boolean rejected = false;
            try { second.start(new Probe()); }
            catch (IOException expected) { rejected = true; }
            finally { second.close(); }
            check(rejected, "new instance must not overlap microphone ownership");
        } finally { first.close(); }
        second.start(new Probe());
        second.close();
    }

    private static class Probe implements AudioCapture.Listener {
        final List<byte[]> aac = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<Long> aacPts = Collections.synchronizedList(new ArrayList<Long>());
        final List<Long> pcmPts = Collections.synchronizedList(new ArrayList<Long>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch error = new CountDownLatch(1);
        volatile int formats;
        @Override public void onFormat(MediaFormat format) { formats++; }
        @Override public void onEncodedFrame(byte[] data, long ptsUs) { aacPts.add(ptsUs); aac.add(data); }
        @Override public void onPcm(short[] samples, int count, long ptsUs) { pcmPts.add(ptsUs); }
        @Override public void onError(String message) { errors.add(message); error.countDown(); }
    }

    private static void awaitSize(List<?> list, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (list.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        check(list.size() == count, "expected " + count + " audio frames, got " + list.size());
    }
    private static void reset() { AudioRecord.reset(); MediaCodec.reset(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
