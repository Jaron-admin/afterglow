package com.oldphone.ipcam;

import java.util.Arrays;
import java.util.Random;

public final class DetectionTest {
    private static final int W = 160;
    private static final int H = 120;

    public static void main(String[] args) {
        motionNeedsSustainedChange();
        ignoresNoiseAndUniformExposure();
        resetsOnGeometryChange();
        motionCooldownAndSensitivity();
        soundNeedsSustainedIncrease();
        ignoresSteadyNoiseAndDc();
        soundCooldownAndSensitivity();
        soundResetsAfterCaptureGap();
        System.out.println("PASS detection: motion/noise/exposure/warmup/geometry/cooldown; sound/impulse/ambient/DC/gaps");
    }

    private static void motionNeedsSustainedChange() {
        MotionDetector detector = warmedMotion(1);
        check(!detector.update(patch(0, 36), W, H, 2000000, 1, 1), "one motion sample must not trigger");
        detector.update(frame(100), W, H, 2200000, 1, 1);
        int events = 0;
        for (int i = 0; i < 12; i++) {
            if (detector.update(patch((i % 4) * 24, 36), W, H, 2400000 + i * 200000L, 1, 1)) events++;
        }
        check(events > 0, "sustained moving object must trigger");
        detector.reset();
        check(!detector.update(patch(0, 36), W, H, 6000000, 1, 1), "explicit reset must warm up");
    }

    private static void ignoresNoiseAndUniformExposure() {
        MotionDetector detector = warmedMotion(2);
        Random random = new Random(821);
        for (int i = 0; i < 30; i++) {
            byte[] noise = frame(100);
            for (int p = 0; p < W * H; p++) noise[p] = (byte) (100 + random.nextInt(9) - 4);
            check(!detector.update(noise, W, H, 2000000 + i * 200000L, 1, 2), "minor sensor noise");
        }
        for (int i = 0; i < 20; i++) {
            check(!detector.update(frame(i % 2 == 0 ? 140 : 70), W, H,
                    8000000 + i * 200000L, 1, 2), "global exposure must not start an event");
        }
        // A single object appearing then disappearing is not sustained motion.
        detector = warmedMotion(1);
        check(!detector.update(patch(0, 36), W, H, 2000000, 1, 1), "isolated flash");
        for (int i = 1; i < 10; i++) {
            check(!detector.update(frame(100), W, H, 2000000 + i * 200000L, 1, 1), "single flash residual");
        }
    }

    private static void resetsOnGeometryChange() {
        MotionDetector detector = warmedMotion(2);
        detector.update(patch(0, 40), W, H, 2000000, 1, 2);
        check(!detector.update(patch(90, 40), W, H, 2200000, 2, 2), "geometry revision begins warmup");
        for (int i = 1; i < 5; i++) {
            check(!detector.update(patch(90, 40), W, H, 2200000 + i * 200000L, 2, 2), "new geometry static");
        }
        check(!detector.update(frame(180), W, H, 10000000, 2, 2), "long gap begins warmup");
        check(!detector.update(frame(100), W, H, 5000000, 2, 2), "backwards clock begins warmup");
    }

    private static void motionCooldownAndSensitivity() {
        MotionDetector detector = warmedMotion(2);
        long previous = Long.MIN_VALUE;
        int events = 0;
        for (int i = 0; i < 60; i++) {
            long pts = 2000000 + i * 200000L;
            if (detector.update(patch((i % 5) * 24, 30), W, H, pts, 1, 2)) {
                if (events > 0) check(pts - previous >= 1500000, "motion cooldown prevents storms");
                previous = pts;
                events++;
            }
        }
        check(events >= 2, "ongoing motion must extend an existing recording");
        MotionDetector high = warmedMotion(2);
        MotionDetector low = warmedMotion(0);
        boolean highEvent = false, lowEvent = false;
        for (int i = 0; i < 20; i++) {
            byte[] small = patch((i % 5) * 24, 20);
            highEvent |= high.update(small, W, H, 2000000 + i * 200000L, 1, 2);
            lowEvent |= low.update(small, W, H, 2000000 + i * 200000L, 1, 0);
        }
        check(highEvent && !lowEvent, "sensitivity must change meaningful object size threshold");
    }

