package com.oldphone.ipcam;

/** Synthetic capture timestamps, not a measurement of an Android device's performance. */
public final class FramePacerSimulation {
    public static void main(String[] args) {
        System.out.println("Synthetic 60-second capture traces (counts only; no hardware CPU/FPS measurement):");
        simulate(15, 15, 2_000_000L);
        simulate(30, 15, 600_000L);
        simulate(15, 12, 2_000_000L);
    }
    private static void simulate(int source, int target, long jitterNanos) {
        long lastAccepted = 0;
        long interval = 1_000_000_000L / target;
        int oldCount = 0;
        for (int i = 0; i < source * 60; i++) {
            long now = 1_000_000_000L + i * 1_000_000_000L / source + FramePacerTest.jitter(i, jitterNanos);
            // The original CameraStreamService reset-on-accept gate, reproduced literally.
            if (lastAccepted != 0 && now - lastAccepted < interval) continue;
            lastAccepted = now;
            oldCount++;
        }
        int newCount = FramePacerTest.count(new FramePacer(target), source, 60, jitterNanos);
        System.out.println(source + " -> " + target + " fps, jitter " + jitterNanos / 1_000_000.0
                + " ms: original=" + oldCount + ", deadline=" + newCount + ", target=" + target * 60);
    }
}
