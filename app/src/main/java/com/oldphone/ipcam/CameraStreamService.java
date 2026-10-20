package com.oldphone.ipcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * 前台服务：打开摄像头 -> 编码 -> 分发到 RTSP / MJPEG。
 *
 * 用 Camera1（android.hardware.Camera）而不是 Camera2，是刻意的取舍：
 *   - Camera1 从 API 1 一直到 Android 15 都能用，行为稳定；
 *   - Camera2 从 API 21 才有，且 21/22 上 bug 极多。
 * 代价是拿不到 Surface 直通编码器的零拷贝路径，需要一次 YUV 转换。
 */
public final class CameraStreamService extends Service implements Camera.PreviewCallback,
        H264Encoder.Listener {

    private static final String TAG = "CameraService";

    public static final String ACTION_START = "com.oldphone.ipcam.action.START";
    public static final String ACTION_STOP = "com.oldphone.ipcam.action.STOP";
    public static final String ACTION_RECONFIGURE = "com.oldphone.ipcam.action.RECONFIGURE";

    /** 界面预览用的帧回调（收到的是已压缩好的 JPEG，避免再跨进程/跨线程传 NV21） */
    public interface PreviewListener {
        void onPreviewJpeg(byte[] jpeg, long generation);
    }

    private static volatile PreviewListener previewListener;

    public static void setPreviewListener(PreviewListener l) {
        previewListener = l;
    }

    public static final int RTSP_PORT = 8554;
    public static final int HTTP_PORT = 8080;

    private static final String CHANNEL_ID = "ipcam_foreground";
    private static final int NOTIFICATION_ID = 1001;

    /** 供 UI 查询 */
    public static volatile boolean running = false;
    /** True while the latest requested configuration is being opened. */
    public static volatile boolean reconfiguring = false;
    /** Invalidated immediately on start, stop and accepted configuration requests. */
    public static volatile long streamGeneration = 0;
    public static volatile String lastError = "";
    public static volatile int measuredFps = 0;
    public static volatile int rtspClients = 0;
    public static volatile int httpClients = 0;
    public static volatile int videoWidth = 0;
    public static volatile int videoHeight = 0;
    /** 当前实际使用的摄像头（含「已自动回退」标记） */
    public static volatile String cameraLabel = "";
    /** 传感器原生朝向，前置/后置常常差 180°，播放端可据此旋转 */
    public static volatile int sensorOrientation = 0;

    private Camera camera;
    // A replacement Service instance shares this serial worker until its predecessor's
    // cleanup finishes. Explicit stop/start therefore cannot overlap native cameras.
    private static final Object WORKER_LOCK = new Object();
    private static HandlerThread sharedCameraThread;
    private static Handler sharedCameraHandler;
    private static int workerUsers;
    private static volatile CameraStreamService stateOwner;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler mainHandler;
    private StreamLifecycle lifecycle;
    private volatile boolean destroyed;
    private volatile int latestStartId;
    private long frameGeneration;
    private android.graphics.SurfaceTexture previewTexture;
    private TextureDrainer drainer;

    private H264Encoder encoder;
    private RtspServer rtspServer;
    private MjpegServer mjpegServer;

    private PowerManager.WakeLock wakeLock;

    private int width;
    private int height;
    private int fps;
    private int bitrate;
    private int mjpegFps;
    /** 画面顺时针旋转角度，0 表示不转 */
    private int rotation;
    /** 旋转用的复用缓冲，避免每帧分配 */
    private byte[] rotBuf;

    private volatile boolean streaming;

    private long lastProcessedNanos;
    private long lastJpegNanos;

    private final ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream(256 * 1024);
    private Rect cropRect;

    private long fpsWindowStart;
    private int fpsWindowCount;

    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        mainHandler = new Handler(getMainLooper());
        stateOwner = this;
        synchronized (WORKER_LOCK) {
            if (sharedCameraThread == null) {
                sharedCameraThread = new HandlerThread("afterglow-camera");
                sharedCameraThread.start();
                sharedCameraHandler = new Handler(sharedCameraThread.getLooper());
            }
            workerUsers++;
            cameraThread = sharedCameraThread;
            cameraHandler = sharedCameraHandler;
        }
        lifecycle = new StreamLifecycle(new StreamLifecycle.Scheduler() {
            @Override public void execute(Runnable task) { cameraHandler.post(task); }
        }, new StreamLifecycle.Resources() {
            @Override public void open(long generation) throws Exception {
                openCameraAndServers(generation);
            }
            @Override public void close() { closeResources(); }
        }, new StreamLifecycle.Observer() {
            @Override public void stateChanged(boolean requested, boolean configuring,
                                               long generation, Throwable failure) {
                if (stateOwner != CameraStreamService.this) return;
                // Publish invalidation before consumers can see the new status.
                streamGeneration = generation;
                running = requested;
                reconfiguring = configuring;
                if (configuring || !requested) streaming = false;
                if (requested && configuring) lastError = "";
                if (failure != null) {
                    lastError = "摄像头启动失败：" + errorMessage(failure);
                    Log.e(TAG, "stream start failed", failure);
                }
            }
            @Override public void stopped(final long generation) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && !running && streamGeneration == generation) {
                            stopForeground(true);
                            stopSelfResult(latestStartId);
                        }
                    }
                });
            }
        }, streamGeneration);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        latestStartId = startId;
        if (ACTION_STOP.equals(action)) {
            lifecycle.stop();
            return START_NOT_STICKY;
        }
        if (ACTION_RECONFIGURE.equals(action)) {
            lifecycle.reconfigure();
            // A delayed settings event must never resurrect a stopped service.
            if (!running) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
        } else if (ACTION_START.equals(action) || action == null) {
            startForegroundCompat();
            lifecycle.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        lifecycle.shutdown();
        // shutdown's cleanup is queued before quitting; never release Camera1 on main.
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                synchronized (WORKER_LOCK) {
                    workerUsers--;
                    if (workerUsers == 0) {
                        cameraThread.quitSafely();
                        sharedCameraThread = null;
                        sharedCameraHandler = null;
                    }
                }
            }
        });
        stopForeground(true);
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void openCameraAndServers(long generation) throws Exception {
        frameGeneration = generation;
        width = Prefs.width();
        height = Prefs.height();
        fps = Prefs.fps();
        bitrate = Prefs.bitrate();
        mjpegFps = Math.min(fps, 12);
        rotation = Prefs.rotationDegrees();

        acquireWakeLock();
        CameraPicker.Pick pick = CameraPicker.open(Prefs.cameraChoice());
        camera = pick.camera;
        if (!lifecycle.isCurrent(generation)) return;
        cameraLabel = pick.label();
        sensorOrientation = pick.orientation;
        Log.i(TAG, "using camera id=" + pick.id + " " + cameraLabel
                + " sensorOrientation=" + pick.orientation);

        Camera.Parameters params = camera.getParameters();

        Camera.Size size = pickPreviewSize(params.getSupportedPreviewSizes(), width, height);
        if (size != null) {
            width = size.width;
            height = size.height;
        }
        params.setPreviewSize(width, height);
        params.setPreviewFormat(ImageFormat.NV21);

        int[] range = pickFpsRange(params.getSupportedPreviewFpsRange(), fps);
        if (range != null) {
            params.setPreviewFpsRange(range[0], range[1]);
        }

        if (supports(params.getSupportedFocusModes(), Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } else if (supports(params.getSupportedFocusModes(), Camera.Parameters.FOCUS_MODE_INFINITY)) {
            // 固定机位用无穷远更稳，避免反复拉风箱
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
        }
        try {
            params.setRecordingHint(true);
        } catch (Throwable ignored) {
        }

        camera.setParameters(params);
        camera.setDisplayOrientation(0);

        // 无界面运行需要一个假 preview 目标；绕 EGL 起线程把缓冲池排空，
        // 否则跑几小时后摄像头会因队列满而静默停止出帧。
        drainer = new TextureDrainer();
        drainer.start();
        previewTexture = drainer.awaitTexture(3000);
        boolean drainOk = previewTexture != null;
        if (!drainOk) {
            Log.w(TAG, "drainer unavailable, fallback to bare SurfaceTexture(0)");
            previewTexture = new android.graphics.SurfaceTexture(0);
        }
        previewTexture.setDefaultBufferSize(width, height);
        camera.setPreviewTexture(previewTexture);
        if (!lifecycle.isCurrent(generation)) return;

        camera.setPreviewCallbackWithBuffer(this);
        int bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
        for (int i = 0; i < 2; i++) {
            camera.addCallbackBuffer(new byte[bufferSize]);
        }

        // 对外公布的尺寸是「旋转之后」的尺寸：90/270 度时宽高互换，
        // 编码器、JPEG、SDP 都必须用这个尺寸，否则画面会被裁掉或拉伸。
        videoWidth = YuvRotator.outWidth(width, height, rotation);
        videoHeight = YuvRotator.outHeight(width, height, rotation);
        cropRect = new Rect(0, 0, videoWidth, videoHeight);
        rotBuf = (rotation == 0) ? null : new byte[width * height * 3 / 2];

        String serverError = startServers();

        // A stop/config change can arrive during native open or codec startup.
        // The lifecycle will close this obsolete session before opening the latest one.
        if (!lifecycle.isCurrent(generation)) return;
        lastError = serverError;
        lastProcessedNanos = 0;
        lastJpegNanos = 0;
        fpsWindowStart = 0;
        fpsWindowCount = 0;
        streaming = true;
        camera.startPreview();

        Log.i(TAG, "camera opened " + videoWidth + "x" + videoHeight
                + (rotation == 0 ? "" : " (rotated " + rotation + "°)")
                + " drainer=" + (drainOk ? "ok" : "FALLBACK")
                + " enc=" + (encoder != null ? "on" : "off")
                + " mjpeg=" + (mjpegServer != null ? "on" : "off"));
    }

    private String startServers() {
        String serverError = "";
        if (Prefs.needRtsp()) {
            try {
                encoder = new H264Encoder();
                encoder.start(videoWidth, videoHeight, fps, bitrate, this);
                rtspServer = new RtspServer(RTSP_PORT);
                rtspServer.setVideoInfo(videoWidth, videoHeight, bitrate);
                rtspServer.start();
            } catch (Throwable t) {
                Log.e(TAG, "rtsp start failed", t);
                if (encoder != null) {
                    encoder.stop();
                    encoder = null;
                }
                if (rtspServer != null) rtspServer.stop();
                rtspServer = null;
                serverError = "RTSP 启动失败：" + errorMessage(t);
            }
        }

        if (Prefs.needMjpeg()) {
            try {
                mjpegServer = new MjpegServer(HTTP_PORT);
                if (Prefs.getBool(Prefs.KEY_AUTH, false)) {
                    mjpegServer.setBasicAuth(Prefs.getStr(Prefs.KEY_USER, "admin"),
                            Prefs.getStr(Prefs.KEY_PASS, "123456"));
                }
                mjpegServer.start();
            } catch (Throwable t) {
                Log.e(TAG, "http start failed", t);
                if (mjpegServer != null) mjpegServer.stop();
                mjpegServer = null;
                serverError = (serverError.length() == 0 ? "" : serverError + "；")
                        + "MJPEG 启动失败：" + errorMessage(t);
            }
        }
        if (rtspServer == null && mjpegServer == null) {
            throw new IllegalStateException(serverError.length() == 0 ? "没有可用的视频服务" : serverError);
        }
        return serverError;
    }

    /** Worker-only and idempotent, including after a partially failed start. */
    private void closeResources() {
        streaming = false;

        releaseCamera();

        if (rtspServer != null) {
            try { rtspServer.stop(); } catch (Throwable t) { Log.w(TAG, "rtsp stop", t); }
            rtspServer = null;
        }
        if (mjpegServer != null) {
            try { mjpegServer.stop(); } catch (Throwable t) { Log.w(TAG, "http stop", t); }
            mjpegServer = null;
        }

        if (encoder != null) {
            try { encoder.stop(); } catch (Throwable t) { Log.w(TAG, "encoder stop", t); }
            encoder = null;
        }

        if (drainer != null) {
            try { drainer.stop(); } catch (Throwable t) { Log.w(TAG, "drainer stop", t); }
            drainer = null;
        }
        if (previewTexture != null) {
            try {
                previewTexture.release();
            } catch (Throwable ignored) {
            }
            previewTexture = null;
        }
        releaseWakeLock();
        measuredFps = 0;
        rtspClients = 0;
        httpClients = 0;
        cameraLabel = "";
        sensorOrientation = 0;
        videoWidth = 0;
        videoHeight = 0;
        rotBuf = null;
        Log.i(TAG, "stopped");
    }

    private void releaseCamera() {
        Camera c = camera;
        camera = null;
        if (c != null) {
            try {
                c.setPreviewCallbackWithBuffer(null);
            } catch (Throwable ignored) {
            }
            try {
                c.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                c.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Camera.PreviewCallback
    // ------------------------------------------------------------------

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        final long generation = frameGeneration;
        if (!streaming || cam != camera || !lifecycle.isCurrent(generation)) {
            returnBuffer(cam, data);
            return;
        }

        long now = System.nanoTime();
        long minInterval = 1_000_000_000L / Math.max(1, fps);
        if (lastProcessedNanos != 0 && (now - lastProcessedNanos) < minInterval) {
            returnBuffer(cam, data);
            return;
        }
        lastProcessedNanos = now;

        try {
            long ptsUs = now / 1000L;

            // 需要旋转时先转一帧，后续编码与 JPEG 都用旋转后的数据
            byte[] frame = data;
            if (rotation != 0 && rotBuf != null) {
                YuvRotator.rotate(data, rotBuf, width, height, rotation);
                frame = rotBuf;
            }

            if (encoder != null) {
                encoder.encode(frame, ptsUs);
            }

            PreviewListener pl = previewListener;
            if (mjpegServer != null || pl != null) {
                // 三种取图节奏，取最"急"的那个：
                //   - 界面正在看预览：8fps，画面才跟手
                //   - 有 HTTP 客户端在看流：mjpegFps
                //   - 都没人：2 秒一张，只为让 /snapshot.jpg 随时可用
                long jpegInterval;
                if (pl != null) {
                    jpegInterval = 1_000_000_000L / 8;
                } else if (mjpegServer != null && mjpegServer.hasClients()) {
                    jpegInterval = 1_000_000_000L / Math.max(1, mjpegFps);
                } else {
                    jpegInterval = 2_000_000_000L;
                }
                if (lastJpegNanos == 0 || (now - lastJpegNanos) >= jpegInterval) {
                    lastJpegNanos = now;
                    byte[] jpeg = compressToJpeg(frame);
                    if (jpeg != null) {
                        if (mjpegServer != null) {
                            mjpegServer.pushJpeg(jpeg);
                        }
                        if (pl != null) {
                            try {
                                pl.onPreviewJpeg(jpeg, generation);
                            } catch (Throwable ignored) {
                                // 界面销毁等竞态，忽略即可
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "frame: " + t);
        }

        updateFps(now);
        returnBuffer(cam, data);
    }

    private void returnBuffer(Camera cam, byte[] data) {
        if (cam == camera && data != null) {
            try { cam.addCallbackBuffer(data); } catch (RuntimeException ignored) { }
        }
    }

    private static String errorMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.length() == 0 ? failure.getClass().getSimpleName() : message;
    }

    private byte[] compressToJpeg(byte[] nv21) {
        try {
            jpegBuffer.reset();
            YuvImage image = new YuvImage(nv21, ImageFormat.NV21, videoWidth, videoHeight, null);
            if (!image.compressToJpeg(cropRect, 72, jpegBuffer)) {
                return null;
            }
            return jpegBuffer.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "jpeg: " + t);
            return null;
        }
    }

    private void updateFps(long now) {
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
            fpsWindowCount = 0;
        }
        fpsWindowCount++;
        if (now - fpsWindowStart >= 1_000_000_000L) {
            measuredFps = fpsWindowCount;
            fpsWindowCount = 0;
            fpsWindowStart = now;
            if (rtspServer != null) {
                rtspClients = rtspServer.clientCount();
            }
            if (mjpegServer != null) {
                httpClients = mjpegServer.clientCount();
            }
        }
    }

    // ------------------------------------------------------------------
    // H264Encoder.Listener
    // ------------------------------------------------------------------

    @Override
    public void onEncodedFrame(byte[] data, long ptsUs, boolean keyFrame) {
        RtspServer s = rtspServer;
        if (s != null) {
            s.pushFrame(data, ptsUs, keyFrame);
        }
    }

    @Override
    public void onCodecConfig(byte[] sps, byte[] pps) {
        RtspServer s = rtspServer;
        if (s != null) {
            s.setCodecConfig(sps, pps);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Camera.Size pickPreviewSize(List<Camera.Size> sizes, int w, int h) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = null;
        long target = (long) w * h;
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long diff = Math.abs((long) s.width * s.height - target);
            // 同面积时优先 4:3，摄像头原生比例通常更自然
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private static int[] pickFpsRange(List<int[]> ranges, int targetFps) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        int target = targetFps * 1000;
        int[] best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int[] r : ranges) {
            // 区间越窄越稳定，避免摄像头自动在 5~30 之间乱跳
            int max = r[1];
            int diff = Math.abs(max - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        return best;
    }

    private static boolean supports(List<String> modes, String mode) {
        return modes != null && modes.contains(mode);
    }

    // ------------------------------------------------------------------
    // 前台通知 / 唤醒锁
    // ------------------------------------------------------------------

    private void startForegroundCompat() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "余光 · 视频服务", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent intent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        String ip = NetUtils.localIp();
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("余光 / Afterglow 运行中")
                .setContentText(String.format(Locale.US, "%s · rtsp://%s:%d/live", ip, ip, RTSP_PORT))
                .setOngoing(true)
                .setContentIntent(pi);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "afterglow:stream");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakelock: " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }
}
