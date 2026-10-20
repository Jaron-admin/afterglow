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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground capture owner. One serial worker owns capture, transforms and streaming. */
public final class CameraStreamService extends Service {
    private static final String TAG = "CameraService";
    public static final String ACTION_START = "com.oldphone.ipcam.action.START";
    public static final String ACTION_STOP = "com.oldphone.ipcam.action.STOP";
    public static final String ACTION_RECONFIGURE = "com.oldphone.ipcam.action.RECONFIGURE";
    public static final String ACTION_ORIENTATION_SETTINGS =
            "com.oldphone.ipcam.action.ORIENTATION_SETTINGS";

    /** Complete upright view plus its exact output crop, never an output-only JPEG. */
    public interface PreviewListener {
        void onPreviewFrame(PreviewFrame frame);
    }
    private static volatile PreviewListener previewListener;
    public static void setPreviewListener(PreviewListener listener) { previewListener = listener; }

    public static final int RTSP_PORT = 8554;
    public static final int HTTP_PORT = 8080;
    private static final String CHANNEL_ID = "ipcam_foreground";
    private static final int NOTIFICATION_ID = 1001;

    /** A stream is requested, including initial startup and full reconfiguration. */
    public static volatile boolean running;
    public static volatile boolean reconfiguring;
    public static volatile long streamGeneration;
    public static volatile String lastError = "";
    public static volatile int measuredFps;
    public static volatile int rtspClients;
    public static volatile int httpClients;
    /** User-selected output dimensions; physical device rotation never swaps them. */
    public static volatile int videoWidth;
    public static volatile int videoHeight;
    public static volatile String cameraLabel = "";
    public static volatile int sensorOrientation;
    public static volatile FrameGeometry frameGeometry;
    /** Last stable physical pose, even while correction is locked to a saved pose. */
    public static volatile int deviceOrientation;
    public static volatile CameraCatalog.Entry activeCamera;

    // A replacement service joins the same worker until its predecessor has released
    // its resources. Native camera ownership cannot overlap across service instances.
    private static final Object WORKER_LOCK = new Object();
    private static HandlerThread sharedCameraThread;
    private static Handler sharedCameraHandler;
    private static int workerUsers;
    private static volatile CameraStreamService stateOwner;
    private static final AtomicLong GEOMETRY_REVISIONS = new AtomicLong();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler mainHandler;
    private StreamLifecycle lifecycle;
    private DeviceOrientationMonitor orientationMonitor;
    private boolean orientationMonitoring;
    private volatile boolean destroyed;
    private volatile boolean streaming;
    private volatile int latestStartId;

    private CameraCapture capture;
    private CameraCatalog.Entry captureEntry;
    private H264Encoder encoder;
    private RtspServer rtspServer;
    private MjpegServer mjpegServer;
    private PowerManager.WakeLock wakeLock;

    private int outputWidth;
    private int outputHeight;
    private int fps;
    private int bitrate;
    private int mjpegFps;
    private byte[] outputBuffer;
    private byte[] fullPreviewBuffer;
    private FrameGeometry geometry;
    private long appliedOrientationSettings = -1;
    private int manualRotation;
    private long lastProcessedNanos;
    private long lastOutputJpegNanos;
    private long lastPreviewNanos;
    private long fpsWindowStart;
    private int fpsWindowCount;
    private final ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream(256 * 1024);

