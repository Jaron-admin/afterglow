package com.oldphone.ipcam;

import java.util.ArrayDeque;

/** Run with javac/java; see tests/STREAM_LIFECYCLE.md. */
public final class StreamLifecycleTest {
    private static final class Queue implements StreamLifecycle.Scheduler {
        final ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        public void execute(Runnable task) { work.add(task); }
        void drain() { while (!work.isEmpty()) work.remove().run(); }
    }
    private static final class Rig implements StreamLifecycle.Resources, StreamLifecycle.Observer {
        final Queue queue = new Queue();
        final StreamLifecycle lifecycle = new StreamLifecycle(queue, this, this, 0);
        int resources, opens, maxResources, stopped;
        long activeGeneration, publishedGeneration;
        boolean running, applying;
        Throwable error;
        Runnable duringOpen;
        boolean fail;
        public void open(long generation) throws Exception {
            resources++; opens++; maxResources = Math.max(maxResources, resources);
            activeGeneration = generation;
            Runnable action = duringOpen; duringOpen = null;
            if (action != null) action.run();
            if (fail) { fail = false; throw new Exception("camera unavailable"); }
        }
        public void close() { resources = 0; }
        public void stateChanged(boolean requested, boolean configuring, long generation, Throwable failure) {
            running = requested; applying = configuring; publishedGeneration = generation; error = failure;
        }
        public void stopped(long generation) { stopped++; }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void stopDuringOpenReleasesResources() {
        final Rig r = new Rig();
        r.duringOpen = new Runnable() { public void run() { r.lifecycle.stop(); r.lifecycle.reconfigure(); } };
        r.lifecycle.start(); r.queue.drain();
        check(r.resources == 0, "stop during open leaked camera/server resources");
        check(r.opens == 1 && !r.running, "queued reconfigure restarted stopped stream");
    }
    private static void pendingChangesCollapseToLatestGeneration() {
        Rig r = new Rig(); r.lifecycle.start(); r.queue.drain();
        for (int i = 0; i < 100; i++) r.lifecycle.reconfigure();
        check(r.queue.work.size() == 1, "reconfigure queue must stay bounded");
        check(r.running && r.applying, "stream must remain running while reconfiguring");
        r.queue.drain();
        check(r.opens == 2 && r.activeGeneration == 101, "pending settings were not coalesced");
        check(r.publishedGeneration == 101 && !r.applying, "settled generation was not published");
        check(r.maxResources == 1, "old resources were not released before reopen");
    }
    private static void partialStartFailureCleansUpAndAllowsRetry() {
        Rig r = new Rig(); r.fail = true; r.lifecycle.start(); r.queue.drain();
        check(r.resources == 0 && !r.running, "failed start leaked resources or remained running");
        check(r.error != null && !r.applying, "failed start did not expose error and clear configuring state");
        r.lifecycle.reconfigure(); r.queue.drain();
        check(r.opens == 1, "reconfigure after failure unexpectedly started camera");
        r.lifecycle.start(); r.queue.drain();
        check(r.opens == 2 && r.resources == 1 && r.error == null, "retry failed to open cleanly");
    }
    private static void rapidStopStartNeverOverlapsResources() {
        final Rig r = new Rig();
        r.duringOpen = new Runnable() { public void run() { r.lifecycle.stop(); r.lifecycle.start(); } };
        r.lifecycle.start(); r.queue.drain();
        check(r.opens == 2 && r.resources == 1 && r.maxResources == 1, "rapid restart overlapped resources");
        check(r.activeGeneration == 3 && r.running && !r.applying, "rapid restart lost latest request");
        check(!r.lifecycle.isCurrent(1) && r.lifecycle.isCurrent(3), "stale captured frames remain current");
    }
    private static void supersededFailureDoesNotCancelNewRequest() {
        final Rig r = new Rig(); r.fail = true;
        r.duringOpen = new Runnable() { public void run() { r.lifecycle.reconfigure(); } };
        r.lifecycle.start(); r.queue.drain();
        check(r.opens == 2 && r.running && r.error == null && r.resources == 1,
                "superseded failure cancelled latest settings");
    }
    private static void shutdownCannotBeRestarted() {
        Rig r = new Rig(); r.lifecycle.start(); r.queue.drain();
        r.lifecycle.shutdown(); r.lifecycle.start(); r.lifecycle.reconfigure(); r.queue.drain();
        check(r.resources == 0 && r.opens == 1 && !r.running, "destroyed service restarted from queued work");
    }
    public static void main(String[] args) {
        stopDuringOpenReleasesResources(); pendingChangesCollapseToLatestGeneration();
        partialStartFailureCleansUpAndAllowsRetry(); rapidStopStartNeverOverlapsResources();
        supersededFailureDoesNotCancelNewRequest(); shutdownCannotBeRestarted();
        System.out.println("PASS StreamLifecycleTest (6 lifecycle behaviors)");
    }
}
