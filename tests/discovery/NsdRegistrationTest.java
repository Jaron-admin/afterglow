package com.oldphone.ipcam;

import java.util.ArrayList;
import java.util.List;

public final class NsdRegistrationTest {
    public static void main(String[] args) {
        advertisesOnlyOnceAndWithdraws();
        stoppedPendingRegistrationIsWithdrawnWhenItCompletes();
        restartWaitsForWithdrawalAndIgnoresStaleCallbacks();
        failedRegistrationRetriesUntilStopped();
        failedWithdrawalRetriesWithoutCreatingDuplicateServices();
        System.out.println("PASS NsdRegistrationTest (publication lifecycle, pending stop/restart, stale callbacks, retries)");
    }

    private static void advertisesOnlyOnceAndWithdraws() {
        FakeBackend backend = new FakeBackend();
        NsdRegistration registration = new NsdRegistration(backend);
        registration.start(8080);
        check(backend.published.size() == 1 && backend.ports.get(0) == 8080, "publish the active HTTP endpoint");
        NsdRegistration.Listener first = backend.published.get(0);
        first.registered();
        registration.start(8080);
        check(backend.published.size() == 1, "same endpoint must not publish twice");
        registration.stop();
        check(backend.withdrawn.size() == 1 && backend.withdrawn.get(0) == first, "stop withdraws its own listener");
        first.unregistered();
        registration.stop();
        check(backend.published.size() == 1 && backend.withdrawn.size() == 1, "stopped service stays withdrawn");
    }

    private static void stoppedPendingRegistrationIsWithdrawnWhenItCompletes() {
        FakeBackend backend = new FakeBackend();
        NsdRegistration registration = new NsdRegistration(backend);
        registration.start(8080);
        NsdRegistration.Listener pending = backend.published.get(0);
        registration.stop();
        check(backend.withdrawn.isEmpty(), "wait for pending registration before withdrawal");
        pending.registered();
        check(backend.withdrawn.size() == 1 && backend.withdrawn.get(0) == pending,
                "late registration after stop must be withdrawn");
        pending.unregistered();
        check(backend.published.size() == 1, "stop must not republish after callback");
    }

    private static void restartWaitsForWithdrawalAndIgnoresStaleCallbacks() {
        FakeBackend backend = new FakeBackend();
        NsdRegistration registration = new NsdRegistration(backend);
        registration.start(8080);
        NsdRegistration.Listener old = backend.published.get(0);
        registration.stop();
        registration.start(8081);
        check(backend.published.size() == 1, "restart must not overlap pending registration");
        old.registered();
        old.unregistered();
        check(backend.published.size() == 2 && backend.ports.get(1) == 8081, "new endpoint follows old withdrawal");
        NsdRegistration.Listener fresh = backend.published.get(1);
        check(fresh != old, "Android 4.4 requires a fresh listener for each registration");
        fresh.registered();
        old.registrationFailed();
        old.unregistered();
        registration.stop();
        check(backend.withdrawn.size() == 2 && backend.withdrawn.get(1) == fresh,
                "stale callbacks cannot lose the current registration");
    }

    private static void failedRegistrationRetriesUntilStopped() {
        FakeBackend backend = new FakeBackend();
        NsdRegistration registration = new NsdRegistration(backend);
        registration.start(8080);
        backend.published.get(0).registrationFailed();
        check(backend.retry != null && backend.published.size() == 1, "failure waits before retry");
        backend.runRetry();
        check(backend.published.size() == 2, "transient failure is retried");
        backend.published.get(1).registrationFailed();
        Runnable queued = backend.retry;
        registration.stop();
        check(backend.retry == null, "stop cancels retry");
        queued.run();
        check(backend.published.size() == 2, "already queued retry cannot restart stopped service");
    }

    private static void failedWithdrawalRetriesWithoutCreatingDuplicateServices() {
        FakeBackend backend = new FakeBackend();
        NsdRegistration registration = new NsdRegistration(backend);
        registration.start(8080);
        NsdRegistration.Listener old = backend.published.get(0);
        old.registered();
        registration.start(8081);
        old.unregistrationFailed();
        check(backend.retry != null && backend.published.size() == 1, "failed withdrawal must not overlap registrations");
        backend.runRetry();
        check(backend.withdrawn.size() == 2 && backend.withdrawn.get(1) == old, "retry the failed withdrawal");
        old.unregistered();
        check(backend.published.size() == 2 && backend.ports.get(1) == 8081, "publish new endpoint after retry succeeds");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class FakeBackend implements NsdRegistration.Backend {
        final List<NsdRegistration.Listener> published = new ArrayList<NsdRegistration.Listener>();
        final List<NsdRegistration.Listener> withdrawn = new ArrayList<NsdRegistration.Listener>();
        final List<Integer> ports = new ArrayList<Integer>();
        Runnable retry;
        @Override public void register(int port, NsdRegistration.Listener listener) { ports.add(port); published.add(listener); }
        @Override public void unregister(NsdRegistration.Listener listener) { withdrawn.add(listener); }
        @Override public void schedule(Runnable task, long delayMillis) {
            check(delayMillis >= 1000 && delayMillis <= 30000, "retries must be bounded and not spin");
            retry = task;
        }
        @Override public void cancel(Runnable task) { if (retry == task) retry = null; }
        void runRetry() { Runnable task = retry; retry = null; task.run(); }
    }
}