    /** One immutable settings snapshot; only replaced on the main thread. */
    private static final class OrientationSettings {
        final boolean locked;
        final int lockedPose;
        final long revision;
        OrientationSettings(boolean locked, int lockedPose, long revision) {
            this.locked = locked;
            this.lockedPose = lockedPose;
            this.revision = revision;
        }
    }
    private volatile OrientationSettings orientationSettings;

    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        mainHandler = new Handler(getMainLooper());
        stateOwner = this;
        frameGeometry = null;
        activeCamera = null;
        refreshOrientationSettings();
        orientationMonitor = new DeviceOrientationMonitor(this,
                new DeviceOrientationMonitor.Listener() {
                    @Override public void onOrientation(int degrees) {
                        if (!destroyed && stateOwner == CameraStreamService.this) {
                            deviceOrientation = degrees;
                        }
                    }
                });
        deviceOrientation = orientationMonitor.current();
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
                openCaptureAndServers(generation);
            }
            @Override public void close() { closeResources(); }
        }, new StreamLifecycle.Observer() {
            @Override public void stateChanged(boolean requested, boolean configuring,
                                               long generation, Throwable failure) {
                if (stateOwner != CameraStreamService.this) return;
                streamGeneration = generation;
                running = requested;
                reconfiguring = configuring;
                if (configuring || !requested) {
                    streaming = false;
                    frameGeometry = null;
                }
                if (requested && configuring) lastError = "";
                if (failure != null) {
                    lastError = "视频服务启动失败：" + errorMessage(failure);
                    Log.e(TAG, "stream start failed", failure);
                }
            }
            @Override public void stopped(final long generation) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && stateOwner == CameraStreamService.this
                                && !running && streamGeneration == generation) {
                            stopOrientationMonitoring();
                            stopForeground(true);
                            stopSelfResult(latestStartId);
                        }
                    }
                });
            }
        }, streamGeneration);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        latestStartId = startId;
        if (ACTION_STOP.equals(action)) {
            stopOrientationMonitoring();
            lifecycle.stop();
            return START_NOT_STICKY;
        }
        if (ACTION_ORIENTATION_SETTINGS.equals(action)) {
            refreshOrientationSettings();
            // Lock/calibration changes are committed with a new geometry at a frame
            // boundary. They do not reopen capture, codec or network connections.
            if (!running) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
        } else if (ACTION_RECONFIGURE.equals(action)) {
            refreshOrientationSettings();
            lifecycle.reconfigure();
            if (!running) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
        } else if (ACTION_START.equals(action) || action == null) {
            refreshOrientationSettings();
            startForegroundCompat();
            if (!orientationMonitoring) {
                orientationMonitor.start();
                orientationMonitoring = true;
                deviceOrientation = orientationMonitor.current();
            }
            lifecycle.start();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        stopOrientationMonitoring();
        lifecycle.shutdown();
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

    private void refreshOrientationSettings() {
        OrientationSettings previous = orientationSettings;
        orientationSettings = new OrientationSettings(Prefs.orientationLocked(),
                Prefs.lockedDeviceOrientation(), previous == null ? 1 : previous.revision + 1);
    }

    private void stopOrientationMonitoring() {
        if (orientationMonitor != null && orientationMonitoring) orientationMonitor.stop();
        orientationMonitoring = false;
    }

    private boolean isSessionCurrent(long generation) {
        return !destroyed && stateOwner == this && lifecycle.isCurrent(generation);
    }

    /** Worker-only. Capture callbacks borrow data only for their synchronous duration. */
    private void openCaptureAndServers(final long generation) throws Exception {
        outputWidth = Prefs.outputWidth();
        outputHeight = Prefs.outputHeight();
        fps = Prefs.fps();
        bitrate = Prefs.bitrate();
        mjpegFps = Math.min(fps, 12);
        acquireWakeLock();
        capture = CameraCapture.open(this, Prefs.cameraKey(), outputWidth, outputHeight,
                fps, cameraHandler, new CameraCapture.Listener() {
                    @Override public void onFrame(byte[] nv21, int width, int height) {
                        processFrame(nv21, width, height, generation);
                    }
                    @Override public void onError(String message) {
                        failCurrentSession(generation, "摄像头采集失败：" + message);
                    }
                });
        if (!isSessionCurrent(generation)) return;
        captureEntry = capture.entry;
        activeCamera = captureEntry;
        cameraLabel = captureEntry.label;
        sensorOrientation = captureEntry.sensorOrientation;
        videoWidth = outputWidth;
        videoHeight = outputHeight;
        geometry = null;
        geometryForFrame(capture.width, capture.height, generation);
        long outputBytes = (long) outputWidth * outputHeight * 3 / 2;
        if (outputBytes <= 0 || outputBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("输出尺寸过大");
        }
        outputBuffer = new byte[(int) outputBytes];
        startServers(generation);
        if (!isSessionCurrent(generation)) return;
        lastProcessedNanos = 0;
        lastOutputJpegNanos = 0;
        lastPreviewNanos = 0;
        fpsWindowStart = 0;
        fpsWindowCount = 0;
        streaming = true;
        Log.i(TAG, "capture opened " + capture.width + "x" + capture.height
                + " -> " + outputWidth + "x" + outputHeight + " camera=" + cameraLabel);
    }

    private void startServers(final long generation) throws Exception {
        if (Prefs.needRtsp()) {
            try {
                rtspServer = new RtspServer(RTSP_PORT);
                rtspServer.setVideoInfo(outputWidth, outputHeight, bitrate);
                encoder = new H264Encoder();
                encoder.start(outputWidth, outputHeight, fps, bitrate, new H264Encoder.Listener() {
                    @Override public void onEncodedFrame(byte[] data, long ptsUs, boolean keyFrame) {
                        RtspServer server = rtspServer;
                        if (server != null && isSessionCurrent(generation)) {
                            server.pushFrame(data, ptsUs, keyFrame);
                        }
                    }
                    @Override public void onCodecConfig(byte[] sps, byte[] pps) {
                        RtspServer server = rtspServer;
                        if (server != null && isSessionCurrent(generation)) {
                            server.setCodecConfig(sps, pps);
                        }
                    }
                });
                rtspServer.start();
            } catch (Throwable failure) {
                throw new IOException("RTSP 编码/服务启动失败（" + outputWidth + "×"
                        + outputHeight + "，" + fps + " fps）：" + errorMessage(failure), failure);
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
            } catch (Throwable failure) {
                throw new IOException("MJPEG 服务启动失败：" + errorMessage(failure), failure);
            }
        }
        if (rtspServer == null && mjpegServer == null) {
            throw new IOException("没有可用的视频服务");
        }
    }

    /** Pure geometry is committed once before both consumers process this frame. */
    private FrameGeometry geometryForFrame(int rawWidth, int rawHeight, long generation) {
        OrientationSettings settings = orientationSettings;
        if (geometry == null || appliedOrientationSettings != settings.revision) {
            // Auto selection can resolve to a different physical camera. Calibration
            // is looked up by the camera actually opened, never by the "auto" option.
            manualRotation = Prefs.manualRotationDegrees(captureEntry.key);
        }
        int pose = settings.locked ? settings.lockedPose : deviceOrientation;
        int rotation = FrameGeometry.rotation(captureEntry.sensorOrientation,
                captureEntry.facing == 1, pose, manualRotation);
        if (geometry == null || geometry.rawWidth != rawWidth || geometry.rawHeight != rawHeight
                || geometry.rotation != rotation || appliedOrientationSettings != settings.revision) {
            geometry = FrameGeometry.create(rawWidth, rawHeight, rotation, outputWidth,
                    outputHeight, GEOMETRY_REVISIONS.incrementAndGet());
            appliedOrientationSettings = settings.revision;
            synchronized (lifecycle) {
                if (isSessionCurrent(generation)) frameGeometry = geometry;
            }
            // Publish the newly cropped snapshot and full preview promptly.
            lastOutputJpegNanos = 0;
            lastPreviewNanos = 0;
        }
        return geometry;
    }

    private void processFrame(byte[] nv21, int rawWidth, int rawHeight, long generation) {
        if (!streaming || capture == null || !isSessionCurrent(generation)) return;
        long now = System.nanoTime();
        long minInterval = 1_000_000_000L / Math.max(1, fps);
        if (lastProcessedNanos != 0 && now - lastProcessedNanos < minInterval) return;
        lastProcessedNanos = now;
        try {
            final FrameGeometry framePlan = geometryForFrame(rawWidth, rawHeight, generation);
            Nv21Transform.render(nv21, framePlan, outputBuffer);
            if (encoder != null) encoder.encode(outputBuffer, now / 1000L);

            // Remote MJPEG/snapshot always receive the cropped fixed-size output.
            if (mjpegServer != null) {
                long interval = mjpegServer.hasClients()
                        ? 1_000_000_000L / Math.max(1, mjpegFps) : 2_000_000_000L;
                if (lastOutputJpegNanos == 0 || now - lastOutputJpegNanos >= interval) {
                    lastOutputJpegNanos = now;
                    byte[] jpeg = compressToJpeg(outputBuffer, outputWidth, outputHeight);
                    if (jpeg != null && isSessionCurrent(generation)) mjpegServer.pushJpeg(jpeg);
                }
            }

            // Local preview keeps the complete view so the UI can draw the exact crop
            // mask. No full-frame rotation/allocation is done without a listener.
            PreviewListener listener = previewListener;
            if (listener != null && (lastPreviewNanos == 0
                    || now - lastPreviewNanos >= 1_000_000_000L / 8)) {
                lastPreviewNanos = now;
                byte[] fullFrame = nv21;
                if (framePlan.rotation != 0) {
                    int size = framePlan.fullWidth * framePlan.fullHeight * 3 / 2;
                    if (fullPreviewBuffer == null || fullPreviewBuffer.length != size) {
                        fullPreviewBuffer = new byte[size];
                    }
                    Nv21Transform.rotateFull(nv21, framePlan, fullPreviewBuffer);
                    fullFrame = fullPreviewBuffer;
                }
                byte[] jpeg = compressToJpeg(fullFrame, framePlan.fullWidth, framePlan.fullHeight);
                if (jpeg != null && isSessionCurrent(generation)) {
                    try { listener.onPreviewFrame(new PreviewFrame(jpeg, framePlan, generation)); }
                    catch (Throwable ignored) { /* Activity replacement must not stop streaming. */ }
                }
            }
        } catch (Throwable failure) {
            Log.e(TAG, "frame transform failed", failure);
            failCurrentSession(generation, "视频处理失败：" + errorMessage(failure));
            return;
        }
        if (isSessionCurrent(generation)) updateFps(now);
    }

    private void failCurrentSession(long generation, String message) {
        synchronized (lifecycle) {
            // Error callbacks may race a newer start/configuration on main. The
            // generation check and stop must be one operation for that reason.
            if (!isSessionCurrent(generation)) return;
            lastError = message;
            lifecycle.stop();
        }
    }

    private byte[] compressToJpeg(byte[] nv21, int width, int height) {
        try {
            jpegBuffer.reset();
            YuvImage image = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            if (!image.compressToJpeg(new Rect(0, 0, width, height), 72, jpegBuffer)) return null;
            return jpegBuffer.toByteArray();
        } catch (Throwable failure) {
            Log.w(TAG, "jpeg: " + failure);
            return null;
        }
    }

    /** Worker-only and idempotent, including every partially initialized resource. */
    private void closeResources() {
        streaming = false;
        CameraCapture oldCapture = capture;
        capture = null;
        if (oldCapture != null) {
            try { oldCapture.close(); } catch (Throwable failure) { Log.w(TAG, "capture stop", failure); }
        }
        if (rtspServer != null) {
            try { rtspServer.stop(); } catch (Throwable failure) { Log.w(TAG, "rtsp stop", failure); }
            rtspServer = null;
        }
        if (mjpegServer != null) {
            try { mjpegServer.stop(); } catch (Throwable failure) { Log.w(TAG, "http stop", failure); }
            mjpegServer = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Throwable failure) { Log.w(TAG, "encoder stop", failure); }
            encoder = null;
        }
        releaseWakeLock();
        captureEntry = null;
        outputBuffer = null;
        fullPreviewBuffer = null;
        geometry = null;
        appliedOrientationSettings = -1;
        if (stateOwner == this) {
            measuredFps = 0;
            rtspClients = 0;
            httpClients = 0;
            cameraLabel = "";
            sensorOrientation = 0;
            videoWidth = 0;
            videoHeight = 0;
            frameGeometry = null;
            activeCamera = null;
        }
        Log.i(TAG, "stopped");
    }

    private void updateFps(long now) {
        if (fpsWindowStart == 0) { fpsWindowStart = now; fpsWindowCount = 0; }
        fpsWindowCount++;
        if (now - fpsWindowStart >= 1_000_000_000L) {
            measuredFps = fpsWindowCount;
            fpsWindowCount = 0;
            fpsWindowStart = now;
            if (rtspServer != null) rtspClients = rtspServer.clientCount();
            if (mjpegServer != null) httpClients = mjpegServer.clientCount();
        }
    }

    private static String errorMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.length() == 0 ? failure.getClass().getSimpleName() : message;
    }

    private void startForegroundCompat() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "余光 · 视频服务", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent intent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, flags);
        String ip = NetUtils.localIp();
        String address = Prefs.needRtsp()
                ? String.format(Locale.US, "rtsp://%s:%d/live", ip, RTSP_PORT)
                : String.format(Locale.US, "http://%s:%d/", ip, HTTP_PORT);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("余光 / Afterglow 运行中")
                .setContentText(address)
                .setOngoing(true)
                .setContentIntent(pending);
        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (manager != null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "afterglow:stream");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable failure) { Log.w(TAG, "wakelock: " + failure); }
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable ignored) { }
        wakeLock = null;
    }
}
