package com.oldphone.ipcam;

public final class FramePacerTest {
    public static void main(String[] args) {
        nominalInputWithJitterDoesNotLoseHalfItsFrames();
        thirtyToFifteenHasEvenCadence();
        fifteenToTwelveKeepsItsRequestedAverage();
        longRunningInputDoesNotExceedTarget();
        pauseDoesNotReplayMissedFrames();
        timestampsMayStartAtZeroBeNegativeOrWrap();
        resettingAndChangingRateStartAFreshCadence();
        toleranceIsSmallAndBounded();
        System.out.println("PASS FramePacerTest (jitter, cadence, bounded rate, stalls, signed time, reset/configuration)");
    }

    private static void nominalInputWithJitterDoesNotLoseHalfItsFrames() {
        FramePacer pacer = new FramePacer(15);
        int accepted = count(pacer, 15, 60, 2_000_000L);
        check(accepted == 900, "15 fps input with +/-2 ms jitter must retain all 900 frames; accepted " + accepted);
    }

    private static void thirtyToFifteenHasEvenCadence() {
        FramePacer pacer = new FramePacer(15);
        int accepted = 0, previousIndex = -2;
        for (int i = 0; i < 1800; i++) {
            long timestamp = 1_000_000_000L + i * 1_000_000_000L / 30 + jitter(i, 600_000L);
            if (pacer.shouldRun(timestamp)) {
                check(i - previousIndex == 2, "30 -> 15 should accept every second capture");
                previousIndex = i;
                accepted++;
            }
        }
        check(accepted == 900, "30 -> 15 should yield 900 frames over 60 seconds");
    }

    private static void fifteenToTwelveKeepsItsRequestedAverage() {
        int accepted = count(new FramePacer(12), 15, 60, 2_000_000L);
        check(accepted >= 719 && accepted <= 721, "15 -> 12 must average 12 fps, not 7.5; accepted " + accepted);
    }

    private static void longRunningInputDoesNotExceedTarget() {
        for (int target : new int[]{5, 12, 15, 25, 30}) {
            int accepted = count(new FramePacer(target), 120, 600, 300_000L);
            check(accepted >= target * 600 - 1 && accepted <= target * 600 + 1,
                    "cumulative deadlines must bound long-term rate for " + target + ": " + accepted);
        }
    }

    private static void pauseDoesNotReplayMissedFrames() {
        FramePacer pacer = new FramePacer(15);
        long start = 123_000_000L;
        check(pacer.shouldRun(start), "first frame is immediate");
        long resumed = start + 10_000_000_000L;
        check(pacer.shouldRun(resumed), "first frame after a long stall is immediate");
        for (int i = 0; i <= 60; i++)
            check(!pacer.shouldRun(resumed + i * 1_000_000L), "no catch-up burst after a long stall");
        check(pacer.shouldRun(resumed + 66_666_666L), "normal cadence resumes after the stall");
    }

    private static void timestampsMayStartAtZeroBeNegativeOrWrap() {
        for (long start : new long[]{0L, -2_000_000_000L, Long.MAX_VALUE - 10_000_000L}) {
            FramePacer pacer = new FramePacer(15);
            check(pacer.shouldRun(start), "any signed nanoTime may initialize the pacer");
            check(!pacer.shouldRun(start), "same timestamp cannot emit two frames");
            check(!pacer.shouldRun(start + 1_000_000L), "zero/negative initialization must still throttle");
            check(pacer.shouldRun(start + 66_666_666L), "signed timestamp subtraction handles nanoTime wrap");
        }
    }

    private static void resettingAndChangingRateStartAFreshCadence() {
        FramePacer pacer = new FramePacer(15);
        long start = 10_000_000_000L;
        check(pacer.shouldRun(start), "first frame");
        pacer.setFps(15);
        check(!pacer.shouldRun(start + 1_000_000L), "unchanged FPS must not reset cadence");
        pacer.setFps(30);
        check(pacer.shouldRun(start + 2_000_000L), "changed FPS starts immediately");
        check(!pacer.shouldRun(start + 3_000_000L), "new FPS still gates subsequent frames");
        pacer.reset();
        check(pacer.shouldRun(-10L), "reset does not retain an old clock origin");
        pacer.setIntervalNanos(2_000_000_000L);
        check(pacer.shouldRun(100L), "switch to idle-snapshot interval starts immediately");
        pacer.setIntervalNanos(2_000_000_000L);
        check(!pacer.shouldRun(1_000_000_100L), "same interval does not reset cadence");
        check(pacer.shouldRun(2_000_000_100L), "fractional-FPS interval is supported");
        try { pacer.setFps(0); throw new AssertionError("zero FPS must be rejected"); }
        catch (IllegalArgumentException expected) { }
        try { pacer.setIntervalNanos(0); throw new AssertionError("zero interval must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void toleranceIsSmallAndBounded() {
        FramePacer slow = new FramePacer(1);
        slow.shouldRun(0);
        check(!slow.shouldRun(994_999_999L), "slow rates cannot run more than 5 ms early");
        check(slow.shouldRun(995_000_000L), "5 ms early jitter is accepted");
        FramePacer fast = new FramePacer(100);
        fast.shouldRun(0);
        check(!fast.shouldRun(8_999_999L), "fast rates limit tolerance to one tenth of the interval");
        check(fast.shouldRun(9_000_000L), "bounded relative tolerance is accepted");
    }

    static int count(FramePacer pacer, int inputFps, int seconds, long jitterNanos) {
        int accepted = 0;
        for (int i = 0; i < inputFps * seconds; i++)
            if (pacer.shouldRun(1_000_000_000L + i * 1_000_000_000L / inputFps + jitter(i, jitterNanos))) accepted++;
        return accepted;
    }
    static long jitter(int frame, long amount) { return frame == 0 ? 0 : frame % 3 == 1 ? -amount : frame % 3 == 2 ? amount / 2 : 0; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
