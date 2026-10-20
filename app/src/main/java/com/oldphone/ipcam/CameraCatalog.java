package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Publicly exposed cameras and their YUV stream capabilities, never still-photo sizes. */
public final class CameraCatalog {
    public static final long MAX_SOURCE_PIXELS = 8294400L;
    private static final ReentrantLock HARDWARE_LOCK = new ReentrantLock();
    private static final Condition HARDWARE_RELEASED = HARDWARE_LOCK.newCondition();
    private static final Map<String, Entry> legacyCache = new HashMap<String, Entry>();
    private static Reservation activeReservation;

    /** Identity-bearing lease: releasing an old session can never release a newer one. */
    static final class Reservation implements AutoCloseable {
        private boolean released;
        private Reservation() { }
        @Override public void close() {
            HARDWARE_LOCK.lock();
            try {
                if (released) return;
                released = true;
                if (activeReservation == this) {
                    activeReservation = null;
                    HARDWARE_RELEASED.signalAll();
                }
            } finally { HARDWARE_LOCK.unlock(); }
        }
    }

    public static final class Entry {
        public final String key, label, id, physicalId;
        /** 0 back, 1 front, 2 external/unknown. */
        public final int facing, sensorOrientation, nativeWidth, nativeHeight;
        public final int[][] sizes;
        public final boolean legacy;

        public Entry(String key, String label, String id, String physicalId, int facing,
                     int sensorOrientation, int nativeWidth, int nativeHeight,
                     int[][] sizes, boolean legacy) {
            this.key = key; this.label = label; this.id = id; this.physicalId = physicalId;
            this.facing = facing; this.sensorOrientation = sensorOrientation;
            this.nativeWidth = nativeWidth; this.nativeHeight = nativeHeight;
            this.sizes = copySizes(sizes); this.legacy = legacy;
        }
    }

    private CameraCatalog() { }

