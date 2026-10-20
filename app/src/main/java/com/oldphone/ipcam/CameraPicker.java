package com.oldphone.ipcam;

import android.hardware.Camera;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 摄像头选择与自动回退。
 *
 * 旧手机最常见的问题是「后置摄像头模组坏了」——Camera.open(0) 直接抛
 * RuntimeException，App 就再也起不来。所以这里不写死 id，而是：
 *   1. 用 getCameraInfo 把设备上的所有摄像头按 facing 分成后置/前置/其他；
 *   2. 按用户选择排出优先顺序（强制前置时前置排第一，其余情况后置排第一）；
 *   3. 逐个 open，第一个成功的就用，并把 id 与朝向回报给界面。
 *
 * 注意：某些 ROM 的 getNumberOfCameras() 会虚报（例如报 6 个但只有 2 个能开），
 * 所以「打开成功」才是唯一的判定标准，不能只信枚举结果。
 */
public final class CameraPicker {

    private static final String TAG = "CameraPicker";

    /** 一次成功的选择结果 */
    public static final class Pick {
        public final Camera camera;
        public final int id;
        public final boolean front;
        /** 传感器原生朝向（0/90/180/270） */
        public final int orientation;
        /** 是否是回退到「非首选」的那颗（用于界面上提示） */
        public final boolean fallback;

        Pick(Camera camera, int id, boolean front, int orientation, boolean fallback) {
            this.camera = camera;
            this.id = id;
            this.front = front;
            this.orientation = orientation;
            this.fallback = fallback;
        }

        public String label() {
            String base = front ? "前置" : "后置";
            return fallback ? base + "（已自动回退）" : base;
        }
    }

    private CameraPicker() {
    }

    public static Pick open(int choice) throws IOException {
        // 每项 = {id, facing, orientation}
        List<int[]> backs = new ArrayList<int[]>();
        List<int[]> fronts = new ArrayList<int[]>();
        List<int[]> others = new ArrayList<int[]>();

        int count = 0;
        try {
            count = Camera.getNumberOfCameras();
        } catch (Throwable t) {
            Log.w(TAG, "getNumberOfCameras: " + t);
        }
        Log.i(TAG, "device reports " + count + " camera(s)");

        for (int i = 0; i < count; i++) {
            try {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                int[] item = new int[]{i, info.facing, info.orientation};
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    fronts.add(item);
                } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backs.add(item);
                } else {
                    others.add(item);
                }
                Log.i(TAG, "camera " + i + " facing=" + info.facing
                        + " orientation=" + info.orientation);
            } catch (Throwable t) {
                // 枚举失败的 id 也放进 others 里赌一把
                Log.w(TAG, "getCameraInfo(" + i + "): " + t);
                others.add(new int[]{i, -1, 0});
            }
        }

        List<int[]> order = new ArrayList<int[]>();
        if (choice == Prefs.CAM_FRONT) {
            order.addAll(fronts);
            order.addAll(backs);
        } else {
            // AUTO 与 BACK 都是后置优先：老手机常见需求是「后置坏了用前置」
            order.addAll(backs);
            order.addAll(fronts);
        }
        order.addAll(others);

        if (order.isEmpty()) {
            // 枚举完全失败时的兜底：直接 open() 默认摄像头
            try {
                Camera c = Camera.open();
                Log.i(TAG, "opened default camera (enumeration empty)");
                return new Pick(c, -1, false, 0, false);
            } catch (Throwable t) {
                throw new IOException("no camera available: " + t);
            }
        }

        int primary = order.get(0)[0];
        Throwable last = null;
        for (int i = 0; i < order.size(); i++) {
            int[] item = order.get(i);
            int id = item[0];
            try {
                Camera c = Camera.open(id);
                boolean front = (item[1] == Camera.CameraInfo.CAMERA_FACING_FRONT);
                boolean fallback = (id != primary);
                Log.i(TAG, "opened camera id=" + id + (front ? " (front)" : " (back)")
                        + (fallback ? " [fallback]" : ""));
                return new Pick(c, id, front, item[2], fallback);
            } catch (Throwable t) {
                last = t;
                Log.w(TAG, "open(" + id + ") failed: " + t);
            }
        }
        throw new IOException("all cameras failed to open: " + last);
    }
}