    private static void soundNeedsSustainedIncrease() {
        SoundDetector detector = warmedSound(1, 30);
        check(!detector.update(tone(8000), 320, 2000000, 1), "one 20 ms impact must not trigger");
        for (int i = 1; i < 20; i++) {
            check(!detector.update(tone(30), 320, 2000000 + i * 20000L, 1), "impulse must settle");
        }
        boolean triggered = false;
        for (int i = 0; i < 30; i++) triggered |= detector.update(tone(4000), 320, 2400000 + i * 20000L, 1);
        check(triggered, "sustained sound increase must trigger");
        detector.reset();
        check(!detector.update(tone(8000), 320, 4000000, 1), "reset must learn environment again");
    }

    private static void ignoresSteadyNoiseAndDc() {
        SoundDetector detector = warmedSound(2, 5000);
        for (int i = 0; i < 150; i++) {
            check(!detector.update(tone(5000), 320, 2000000 + i * 20000L, 2), "steady initial ambient must not trigger");
        }
        detector = warmedSound(2, 0);
        short[] dc = new short[320];
        Arrays.fill(dc, (short) 9000);
        for (int i = 0; i < 40; i++) {
            check(!detector.update(dc, dc.length, 2000000 + i * 20000L, 2), "DC offset is not acoustic energy");
        }
    }

    private static void soundCooldownAndSensitivity() {
        SoundDetector detector = warmedSound(2, 10);
        int events = 0;
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < 400; i++) {
            long pts = 2000000 + i * 20000L;
            if (detector.update(tone(5000), 320, pts, 2)) {
                if (events > 0) check(pts - previous >= 1500000, "sound cooldown prevents storms");
                previous = pts;
                events++;
            }
        }
        check(events >= 2, "ongoing sound must extend event recording");
        SoundDetector high = warmedSound(2, 10), low = warmedSound(0, 10);
        boolean highEvent = false, lowEvent = false;
        for (int i = 0; i < 60; i++) {
            highEvent |= high.update(tone(700), 320, 2000000 + i * 20000L, 2);
            lowEvent |= low.update(tone(700), 320, 2000000 + i * 20000L, 0);
        }
        check(highEvent && !lowEvent, "sound sensitivity must change threshold");
    }

    private static void soundResetsAfterCaptureGap() {
        SoundDetector detector = warmedSound(2, 20);
        for (int i = 0; i < 4; i++) detector.update(tone(6000), 320, 2000000 + i * 20000L, 2);
        check(!detector.update(tone(6000), 320, 6000000, 2), "gap must reset continuity");
        check(!detector.update(tone(6000), 320, 1000000, 2), "backwards timestamp must reset");
    }

    private static MotionDetector warmedMotion(int sensitivity) {
        MotionDetector result = new MotionDetector();
        for (int i = 0; i < 10; i++) {
            check(!result.update(frame(100), W, H, i * 200000L, 1, sensitivity), "static warmup");
        }
        return result;
    }

    private static SoundDetector warmedSound(int sensitivity, int amplitude) {
        SoundDetector result = new SoundDetector();
        for (int i = 0; i < 100; i++) {
            check(!result.update(tone(amplitude), 320, i * 20000L, sensitivity), "sound warmup/steady ambient");
        }
        return result;
    }

    private static byte[] frame(int y) {
        byte[] result = new byte[W * H * 3 / 2];
        Arrays.fill(result, 0, W * H, (byte) y);
        return result;
    }

    private static byte[] patch(int x, int side) {
        byte[] result = frame(100);
        for (int y = 36; y < 36 + side; y++) {
            Arrays.fill(result, y * W + x, y * W + x + side, (byte) 210);
        }
        return result;
    }

    private static short[] tone(int amplitude) {
        short[] result = new short[320];
        for (int i = 0; i < result.length; i++) result[i] = (short) ((i % 8 < 4) ? amplitude : -amplitude);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
