package com.oldphone.ipcam;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real process-wide lease, with no Android camera mocks. */
public final class CameraReservationTest {
    public static void main(String[] args) throws Exception {
        CameraCatalog.Reservation first = CameraCatalog.captureStarted(100);
        final AtomicReference<CameraCatalog.Reservation> second = new AtomicReference<CameraCatalog.Reservation>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch waiting = new CountDownLatch(1), acquired = new CountDownLatch(1);
        Thread next = new Thread(new Runnable() {
            @Override public void run() {
                waiting.countDown();
                try { second.set(CameraCatalog.captureStarted(2000)); }
                catch (Throwable error) { failure.set(error); }
                finally { acquired.countDown(); }
            }
        });
        next.start();
        if (!waiting.await(1, TimeUnit.SECONDS)) throw new AssertionError("waiter did not start");
        if (acquired.await(100, TimeUnit.MILLISECONDS)) throw new AssertionError("second camera opened before actual release");
        first.close();
        if (!acquired.await(1, TimeUnit.SECONDS)) throw new AssertionError("real release did not unblock next camera");
        next.join();
        if (failure.get() != null || second.get() == null) throw new AssertionError("waiter failed", failure.get());
        // Releasing the old handle again must not release the newer camera's lease.
        first.close();
        long before = System.nanoTime();
        try {
            CameraCatalog.captureStarted(80);
            throw new AssertionError("duplicate old close released a newer camera");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("等待旧镜头释放")) throw new AssertionError(expected);
        }
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 50)
            throw new AssertionError("reservation failed without waiting for the configured timeout");
        second.get().close(); second.get().close();
        CameraCatalog.Reservation third = CameraCatalog.captureStarted(100);
        third.close();
        System.out.println("PASS CameraReservationTest (exclusive wait, bounded timeout, real-release handoff, duplicate close identity)");
    }
}
