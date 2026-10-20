package com.oldphone.ipcam;

public final class OrientationStabilityTest {
    public static void main(String[] args) {
        OrientationStability state = new OrientationStability(0);
        for (int angle : new int[] {44, 46, 49, -1, 359, 2}) state.observe(angle);
        if (state.commit() || state.current() != 0) throw new AssertionError("boundary jitter changed orientation");
        state.observe(70);
        if (state.current() != 0 || state.pending() != 90) throw new AssertionError("must wait for stable direction");
        if (!state.commit() || state.current() != 90) throw new AssertionError("stable rotation missing");
        state.observe(180);
        state.observe(90);
        if (state.commit()) throw new AssertionError("canceled rotation was applied");
        state.observe(270);
        state.observe(-1);
        if (state.commit()) throw new AssertionError("flat unknown pose must cancel pending change");
        state.observe(270);
        if (!state.commit() || state.current() != 270) throw new AssertionError("180-degree rotation failed");
        state.observe(0);
        state.cancelPending();
        if (state.pending() != state.current()) throw new AssertionError("stop retained pending pose");
        state.observe(0);
        if (state.pending() == state.current() || !state.commit())
            throw new AssertionError("restart at same physical pose did not reschedule");
        System.out.println("PASS orientation stability: hysteresis, unknown pose, cancelation and 180-degree changes");
    }
}