    public static List<Entry> list(Context context) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                List<Entry> modern = Modern.list(context);
                if (!modern.isEmpty()) return modern;
            } catch (Throwable failure) {
                Log.w("CameraCatalog", "Camera2 catalog unavailable", failure);
            }
        }
        return legacyList();
    }

    public static Entry find(List<Entry> entries, String key) {
        if (entries == null) return null;
        if ("facing:front".equals(key) || "facing:back".equals(key) || "auto".equals(key)) {
            int facing = "facing:front".equals(key) ? 1 : 0;
            for (Entry entry : entries) if (entry.facing == facing) return entry;
            return "auto".equals(key) && !entries.isEmpty() ? entries.get(0) : null;
        }
        for (Entry entry : entries) if (entry.key.equals(key)) return entry;
        return null;
    }

    static Reservation captureStarted() throws IOException { return captureStarted(4000); }

    static Reservation captureStarted(long timeoutMillis) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean locked = false;
        try {
            locked = HARDWARE_LOCK.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!locked) throw new IOException("等待旧镜头释放，请稍后重试");
            while (activeReservation != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("等待旧镜头释放，请稍后重试");
                HARDWARE_RELEASED.awaitNanos(remaining);
            }
            activeReservation = new Reservation();
            return activeReservation;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("等待镜头释放已取消", interrupted);
        } finally {
            if (locked) HARDWARE_LOCK.unlock();
        }
    }

    static List<Entry> legacyList() {
        HARDWARE_LOCK.lock();
        try {
            List<Entry> entries = new ArrayList<Entry>();
            int count;
            try { count = Camera.getNumberOfCameras(); }
            catch (Throwable failure) { return entries; }
            for (int id = 0; id < count; id++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                try { Camera.getCameraInfo(id, info); }
                catch (Throwable failure) { continue; }
                String key = "legacy:" + id;
                Entry entry = legacyCache.get(key);
                // Camera1 exposes sizes only after opening. Probe only while no capture
                // owns hardware, and serialize the probe with capture reservations.
                if (entry == null && activeReservation == null) {
                    Camera camera = null;
                    try {
                        camera = Camera.open(id);
                        entry = legacyEntry(id, info, camera.getParameters());
                        legacyCache.put(key, entry);
                    } catch (Throwable failure) {
                        Log.w("CameraCatalog", "Cannot query " + key, failure);
                    } finally {
                        if (camera != null) try { camera.release(); } catch (Throwable ignored) { }
                    }
                }
                if (entry == null) {
                    int facing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 1 : 0;
                    entry = new Entry(key, label(facing, String.valueOf(id)), String.valueOf(id),
                            null, facing, info.orientation, 0, 0, new int[0][], true);
                }
                entries.add(entry);
            }
            sort(entries);
            return entries;
        } finally { HARDWARE_LOCK.unlock(); }
    }

    static Entry legacyEntry(int id, Camera.CameraInfo info, Camera.Parameters parameters) {
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        int[][] dimensions = new int[sizes == null ? 0 : sizes.size()][2];
        int nativeW = 0, nativeH = 0;
        for (int i = 0; i < dimensions.length; i++) {
            dimensions[i][0] = sizes.get(i).width;
            dimensions[i][1] = sizes.get(i).height;
            if ((long) dimensions[i][0] * dimensions[i][1] > (long) nativeW * nativeH) {
                nativeW = dimensions[i][0]; nativeH = dimensions[i][1];
            }
        }
        // Camera1 has no active-array metadata; largest preview is our best public hint.
        int facing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 1 : 0;
        Entry entry = new Entry("legacy:" + id, label(facing, String.valueOf(id)),
                String.valueOf(id), null, facing, info.orientation, nativeW, nativeH,
                dimensions, true);
        HARDWARE_LOCK.lock();
        try { legacyCache.put(entry.key, entry); }
        finally { HARDWARE_LOCK.unlock(); }
        return entry;
    }

    /** Native-aspect capture, choosing the smallest source with enough post-crop pixels. */
    public static int[] chooseSourceSize(Entry entry, int outputW, int outputH) {
        int[][] sizes = entry.sizes;
        int nativeW = entry.nativeWidth, nativeH = entry.nativeHeight;
        if (nativeW <= 0 || nativeH <= 0) {
            for (int[] size : sizes) {
                if (valid(size) && (long) size[0] * size[1] > (long) nativeW * nativeH) {
                    nativeW = size[0]; nativeH = size[1];
                }
            }
        }
        double nativeRatio = nativeH == 0 ? 4.0 / 3.0 : (double) nativeW / nativeH;
        double bestRatioError = Double.MAX_VALUE;
        for (int[] size : sizes) if (usable(size)) {
            double error = Math.abs(Math.log(((double) size[0] / size[1]) / nativeRatio));
            bestRatioError = Math.min(bestRatioError, error);
        }
        int neededW = nativeRatio >= 1 ? Math.max(outputW, outputH) : Math.min(outputW, outputH);
        int neededH = nativeRatio >= 1 ? Math.min(outputW, outputH) : Math.max(outputW, outputH);
        int[] enough = null, largest = null;
        for (int[] size : sizes) if (usable(size)) {
            double error = Math.abs(Math.log(((double) size[0] / size[1]) / nativeRatio));
            // Sensor active arrays often have a few optical-black pixels; 1% tolerance
            // keeps nominal 4:3 modes together instead of always selecting the largest.
            if (error > bestRatioError + 0.01) continue;
            long area = (long) size[0] * size[1];
            if (largest == null || area > (long) largest[0] * largest[1]) largest = size;
            if (size[0] >= Math.max(1, neededW) && size[1] >= Math.max(1, neededH)
                    && (enough == null || area < (long) enough[0] * enough[1])) enough = size;
        }
        int[] chosen = enough == null ? largest : enough;
        if (chosen == null) throw new IllegalArgumentException("没有可用的流分辨率（源帧上限 830 万像素）");
        return new int[]{chosen[0], chosen[1]};
    }

    private static boolean valid(int[] s) {
        return s != null && s.length >= 2 && s[0] > 0 && s[1] > 0
                && (s[0] & 1) == 0 && (s[1] & 1) == 0;
    }
    private static boolean usable(int[] s) {
        return valid(s) && s[0] >= 2 && s[1] >= 2 && s[0] <= 4096 && s[1] <= 4096
                && (long) s[0] * s[1] <= MAX_SOURCE_PIXELS;
    }
    private static int[][] copySizes(int[][] sizes) {
        if (sizes == null) return new int[0][];
        int[][] copy = new int[sizes.length][];
        for (int i = 0; i < sizes.length; i++) copy[i] = sizes[i].clone();
        return copy;
    }
    private static String label(int facing, String id) {
        return (facing == 0 ? "后置 " : facing == 1 ? "前置 " : "外接 ") + id;
    }
    private static void sort(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.facing != b.facing ? a.facing - b.facing : a.key.compareTo(b.key);
            }
        });
    }

    /** Kept in an API21-only class so KitKat never verifies Camera2 references. */
    @android.annotation.TargetApi(21)
    private static final class Modern {
        static List<Entry> list(Context context) throws Exception {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            List<Entry> entries = new ArrayList<Entry>();
            if (manager == null) return entries;
            for (String id : manager.getCameraIdList()) {
                try {
                    android.hardware.camera2.CameraCharacteristics info = manager.getCameraCharacteristics(id);
                    Entry entry = create(id, null, info);
                    if (entry != null) entries.add(entry);
                    if (Build.VERSION.SDK_INT >= 28) {
                        for (String physical : info.getPhysicalCameraIds()) {
                            try {
                                Entry child = create(id, physical, manager.getCameraCharacteristics(physical));
                                if (child != null) entries.add(child);
                            } catch (Throwable unavailable) {
                                // A hidden/vendor-only physical camera is not a usable entry.
                                Log.w("CameraCatalog", "Physical metadata unavailable: " + physical);
                            }
                        }
                    }
                } catch (Throwable unavailable) {
                    Log.w("CameraCatalog", "Camera metadata unavailable: " + id);
                }
            }
            sort(entries);
            return entries;
        }

        private static Entry create(String id, String physical,
                android.hardware.camera2.CameraCharacteristics info) {
            android.hardware.camera2.params.StreamConfigurationMap map = info.get(
                    android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;
            android.util.Size[] supported = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (supported == null || supported.length == 0) return null;
            int[][] sizes = new int[supported.length][2];
            for (int i = 0; i < supported.length; i++) {
                sizes[i][0] = supported[i].getWidth(); sizes[i][1] = supported[i].getHeight();
            }
            Integer facingValue = info.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
            int facing = facingValue != null && facingValue == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    ? 0 : facingValue != null && facingValue == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT ? 1 : 2;
            Integer orientation = info.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
            Rect active = info.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            String key = "camera2:" + id + (physical == null ? "" : "/physical:" + physical);
            String name = label(facing, physical == null ? id : physical) + (physical == null ? "" : " · 独立镜头");
            return new Entry(key, name, id, physical, facing, orientation == null ? 0 : orientation,
                    active == null ? 0 : active.width(), active == null ? 0 : active.height(), sizes, false);
        }
    }
}
