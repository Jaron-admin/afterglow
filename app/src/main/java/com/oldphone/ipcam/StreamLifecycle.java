package com.oldphone.ipcam;

/**
 * Bounded, last-request-wins lifecycle. Requests may come from any thread;
 * the scheduler must enqueue work on one serial worker. Resource ownership
 * never leaves that worker, including partial-start failure cleanup.
 */
final class StreamLifecycle {
    interface Scheduler { void execute(Runnable task); }
    interface Resources { void open(long generation) throws Exception; void close(); }
    interface Observer {
        void stateChanged(boolean requested, boolean configuring, long generation, Throwable failure);
        void stopped(long generation);
    }

    private final Scheduler scheduler;
    private final Resources resources;
    private final Observer observer;
    private long generation;
    private boolean requested;
    private boolean destroyed;
    private boolean queued;

    StreamLifecycle(Scheduler scheduler, Resources resources, Observer observer, long generation) {
        this.scheduler = scheduler;
        this.resources = resources;
        this.observer = observer;
        this.generation = generation;
    }

    synchronized void start() {
        if (destroyed || requested) return;
        requested = true;
        changed();
    }

    synchronized void stop() {
        requested = false;
        changed();
    }

    synchronized void reconfigure() {
        if (destroyed || !requested) return;
        changed();
    }

    synchronized void shutdown() {
        destroyed = true;
        stop();
    }

    synchronized boolean isCurrent(long capturedGeneration) {
        return requested && !destroyed && generation == capturedGeneration;
    }

    private void changed() {
        generation++;
        observer.stateChanged(requested, requested, generation, null);
        if (!queued) {
            queued = true;
            scheduler.execute(new Runnable() { public void run() { drain(); } });
        }
    }

    private void drain() {
        while (true) {
            final long applyingGeneration;
            final boolean shouldOpen;
            synchronized (this) {
                applyingGeneration = generation;
                shouldOpen = requested && !destroyed;
            }
            resources.close();
            Throwable failure = null;
            if (shouldOpen && isCurrent(applyingGeneration)) {
                try {
                    resources.open(applyingGeneration);
                } catch (Throwable t) {
                    failure = t;
                    resources.close();
                }
            }
            synchronized (this) {
                // Stop/configuration may have arrived while native camera open blocked.
                // Release that obsolete session on this same worker before doing anything else.
                if (generation != applyingGeneration) continue;
                if (failure != null) requested = false;
                observer.stateChanged(requested, false, generation, failure);
                queued = false;
                if (!requested) observer.stopped(generation);
                return;
            }
        }
    }
}
