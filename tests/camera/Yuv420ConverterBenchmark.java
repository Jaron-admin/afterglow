package com.oldphone.ipcam;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/** Synthetic desktop JVM microbenchmark, not an Android camera or device-FPS test. */
public final class Yuv420ConverterBenchmark {
    private static volatile int sink;
    private static final int ITERATIONS = 160;
    private static final int ROUNDS = 7;

    public static void main(String[] args) {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocations = base instanceof com.sun.management.ThreadMXBean
                ? (com.sun.management.ThreadMXBean) base : null;
        if (allocations != null && allocations.isThreadAllocatedMemorySupported()
                && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        System.out.println("Desktop JVM conversion only; no camera, JPEG, codec, network, or Android runtime.");
        System.out.println("Median of " + ROUNDS + " alternating-order batches x " + ITERATIONS + " frames after warm-up.");
        runCase(new Fixture(1280, 720, false, 0), allocations);
        runCase(new Fixture(1280, 720, true, 32), allocations);
        runCase(new Fixture(1920, 1080, true, 32), allocations);
    }

    private static void runCase(Fixture frame, com.sun.management.ThreadMXBean allocations) {
        byte[] reference = new byte[frame.out.length];
        scalar(frame, reference);
        convert(frame);
        if (!Arrays.equals(reference, frame.out)) throw new AssertionError("Converter pixels differ from scalar baseline");
        for (int i = 0; i < 320; i++) { scalar(frame, frame.out); convert(frame); }
        double[] oldNs = new double[ROUNDS], newNs = new double[ROUNDS];
        double[] oldBytes = new double[ROUNDS], newBytes = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            boolean firstOptimized = (round & 1) == 0;
            measure(frame, firstOptimized, allocations,
                    firstOptimized ? newNs : oldNs, firstOptimized ? newBytes : oldBytes, round);
            measure(frame, !firstOptimized, allocations,
                    firstOptimized ? oldNs : newNs, firstOptimized ? oldBytes : newBytes, round);
        }
        double oldMedian = median(oldNs), newMedian = median(newNs);
        System.out.printf(Locale.ROOT,
                "%dx%d %s padding=%d: scalar %.1f us/frame, current %.1f us/frame, %.2fx; allocated %.1f -> %.1f B/frame%n",
                frame.width, frame.height, frame.y.isDirect() ? "direct read-only" : "heap read-only", frame.padding,
                oldMedian / 1000, newMedian / 1000, oldMedian / newMedian, median(oldBytes), median(newBytes));
    }

    private static void measure(Fixture frame, boolean optimized, com.sun.management.ThreadMXBean allocations,
            double[] nanos, double[] bytes, int round) {
        long thread = Thread.currentThread().getId();
        long allocatedBefore = allocated(allocations, thread);
        long start = System.nanoTime();
        int checksum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            if (optimized) convert(frame); else scalar(frame, frame.out);
            checksum += frame.out[(i * 997) % frame.out.length];
        }
        nanos[round] = (System.nanoTime() - start) / (double) ITERATIONS;
        long allocatedAfter = allocated(allocations, thread);
        bytes[round] = allocatedBefore < 0 || allocatedAfter < 0 ? -1
                : (allocatedAfter - allocatedBefore) / (double) ITERATIONS;
        sink = checksum;
    }

    private static long allocated(com.sun.management.ThreadMXBean allocations, long thread) {
        return allocations == null || !allocations.isThreadAllocatedMemorySupported() ? -1
                : allocations.getThreadAllocatedBytes(thread);
    }

    private static double median(double[] values) { Arrays.sort(values); return values[values.length / 2]; }

    private static void convert(Fixture frame) {
        Yuv420Converter.toNv21(frame.width, frame.height, frame.y, frame.yStride, 1,
                frame.u, frame.uvStride, 2, frame.v, frame.uvStride, 2, frame.out);
    }

    private static void scalar(Fixture f, byte[] out) {
        originalToNv21(f.width, f.height, f.y, f.yStride, 1,
                f.u, f.uvStride, 2, f.v, f.uvStride, 2, out);
    }

    /** The pre-optimization implementation, including its generic strides and validation. */
    private static void originalToNv21(int width, int height,
            ByteBuffer y, int yRowStride, int yPixelStride,
            ByteBuffer u, int uRowStride, int uPixelStride,
            ByteBuffer v, int vRowStride, int vPixelStride, byte[] out) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0
                || pixels > Integer.MAX_VALUE * 2L / 3 || out == null || out.length < pixels * 3 / 2)
            throw new IllegalArgumentException();
        validate(y, width, height, yRowStride, yPixelStride);
        validate(u, width / 2, height / 2, uRowStride, uPixelStride);
        validate(v, width / 2, height / 2, vRowStride, vPixelStride);
        int offset = 0, base = y.position();
        for (int row = 0; row < height; row++) {
            int start = base + row * yRowStride;
            for (int col = 0; col < width; col++) out[offset++] = y.get(start + col * yPixelStride);
        }
        int uBase = u.position(), vBase = v.position();
        for (int row = 0; row < height / 2; row++) {
            int uStart = uBase + row * uRowStride, vStart = vBase + row * vRowStride;
            for (int col = 0; col < width / 2; col++) {
                out[offset++] = v.get(vStart + col * vPixelStride);
                out[offset++] = u.get(uStart + col * uPixelStride);
            }
        }
    }

    private static void validate(ByteBuffer plane, int columns, int rows, int rowStride, int pixelStride) {
        if (plane == null || rowStride <= 0 || pixelStride <= 0
                || (long) (columns - 1) * pixelStride >= rowStride
                || (long) plane.position() + (long) (rows - 1) * rowStride
                    + (long) (columns - 1) * pixelStride >= plane.limit()) throw new IllegalArgumentException();
    }

    private static final class Fixture {
        final int width, height, padding, yStride, uvStride;
        final ByteBuffer y, u, v;
        final byte[] out;
        Fixture(int width, int height, boolean direct, int padding) {
            this.width = width; this.height = height; this.padding = padding;
            yStride = width + padding; uvStride = width + padding;
            y = plane(direct, 3 + (height - 1) * yStride + width);
            u = plane(direct, 3 + (height / 2 - 1) * uvStride + width - 1);
            v = plane(direct, 3 + (height / 2 - 1) * uvStride + width - 1);
            out = new byte[width * height * 3 / 2];
        }
        private static ByteBuffer plane(boolean direct, int size) {
            ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            for (int i = 0; i < size; i++) buffer.put(i, (byte) (i * 31));
            buffer.position(3);
            return buffer.asReadOnlyBuffer();
        }
    }
}
