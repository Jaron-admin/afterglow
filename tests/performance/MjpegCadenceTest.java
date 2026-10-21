package com.oldphone.ipcam;

public final class MjpegCadenceTest {
    public static void main(String[] args) {
        activeClientsDoNotApplyASecondFrameGate();
        idleSnapshotsStayAtHalfAFramePerSecond();
        joinLeaveAndResetAreImmediateAndBounded();
        System.out.println("PASS MjpegCadenceTest (single active gate, idle cadence, joins/leaves, geometry/session reset)");
    }

    private static void activeClientsDoNotApplyASecondFrameGate() {
        FramePacer capture = new FramePacer(15);
        FramePacer secondGate = new FramePacer(15);
        MjpegCadence cadence = new MjpegCadence();
        int selected = 0, activePublished = 0, doubleGated = 0;
        for (int i = 0; i < 900; i++) {
            long jitter = i == 0 ? 0 : (i % 2 == 1 ? 4_000_000L : -4_000_000L);
            long now = 1_000_000_000L + i * 1_000_000_000L / 15 + jitter;
            if (!capture.shouldRun(now)) continue;
            selected++;
            boolean clients = i >= 1; // Client joins when the current frame happens to arrive late.
            if (cadence.shouldPublish(now, clients) && clients) activePublished++;
            if (clients && secondGate.shouldRun(now)) doubleGated++;
        }
        check(selected == 900 && activePublished == 899,
                "every capture admitted after joining must reach MJPEG: selected=" + selected + ", published=" + activePublished);
        System.out.println("Synthetic cascade trace: capture=" + selected + ", clients join after first frame; double-gated="
                + doubleGated + ", single-gated=" + activePublished + " (no hardware measurement)");
    }

    private static void idleSnapshotsStayAtHalfAFramePerSecond() {
        MjpegCadence cadence = new MjpegCadence();
        int published = 0;
        for (int i = 0; i < 900; i++)
            if (cadence.shouldPublish(1_000_000_000L + i * 1_000_000_000L / 15, false)) published++;
        check(published == 30, "idle snapshots must stay at 0.5 fps: " + published);
    }

    private static void joinLeaveAndResetAreImmediateAndBounded() {
        MjpegCadence cadence = new MjpegCadence();
        check(cadence.shouldPublish(0, false), "first idle snapshot is immediate");
        check(!cadence.shouldPublish(50_000_000L, false), "idle refresh is throttled");
        check(cadence.shouldPublish(100_000_000L, true), "client join cannot wait for idle deadline");
        check(cadence.shouldPublish(150_000_000L, true), "active frames have already passed capture pacing");
        check(cadence.shouldPublish(200_000_000L, false), "leaving clients starts fresh idle cadence");
        for (int i = 0; i < 1990; i++)
            check(!cadence.shouldPublish(200_000_000L + i * 1_000_000L, false), "leaving clients must not keep encoding at full FPS");
        check(cadence.shouldPublish(2_200_000_000L, false), "idle cadence resumes two seconds later");
        cadence.reset();
        check(cadence.shouldPublish(2_250_000_000L, false), "new geometry/session publishes its first snapshot immediately");
        check(!cadence.shouldPublish(2_250_000_000L, false), "reset does not leave the gate permanently open");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
