package com.oldphone.ipcam;

/** Serializes asynchronous DNS-SD publication and withdrawal. */
final class NsdRegistration {
    interface Listener {
        void registered();
        void registrationFailed();
        void unregistered();
        void unregistrationFailed();
    }
    interface Backend {
        void register(int port, Listener listener);
        void unregister(Listener listener);
        void schedule(Runnable task, long delayMillis);
        void cancel(Runnable task);
    }
    private final Backend backend;
    private int desiredPort;
    private Attempt current;
    private Runnable retry;
    private long retryMillis = 1000;

    NsdRegistration(Backend backend) { this.backend = backend; }

    synchronized void start(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid discovery port");
        desiredPort = port;
        if (current == null) {
            cancelRetry();
            publish();
        } else if (current.port != port) {
            current.obsolete = true;
            withdraw();
        }
    }

    synchronized void stop() {
        desiredPort = 0;
        cancelRetry();
        if (current != null) {
            current.obsolete = true;
            withdraw();
        }
    }

    private void publish() {
        if (desiredPort == 0 || current != null) return;
        current = new Attempt(desiredPort);
        backend.register(current.port, current);
    }

    private void withdraw() {
        if (current == null || !current.registered || current.withdrawing) return;
        current.withdrawing = true;
        backend.unregister(current);
    }

    private void cancelRetry() {
        if (retry != null) backend.cancel(retry);
        retry = null;
    }

    private void scheduleRetry() {
        cancelRetry();
        retry = new Runnable() {
            @Override public void run() {
                synchronized (NsdRegistration.this) {
                    if (retry != this) return;
                    retry = null;
                    if (current != null && current.obsolete) withdraw();
                    else publish();
                }
            }
        };
        backend.schedule(retry, retryMillis);
        retryMillis = Math.min(30000, retryMillis * 2);
    }

    private final class Attempt implements Listener {
        final int port;
        boolean registered, obsolete, withdrawing;
        Attempt(int port) { this.port = port; }

        @Override public void registered() {
            synchronized (NsdRegistration.this) {
                if (current != this) return;
                registered = true;
                retryMillis = 1000;
                if (obsolete || desiredPort == 0) withdraw();
            }
        }

        @Override public void registrationFailed() {
            synchronized (NsdRegistration.this) {
                if (current != this) return;
                current = null;
                if (desiredPort != 0) scheduleRetry();
            }
        }

        @Override public void unregistered() {
            synchronized (NsdRegistration.this) {
                if (current != this) return;
                current = null;
                cancelRetry();
                retryMillis = 1000;
                publish();
            }
        }

        @Override public void unregistrationFailed() {
            synchronized (NsdRegistration.this) {
                if (current != this) return;
                withdrawing = false;
                scheduleRetry();
            }
        }
    }
}
