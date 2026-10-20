package com.oldphone.ipcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.view.Surface;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {

    private static final int REQ_PERM = 100;

    /** 外部拉起推流用的 intent extra */
    public static final String EXTRA_AUTOSTART = "autostart";
    /** 外部指定摄像头：0 自动 / 1 后置 / 2 前置 */
    public static final String EXTRA_CAMERA = "camera";
    /** 外部指定旋转：0 / 90 / 180 / 270 */
    public static final String EXTRA_ROTATION = "rotation";
    /**
     * 调试用：把当前界面渲染成 PNG 存到应用外部目录。
     * 设备锁屏/关屏时 screencap 只能拍到锁屏页，这个口子可以在不改变任何
     * 窗口行为的前提下检查布局，排查界面问题很方便。
     *   adb shell am start -n com.oldphone.ipcam/.MainActivity --ez uishot true
     */
    public static final String EXTRA_UI_SHOT = "uishot";
    /** 直接打开指定 IP 的查看页（可用来给某一路流建桌面快捷方式） */
    public static final String EXTRA_OPEN_IP = "open_ip";
    /** 配合 open_ip：把对方当作「仅 RTSP」设备处理 */
    public static final String EXTRA_RTSP_ONLY = "rtsp_only";

    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvRtsp;
    private TextView tvMjpeg;
    private TextView tvSnapshot;
    private Button btnToggle;

    private View rowRtsp;
    private View rowMjpeg;
    private View rowSnapshot;

    private CropPreviewView ivPreview;
    private View flPreview;
    private TextView tvGeometry;
    private View llPreviewEmpty;
    private TextView tvPreviewEmptyTitle;
    private TextView tvPreviewEmptyHint;

    private Spinner spResolution;
    private Spinner spFps;
    private Spinner spBitrate;
    private Spinner spMode;
    private Spinner spCamera;
    private Spinner spOutputLayout;
    private CheckBox cbOrientationLock;
    private View llResolution;
    private View llCustomSize;
    private EditText etOutputWidth;
    private EditText etOutputHeight;
    private final List<CameraCatalog.Entry> cameraEntries = new ArrayList<CameraCatalog.Entry>();
    private final List<String> cameraKeys = new ArrayList<String>();
    private final List<int[]> outputSizes = new ArrayList<int[]>();
    private boolean loadingCameras;
    private int pendingCalibrationDegrees = -1;
    private CameraCatalog.Entry lastActiveCamera;
    private long previewRevision = -1;
    private FrameGeometry displayedGeometry;

    private CheckBox cbBoot;
    private CheckBox cbAuth;
    private LinearLayout llAuth;
    private EditText etUser;
    private EditText etPass;

    private TextView tvDevicesStatus;
    private LinearLayout llDevices;
    private TextView btnRescan;

    private boolean scanning;
    private boolean autoScanned;
    private int foundCount;
    /** 本次启动是否要求把界面渲染成 PNG（调试用） */
    private boolean shotRequested;

    private final Handler handler = new Handler();
    private boolean suppressListener;

    // ---- 预览 ----
    /** 解码线程：JPEG 解码不能放在相机线程，也不能放在 UI 线程 */
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    /** 上一帧还没解完就丢新帧，避免慢机器上越积越多 */
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    /** 双缓冲 + inBitmap 复用，避免 8fps 下每秒几 MB 的 GC 压力 */
    private final JpegFrameDecoder previewDecoder = new JpegFrameDecoder();
    private volatile boolean hasPreviewFrame;
    private volatile boolean previewActive;
    private volatile long previewEpoch;
    private long previewGeneration = -1;
    private volatile long rejectedPreviewGeneration = -1;

    private final Runnable applyStreamChanges = new Runnable() {
        @Override
        public void run() {
            pendingStreamChanges = false;
            // Editing preferences never starts a stopped camera.
            if (!CameraStreamService.running) {
                return;
            }
            previewEpoch++;
            rejectedPreviewGeneration = CameraStreamService.streamGeneration;
            resetPreview();
            try {
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_RECONFIGURE);
                startService(intent);
                tvStatus.setText(R.string.status_switching);
                tvPreviewEmptyTitle.setText(R.string.status_switching);
            } catch (Throwable t) {
                rejectedPreviewGeneration = -1;
                Toast.makeText(MainActivity.this, "设置未应用：" + t.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    private final CameraStreamService.PreviewListener previewListener =
            new CameraStreamService.PreviewListener() {
                @Override
                public void onPreviewFrame(final PreviewFrame frame) {
                    final Handler h = decodeHandler;
                    final long epoch = previewEpoch;
                    if (h == null || !acceptPreview(frame, epoch)
                            || !decoding.compareAndSet(false, true)) {
                        return;
                    }
                    if (!h.post(new Runnable() {
                        @Override
                        public void run() {
                            Bitmap bmp = null;
                            try {
                                if (acceptPreview(frame, epoch)) {
                                    bmp = previewDecoder.decode(frame.jpeg);
                                }
                            } catch (Throwable t) {
                                Log.w("MainActivity", "preview decode failed", t);
                            }
                            if (bmp == null) {
                                decoding.set(false);
                                return;
                            }
                            final Bitmap out = bmp;
                            // Hold the gate through the UI handoff: the decoder owns both
                            // reusable bitmaps and must not overwrite one still queued to draw.
                            if (!handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (acceptPreview(frame, epoch) && !isFinishing()) {
                                            showPreviewFrame(out, frame);
                                        }
                                    } finally {
                                        decoding.set(false);
                                    }
                                }
                            })) {
                                decoding.set(false);
                            }
                        }
                    })) {
                        decoding.set(false);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        applyRequestedOrientation();
        setContentView(R.layout.activity_main);
        bindViews();
        setupSpinners();
        setupListeners();
        refreshAddresses();

        decodeThread = new HandlerThread("preview-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * 支持从外部一键拉起推流：
     *   adb shell am start -n com.oldphone.ipcam/.MainActivity --ez autostart true
     *   （Tasker / 快捷方式同样适用）
     * 之所以走 Activity 而不是直接给 Service 打洞，是为了不用把 Service 改成 exported。
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        // 截图标记要先读，后面 openViewer 会把它透传给查看页
        if (intent.getBooleanExtra(EXTRA_UI_SHOT, false)) {
            shotRequested = true;
            intent.removeExtra(EXTRA_UI_SHOT);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    captureUiToFile();
                }
            }, 600);
        }
        // 直接打开某一路流的查看页：--es open_ip 10.0.0.5 [--ez rtsp_only true]
        if (intent.hasExtra(EXTRA_OPEN_IP)) {
            String targetIp = intent.getStringExtra(EXTRA_OPEN_IP);
            boolean rtspOnly = intent.getBooleanExtra(EXTRA_RTSP_ONLY, false);
            intent.removeExtra(EXTRA_OPEN_IP);
            intent.removeExtra(EXTRA_RTSP_ONLY);
            if (targetIp != null && targetIp.length() > 0) {
                LanScanner.Device device = new LanScanner.Device();
                device.ip = targetIp;
                device.rtspOnly = rtspOnly;
                openViewer(device);
            }
        }
        boolean streamChanged = false;
        // 允许外部指定摄像头：--ei camera 0|1|2（0 自动 / 1 后置 / 2 前置）
        if (intent.hasExtra(EXTRA_CAMERA)) {
            int choice = intent.getIntExtra(EXTRA_CAMERA, Prefs.CAM_AUTO);
            if (choice < 0 || choice > Prefs.CAM_FRONT) {
                choice = Prefs.CAM_AUTO;
            }
            String key = choice == Prefs.CAM_FRONT ? "facing:front"
                    : choice == Prefs.CAM_BACK ? "facing:back" : "auto";
            streamChanged |= !Prefs.cameraKey().equals(key);
            Prefs.setCameraKey(key);
            normalizeLegacyCameraKey();
            intent.removeExtra(EXTRA_CAMERA);
            if (spCamera != null) {
                suppressListener = true;
                refreshCameraOptions();
                suppressListener = false;
            }
        }
        if (intent.hasExtra(EXTRA_ROTATION)) {
            int deg = intent.getIntExtra(EXTRA_ROTATION, 0);
            deg = ((deg % 360) + 360) % 360;
            if (deg % 90 != 0) deg = 0;
            String calibrationKey = calibrationCameraKey();
            if (calibrationKey == null) {
                pendingCalibrationDegrees = deg;
            } else {
                Prefs.setManualRotationDegrees(calibrationKey, deg);
                applyOrientationSettings();
            }
            intent.removeExtra(EXTRA_ROTATION);
        }
        if (streamChanged) {
            scheduleStreamChanges();
        }
        if (!intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            return;
        }
        intent.removeExtra(EXTRA_AUTOSTART);
        if (CameraStreamService.running || !hasCameraPermission()) {
            return;
        }
        if (saveSettings()) {
            startServiceNow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        previewEpoch++;
        previewActive = true;
        syncSelections();
        applyRequestedOrientation();
        loadCameras();
        refreshUi();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
        // 只有界面在前台时才让服务压缩预览帧，退到后台立刻停止，省 CPU
        CameraStreamService.setPreviewListener(previewListener);

        // 首次进入自动扫一次，之后交给「重新扫描」按钮，避免每次切回都扫全网段
        if (!autoScanned) {
            autoScanned = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scanDevices();
                }
            }, 500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        previewActive = false;
        previewEpoch++;
        saveAuth();
        // Flush user changes before leaving; a later Activity teardown must not lose them.
        handler.removeCallbacks(applyStreamChanges);
        if (pendingStreamChanges) {
            pendingStreamChanges = false;
            applyStreamChanges.run();
        }
        resetPreview();
        handler.removeCallbacks(refresher);
        CameraStreamService.setPreviewListener(null);
    }

    @Override
    protected void onDestroy() {
        previewActive = false;
        previewEpoch++;
        handler.removeCallbacksAndMessages(null);
        CameraStreamService.setPreviewListener(null);
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        super.onDestroy();
    }

    private void bindViews() {
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvDetail = (TextView) findViewById(R.id.tvDetail);
        tvRtsp = (TextView) findViewById(R.id.tvRtsp);
        tvMjpeg = (TextView) findViewById(R.id.tvMjpeg);
        tvSnapshot = (TextView) findViewById(R.id.tvSnapshot);
        btnToggle = (Button) findViewById(R.id.btnToggle);

        rowRtsp = findViewById(R.id.rowRtsp);
        rowMjpeg = findViewById(R.id.rowMjpeg);
        rowSnapshot = findViewById(R.id.rowSnapshot);

        ivPreview = (CropPreviewView) findViewById(R.id.ivPreview);
        flPreview = findViewById(R.id.flPreview);
        tvGeometry = (TextView) findViewById(R.id.tvGeometry);
        llPreviewEmpty = findViewById(R.id.llPreviewEmpty);
        tvPreviewEmptyTitle = (TextView) findViewById(R.id.tvPreviewEmptyTitle);
        tvPreviewEmptyHint = (TextView) findViewById(R.id.tvPreviewEmptyHint);

        spResolution = (Spinner) findViewById(R.id.spResolution);
        spFps = (Spinner) findViewById(R.id.spFps);
        spBitrate = (Spinner) findViewById(R.id.spBitrate);
        spMode = (Spinner) findViewById(R.id.spMode);
        spCamera = (Spinner) findViewById(R.id.spCamera);
        spOutputLayout = (Spinner) findViewById(R.id.spOutputLayout);
        cbOrientationLock = (CheckBox) findViewById(R.id.cbOrientationLock);
        llResolution = findViewById(R.id.llResolution);
        llCustomSize = findViewById(R.id.llCustomSize);
        etOutputWidth = (EditText) findViewById(R.id.etOutputWidth);
        etOutputHeight = (EditText) findViewById(R.id.etOutputHeight);

        cbBoot = (CheckBox) findViewById(R.id.cbBoot);
        cbAuth = (CheckBox) findViewById(R.id.cbAuth);
        llAuth = (LinearLayout) findViewById(R.id.llAuth);
        etUser = (EditText) findViewById(R.id.etUser);
        etPass = (EditText) findViewById(R.id.etPass);

        tvDevicesStatus = (TextView) findViewById(R.id.tvDevicesStatus);
        llDevices = (LinearLayout) findViewById(R.id.llDevices);
        btnRescan = (TextView) findViewById(R.id.btnRescan);
    }

    private void setupSpinners() {
        suppressListener = true;

        refreshCameraOptions();
        bindSpinner(spOutputLayout, getResources().getStringArray(R.array.output_layouts), Prefs.outputLayout());
        refreshResolutionOptions();
        cbOrientationLock.setChecked(Prefs.orientationLocked());
        etOutputWidth.setText(String.valueOf(Prefs.outputWidth()));
        etOutputHeight.setText(String.valueOf(Prefs.outputHeight()));
        showOutputEditors();
        bindSpinner(spFps, Prefs.FPS_LABELS, Prefs.getInt(Prefs.KEY_FPS, 2));
        bindSpinner(spBitrate, Prefs.BITRATE_LABELS, Prefs.getInt(Prefs.KEY_BITRATE, 2));
        bindSpinner(spMode, Prefs.MODE_LABELS, Prefs.getInt(Prefs.KEY_MODE, Prefs.MODE_BOTH));

        cbBoot.setChecked(Prefs.getBool(Prefs.KEY_BOOT, false));
        cbAuth.setChecked(Prefs.getBool(Prefs.KEY_AUTH, false));
        etUser.setText(Prefs.getStr(Prefs.KEY_USER, "admin"));
        etPass.setText(Prefs.getStr(Prefs.KEY_PASS, "123456"));
        llAuth.setVisibility(cbAuth.isChecked() ? View.VISIBLE : View.GONE);

        suppressListener = false;
    }

    private void bindSpinner(Spinner spinner, String[] labels, int selection) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.item_spinner, labels);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        if (selection >= 0 && selection < labels.length) {
            spinner.setSelection(selection, false);
        }
    }

    private void loadCameras() {
        if (loadingCameras) return;
        loadingCameras = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<CameraCatalog.Entry> found;
                try {
                    found = CameraCatalog.list(getApplicationContext());
                } catch (Throwable error) {
                    Log.w("MainActivity", "camera catalog unavailable", error);
                    found = new ArrayList<CameraCatalog.Entry>();
                }
                final List<CameraCatalog.Entry> result = found;
                handler.post(new Runnable() {
                    @Override public void run() {
                        loadingCameras = false;
                        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                        cameraEntries.clear();
                        cameraEntries.addAll(result);
                        mergeActiveCamera();
                        normalizeLegacyCameraKey();
                        String calibrationKey = calibrationCameraKey();
                        if (pendingCalibrationDegrees >= 0 && calibrationKey != null) {
                            Prefs.setManualRotationDegrees(calibrationKey, pendingCalibrationDegrees);
                            pendingCalibrationDegrees = -1;
                            applyOrientationSettings();
                        }
                        refreshCameraOptions();
                        refreshResolutionOptions();
                        updateGeometryLabel();
                    }
                });
            }
        }, "camera-catalog").start();
    }

    private void refreshCameraOptions() {
        boolean wasSuppressed = suppressListener;
        suppressListener = true;
        cameraKeys.clear();
        List<String> labels = new ArrayList<String>();
        cameraKeys.add("auto");
        labels.add(getString(R.string.camera_auto));
        for (CameraCatalog.Entry camera : cameraEntries) {
            if (!cameraKeys.contains(camera.key)) {
                cameraKeys.add(camera.key);
                labels.add(camera.label);
            }
        }
        String selected = Prefs.cameraKey();
        if (!cameraKeys.contains(selected)) {
            cameraKeys.add(selected);
            labels.add("facing:front".equals(selected) ? getString(R.string.camera_front_legacy)
                    : "facing:back".equals(selected) ? getString(R.string.camera_back_legacy)
                    : getString(R.string.camera_unavailable, selected));
        }
        bindSpinner(spCamera, labels.toArray(new String[0]), cameraKeys.indexOf(selected));
        suppressListener = wasSuppressed;
    }

    private void mergeActiveCamera() {
        CameraCatalog.Entry active = CameraStreamService.activeCamera;
        if (active == null) return;
        for (int i = 0; i < cameraEntries.size(); i++) {
            if (cameraEntries.get(i).key.equals(active.key)) {
                cameraEntries.set(i, active);
                return;
            }
        }
        cameraEntries.add(active);
    }

    private CameraCatalog.Entry selectedCamera() {
        String key = Prefs.cameraKey();
        int facing = "facing:front".equals(key) ? 1 : "facing:back".equals(key) ? 0 : -1;
        CameraCatalog.Entry active = CameraStreamService.activeCamera;
        if (CameraStreamService.running && active != null
                && ("auto".equals(key) || active.key.equals(key) || active.facing == facing)) return active;
        return CameraCatalog.find(cameraEntries, key);
    }

    private void normalizeLegacyCameraKey() {
        String key = Prefs.cameraKey();
        if (!"facing:front".equals(key) && !"facing:back".equals(key)) return;
        CameraCatalog.Entry resolved = selectedCamera();
        if (resolved != null) Prefs.setCameraKey(resolved.key);
    }

    private void refreshResolutionOptions() {
        if (spResolution == null) return;
        boolean wasSuppressed = suppressListener;
        suppressListener = true;
        outputSizes.clear();
        addOutputSize(640, 480);
        addOutputSize(1280, 720);
        addOutputSize(1920, 1080);
        CameraCatalog.Entry camera = selectedCamera();
        if (camera != null && camera.sizes != null) {
            for (int[] size : camera.sizes) {
                if (size != null && size.length >= 2) addOutputSize(size[0], size[1]);
            }
        }
        addOutputSize(Prefs.outputWidth(), Prefs.outputHeight());
        Collections.sort(outputSizes, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                long first = (long) a[0] * a[1];
                long second = (long) b[0] * b[1];
                return first < second ? -1 : first == second ? Integer.compare(a[0], b[0]) : 1;
            }
        });
        List<String> labels = new ArrayList<String>();
        int selected = 0;
        for (int i = 0; i < outputSizes.size(); i++) {
            int[] size = outputSizes.get(i);
            labels.add(String.format(Locale.US, "%d × %d", size[0], size[1]));
            if (size[0] == Prefs.outputWidth() && size[1] == Prefs.outputHeight()) selected = i;
        }
        bindSpinner(spResolution, labels.toArray(new String[0]), selected);
        suppressListener = wasSuppressed;
    }

    private void addOutputSize(int width, int height) {
        if (width < 16 || height < 16 || width > 4096 || height > 4096
                || (width & 1) != 0 || (height & 1) != 0 || (long) width * height > 8294400L) return;
        if (Prefs.outputLayout() != 2) {
            int longer = Math.max(width, height);
            int shorter = Math.min(width, height);
            width = Prefs.outputLayout() == 0 ? longer : shorter;
            height = Prefs.outputLayout() == 0 ? shorter : longer;
        }
        for (int[] size : outputSizes) if (size[0] == width && size[1] == height) return;
        outputSizes.add(new int[] { width, height });
    }

    private void showOutputEditors() {
        boolean custom = Prefs.outputLayout() == 2;
        llResolution.setVisibility(custom ? View.GONE : View.VISIBLE);
        llCustomSize.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private boolean saveCustomOutput() {
        int width = parseDimension(etOutputWidth);
        int height = parseDimension(etOutputHeight);
        if (width < 0 || height < 0) return false;
        if ((long) width * height > 8294400L) {
            etOutputHeight.setError(getString(R.string.output_pixel_limit));
            return false;
        }
        if (width != Prefs.outputWidth() || height != Prefs.outputHeight()) {
            Prefs.setOutputSize(width, height);
            updateGeometryLabel();
            scheduleStreamChanges();
        }
        return true;
    }

    private int parseDimension(EditText field) {
        int value;
        try { value = Integer.parseInt(field.getText().toString().trim()); }
        catch (NumberFormatException error) { value = -1; }
        if (value < 16 || value > 4096 || (value & 1) != 0) {
            field.setError(getString(R.string.output_dimension_invalid));
            return -1;
        }
        field.setError(null);
        return value;
    }

    private String calibrationCameraKey() {
        CameraCatalog.Entry selected = selectedCamera();
        return selected != null ? selected.key : null;
    }

    private void showCalibrationDialog() {
        final String key = calibrationCameraKey();
        if (key == null) {
            Toast.makeText(this, R.string.camera_catalog_waiting, Toast.LENGTH_SHORT).show();
            loadCameras();
            return;
        }
        final int previous = Prefs.manualRotationDegrees(key);
        final int[] selected = new int[] { previous / 90 };
        CameraCatalog.Entry camera = selectedCamera();
        String title = getString(R.string.calibrate_camera)
                + (camera == null ? "" : " · " + camera.label);
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(R.array.camera_calibration_offsets, selected[0],
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { selected[0] = which; }
                        })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        int degrees = selected[0] * 90;
                        if (degrees != previous) {
                            Prefs.setManualRotationDegrees(key, degrees);
                            applyOrientationSettings();
                        }
                    }
                })
                .setNeutralButton(R.string.calibration_reset, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Prefs.setManualRotationDegrees(key, 0);
                        applyOrientationSettings();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void applyOrientationSettings() {
        if (!CameraStreamService.running) return;
        try {
            Intent intent = new Intent(this, CameraStreamService.class);
            intent.setAction(CameraStreamService.ACTION_ORIENTATION_SETTINGS);
            startService(intent);
        } catch (Throwable error) {
            Toast.makeText(this, getString(R.string.orientation_apply_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void applyRequestedOrientation() {
        if (!Prefs.orientationLocked()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            return;
        }
        int current = getWindowManager().getDefaultDisplay().getRotation();
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean naturalPortrait = ((current == Surface.ROTATION_0 || current == Surface.ROTATION_180) && portrait)
                || ((current == Surface.ROTATION_90 || current == Surface.ROTATION_270) && !portrait);
        int locked = Prefs.lockedDisplayRotation();
        int[] requests = naturalPortrait ? new int[] {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } : new int[] {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        };
        setRequestedOrientation(requests[Math.max(0, Math.min(3, locked))]);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        flPreview.post(new Runnable() {
            @Override public void run() { resizePreview(displayedGeometry); }
        });
    }

    private void resizePreview(FrameGeometry geometry) {
        if (geometry == null || geometry.fullWidth <= 0 || geometry.fullHeight <= 0) return;
        View parent = (View) flPreview.getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        if (availableWidth <= 0) availableWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int limit = Math.max(dp(120), (int) (screenHeight * 0.42f));
        int width = Math.max(1, Math.min(availableWidth, (int) (limit * (float) geometry.fullWidth / geometry.fullHeight)));
        int height = Math.max(1, Math.round(width * (float) geometry.fullHeight / geometry.fullWidth));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) flPreview.getLayoutParams();
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER_HORIZONTAL) {
            params.width = width;
            params.height = height;
            params.gravity = Gravity.CENTER_HORIZONTAL;
            flPreview.setLayoutParams(params);
        }
    }

    private int dp(int pixels) { return Math.round(pixels * getResources().getDisplayMetrics().density); }

    private void updateGeometryLabel() {
        FrameGeometry geometry = CameraStreamService.frameGeometry;
        String text;
        if (CameraStreamService.running && geometry != null) {
            int divisor = gcd(geometry.fullWidth, geometry.fullHeight);
            text = getString(R.string.geometry_live, geometry.fullWidth, geometry.fullHeight,
                    geometry.fullWidth / divisor, geometry.fullHeight / divisor,
                    geometry.outputWidth, geometry.outputHeight);
        } else {
            CameraCatalog.Entry camera = selectedCamera();
            int[] sourceSize = largestSourceSize(camera);
            if (sourceSize != null) {
                int divisor = gcd(sourceSize[0], sourceSize[1]);
                text = getString(R.string.geometry_available, sourceSize[0], sourceSize[1],
                        sourceSize[0] / divisor, sourceSize[1] / divisor,
                        Prefs.outputWidth(), Prefs.outputHeight());
            } else {
                text = getString(R.string.geometry_unknown, Prefs.outputWidth(), Prefs.outputHeight());
            }
        }
        if (!text.contentEquals(tvGeometry.getText())) tvGeometry.setText(text);
    }

    private static int gcd(int first, int second) {
        while (second != 0) { int remainder = first % second; first = second; second = remainder; }
        return Math.max(1, first);
    }

    private static int[] largestSourceSize(CameraCatalog.Entry camera) {
        if (camera == null || camera.sizes == null) return null;
        int[] largest = null;
        for (int[] size : camera.sizes) {
            if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0
                    || size[0] > 4096 || size[1] > 4096 || (long) size[0] * size[1] > 8294400L) continue;
            if (largest == null || (long) size[0] * size[1] > (long) largest[0] * largest[1]) largest = size;
        }
        return largest;
    }

    private void setupListeners() {
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (CameraStreamService.running) {
                    stopServiceNow();
                } else {
                    if (!hasCameraPermission()) {
                        requestPermissions();
                        return;
                    }
                    if (saveSettings()) {
                        startServiceNow();
                    }
                }
            }
        });

        spCamera.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                if (position < 0 || position >= cameraKeys.size()) return;
                String key = cameraKeys.get(position);
                if (key.equals(Prefs.cameraKey())) return;
                Prefs.setCameraKey(key);
                refreshResolutionOptions();
                updateGeometryLabel();
                scheduleStreamChanges();
            }
        });
        spOutputLayout.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                if (position == Prefs.outputLayout()) return;
                int width = Prefs.outputWidth();
                int height = Prefs.outputHeight();
                Prefs.setOutputLayout(position);
                if (position != 2) {
                    int longSide = Math.max(width, height);
                    int shortSide = Math.min(width, height);
                    width = position == 0 ? longSide : shortSide;
                    height = position == 0 ? shortSide : longSide;
                }
                boolean changed = width != Prefs.outputWidth() || height != Prefs.outputHeight();
                Prefs.setOutputSize(width, height);
                etOutputWidth.setText(String.valueOf(width));
                etOutputHeight.setText(String.valueOf(height));
                etOutputWidth.setError(null);
                etOutputHeight.setError(null);
                refreshResolutionOptions();
                showOutputEditors();
                updateGeometryLabel();
                if (changed) scheduleStreamChanges();
            }
        });
        spResolution.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                if (Prefs.outputLayout() == 2 || position < 0 || position >= outputSizes.size()) return;
                int[] size = outputSizes.get(position);
                if (size[0] == Prefs.outputWidth() && size[1] == Prefs.outputHeight()) return;
                Prefs.setOutputSize(size[0], size[1]);
                etOutputWidth.setText(String.valueOf(size[0]));
                etOutputHeight.setText(String.valueOf(size[1]));
                etOutputWidth.setError(null);
                etOutputHeight.setError(null);
                updateGeometryLabel();
                scheduleStreamChanges();
            }
        });
        cbOrientationLock.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean locked) {
                if (suppressListener) return;
                int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                int deviceDegrees = CameraStreamService.running ? CameraStreamService.deviceOrientation
                        : (360 - displayRotation * 90) % 360;
                Prefs.setOrientationLock(locked, deviceDegrees, displayRotation);
                applyRequestedOrientation();
                applyOrientationSettings();
            }
        });
        findViewById(R.id.btnCalibrate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showCalibrationDialog(); }
        });
        findViewById(R.id.btnApplySize).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { saveCustomOutput(); }
        });
        etOutputHeight.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int action, KeyEvent event) {
                if (action == EditorInfo.IME_ACTION_DONE) { saveCustomOutput(); return true; }
                return false;
            }
        });
        spFps.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                updateStreamSetting(Prefs.KEY_FPS, 2, position);
            }
        });
        spBitrate.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                updateStreamSetting(Prefs.KEY_BITRATE, 2, position);
            }
        });
        spMode.setOnItemSelectedListener(new SimpleSelect() {
            @Override
            public void onSelected(int position) {
                updateStreamSetting(Prefs.KEY_MODE, Prefs.MODE_BOTH, position);
                refreshAddresses();
            }
        });

        cbBoot.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setBool(Prefs.KEY_BOOT, isChecked);
            }
        });
        cbAuth.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (suppressListener) {
                    return;
                }
                llAuth.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked && !saveAuth()) {
                    // Keep protection at its last configured state. The visible fields
                    // let the user correct the rejected value before enabling again.
                    suppressListener = true;
                    cbAuth.setChecked(Prefs.getBool(Prefs.KEY_AUTH, false));
                    suppressListener = false;
                    llAuth.setVisibility(View.VISIBLE);
                    etUser.requestFocus();
                    return;
                }
                if (Prefs.getBool(Prefs.KEY_AUTH, false) != isChecked) {
                    Prefs.setBool(Prefs.KEY_AUTH, isChecked);
                    scheduleStreamChanges();
                }
                if (!isChecked) {
                    etUser.setError(null);
                }
            }
        });

        View.OnFocusChangeListener authFocus = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (!focused) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!etUser.hasFocus() && !etPass.hasFocus()) {
                                saveAuth();
                            }
                        }
                    });
                }
            }
        };
        etUser.setOnFocusChangeListener(authFocus);
        etPass.setOnFocusChangeListener(authFocus);
        TextView.OnEditorActionListener authDone = new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveAuth();
                }
                return false;
            }
        };
        etPass.setOnEditorActionListener(authDone);
        findViewById(R.id.btnHelp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.help_title)
                        .setMessage(R.string.help_body)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });

        View.OnClickListener copyRtsp = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(tvRtsp.getText().toString());
            }
        };
        View.OnClickListener copyMjpeg = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(tvMjpeg.getText().toString());
            }
        };
        View.OnClickListener copySnapshot = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(tvSnapshot.getText().toString());
            }
        };
        rowRtsp.setOnClickListener(copyRtsp);
        rowMjpeg.setOnClickListener(copyMjpeg);
        rowSnapshot.setOnClickListener(copySnapshot);

        btnRescan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanDevices();
            }
        });
    }

    // ------------------------------------------------------------------
    // 局域网设备发现
    // ------------------------------------------------------------------

    private void scanDevices() {
        if (scanning) {
            return;
        }
        scanning = true;
        foundCount = 0;
        llDevices.removeAllViews();
        tvDevicesStatus.setText(getString(R.string.devices_scanning, LanScanner.currentSubnet()));
        btnRescan.setEnabled(false);
        btnRescan.setAlpha(0.4f);

        LanScanner.scan(new LanScanner.Callback() {
            @Override
            public void onFound(LanScanner.Device device) {
                foundCount++;
                addDeviceRow(device);
                tvDevicesStatus.setText(getString(R.string.devices_found, foundCount));
            }

            @Override
            public void onFinished(int scanned) {
                scanning = false;
                btnRescan.setEnabled(true);
                btnRescan.setAlpha(1f);
                if (foundCount == 0) {
                    tvDevicesStatus.setText(R.string.devices_none);
                }
            }
        });
    }

    private void addDeviceRow(final LanScanner.Device device) {
        View row = getLayoutInflater().inflate(R.layout.item_device, llDevices, false);
        TextView ipView = (TextView) row.findViewById(R.id.tvDeviceIp);
        TextView descView = (TextView) row.findViewById(R.id.tvDeviceDesc);
        ipView.setText(device.ip);
        descView.setText(device.detail());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openViewer(device);
            }
        });
        llDevices.addView(row);
    }

    private void openViewer(LanScanner.Device device) {
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.putExtra(ViewerActivity.EXTRA_IP, device.ip);
        intent.putExtra(ViewerActivity.EXTRA_LABEL, device.detail());
        intent.putExtra(ViewerActivity.EXTRA_RTSP_ONLY, device.rtspOnly);
        intent.putExtra(ViewerActivity.EXTRA_HTTP_PORT, CameraStreamService.HTTP_PORT);
        intent.putExtra(ViewerActivity.EXTRA_RTSP_PORT, CameraStreamService.RTSP_PORT);
        intent.putExtra(EXTRA_UI_SHOT, shotRequested);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && text != null && text.length() > 0) {
            cm.setPrimaryClip(ClipData.newPlainText("url", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean pendingStreamChanges;

    private void updateStreamSetting(String key, int defaultValue, int value) {
        if (Prefs.getInt(key, defaultValue) == value) {
            return;
        }
        Prefs.setInt(key, value);
        scheduleStreamChanges();
    }

    private void scheduleStreamChanges() {
        handler.removeCallbacks(applyStreamChanges);
        pendingStreamChanges = CameraStreamService.running;
        if (pendingStreamChanges) {
            handler.postDelayed(applyStreamChanges, 300);
        }
    }

    private void syncSelections() {
        suppressListener = true;
        refreshCameraOptions();
        spOutputLayout.setSelection(Prefs.outputLayout(), false);
        refreshResolutionOptions();
        cbOrientationLock.setChecked(Prefs.orientationLocked());
        spFps.setSelection(Prefs.getInt(Prefs.KEY_FPS, 2), false);
        spBitrate.setSelection(Prefs.getInt(Prefs.KEY_BITRATE, 2), false);
        spMode.setSelection(Prefs.mode(), false);
        suppressListener = false;
    }

    private boolean saveSettings() {
        return (Prefs.outputLayout() != 2 || saveCustomOutput()) && saveAuth();
    }

    private boolean saveAuth() {
        String user = etUser.getText().toString().trim();
        if (user.length() == 0) {
            // Validate before touching either credential or scheduling a restart.
            // An inactive, hidden editor need not block an unprotected stream.
            boolean editingProtection = cbAuth.isChecked()
                    || Prefs.getBool(Prefs.KEY_AUTH, false)
                    || llAuth.getVisibility() == View.VISIBLE;
            if (editingProtection) {
                etUser.setError(getString(R.string.auth_username_required));
            }
            return !editingProtection;
        }
        etUser.setError(null);
        String pass = etPass.getText().toString();
        if (user.equals(Prefs.getStr(Prefs.KEY_USER, "admin"))
                && pass.equals(Prefs.getStr(Prefs.KEY_PASS, "123456"))) {
            return true;
        }
        Prefs.setStr(Prefs.KEY_USER, user);
        Prefs.setStr(Prefs.KEY_PASS, pass);
        if (Prefs.getBool(Prefs.KEY_AUTH, false)) {
            scheduleStreamChanges();
        }
        return true;
    }

    // ------------------------------------------------------------------

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        List<String> perms = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (perms.isEmpty()) {
            return;
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasCameraPermission()) {
                loadCameras();
                if (saveSettings()) {
                    startServiceNow();
                }
            } else {
                Toast.makeText(this, "需要摄像头权限才能推流", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startServiceNow() {
        handler.removeCallbacks(applyStreamChanges);
        pendingStreamChanges = false;
        rejectedPreviewGeneration = CameraStreamService.streamGeneration;
        resetPreview();
        try {
            Intent intent = new Intent(this, CameraStreamService.class);
            intent.setAction(CameraStreamService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUi();
            }
        }, 1200);
    }

    private void stopServiceNow() {
        handler.removeCallbacks(applyStreamChanges);
        pendingStreamChanges = false;
        previewEpoch++;
        rejectedPreviewGeneration = CameraStreamService.streamGeneration;
        resetPreview();
        tvPreviewEmptyTitle.setText(R.string.preview_idle);
        try {
            Intent intent = new Intent(this, CameraStreamService.class);
            intent.setAction(CameraStreamService.ACTION_STOP);
            startService(intent);
        } catch (Throwable ignored) {
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUi();
            }
        }, 500);
    }

    // ------------------------------------------------------------------

    private void refreshUi() {
        boolean running = CameraStreamService.running;

        boolean switching = CameraStreamService.reconfiguring;
        tvStatus.setText(switching ? R.string.status_switching
                : (running ? R.string.status_running : R.string.status_stopped));
        tvStatus.setBackgroundResource(running
                ? R.drawable.bg_chip_running : R.drawable.bg_chip_stopped);
        tvStatus.setTextColor(getResources().getColor(running
                ? R.color.chip_running_fg : R.color.chip_stopped_fg));

        btnToggle.setText(running ? R.string.btn_stop : R.string.btn_start);
        btnToggle.setTextColor(getResources().getColor(running
                ? R.color.on_surface : R.color.on_primary));
        btnToggle.setBackgroundResource(running
                ? R.drawable.bg_btn_danger : R.drawable.bg_btn_primary);

        String error = CameraStreamService.lastError;
        if (switching) {
            tvDetail.setText(R.string.status_switching);
        } else if (error.length() > 0) {
            tvDetail.setText(error);
        } else if (running) {
            tvDetail.setText(String.format(Locale.US,
                    "%s · %d×%d · %d fps · %d 连接",
                    CameraStreamService.cameraLabel,
                    CameraStreamService.videoWidth, CameraStreamService.videoHeight,
                    CameraStreamService.measuredFps,
                    CameraStreamService.rtspClients + CameraStreamService.httpClients));
        } else {
            tvDetail.setText(R.string.detail_idle);
        }

        refreshPreview(running);
        CameraCatalog.Entry active = CameraStreamService.activeCamera;
        if (active != lastActiveCamera) {
            lastActiveCamera = active;
            mergeActiveCamera();
            normalizeLegacyCameraKey();
            refreshCameraOptions();
            refreshResolutionOptions();
        }
        updateGeometryLabel();
        refreshAddresses();
    }

    private boolean acceptPreview(PreviewFrame frame, long epoch) {
        FrameGeometry latest = CameraStreamService.frameGeometry;
        return previewActive && epoch == previewEpoch && CameraStreamService.running
                && !CameraStreamService.reconfiguring
                && frame.generation != rejectedPreviewGeneration
                && frame.generation == CameraStreamService.streamGeneration
                && frame.geometry != null && latest != null
                && frame.geometry.revision == latest.revision;
    }

    private void resetPreview() {
        hasPreviewFrame = false;
        ivPreview.clearFrame();
        displayedGeometry = null;
        previewRevision = -1;
        ivPreview.setVisibility(View.GONE);
        llPreviewEmpty.setVisibility(View.VISIBLE);
    }

    private void refreshPreview(boolean running) {
        long generation = CameraStreamService.streamGeneration;
        if (!running || CameraStreamService.reconfiguring || previewGeneration != generation) {
            resetPreview();
            previewGeneration = generation;
        }
        if (running && hasPreviewFrame) {
            ivPreview.setVisibility(View.VISIBLE);
            llPreviewEmpty.setVisibility(View.GONE);
            return;
        }
        ivPreview.setVisibility(View.GONE);
        llPreviewEmpty.setVisibility(View.VISIBLE);
        tvPreviewEmptyTitle.setText(CameraStreamService.reconfiguring ? R.string.status_switching
                : (running ? R.string.preview_waiting : R.string.preview_idle));
        tvPreviewEmptyHint.setVisibility(View.GONE);
    }

    private void showPreviewFrame(Bitmap bmp, PreviewFrame frame) {
        if (previewGeneration != frame.generation) {
            resetPreview();
            previewGeneration = frame.generation;
        }
        hasPreviewFrame = true;
        displayedGeometry = frame.geometry;
        if (previewRevision != frame.geometry.revision) {
            previewRevision = frame.geometry.revision;
            resizePreview(frame.geometry);
            updateGeometryLabel();
        }
        ivPreview.setFrame(bmp, frame.geometry);
        ivPreview.setVisibility(View.VISIBLE);
        llPreviewEmpty.setVisibility(View.GONE);
    }


    private void refreshAddresses() {
        String ip = NetUtils.localIp();
        int mode = Prefs.getInt(Prefs.KEY_MODE, Prefs.MODE_BOTH);
        rowRtsp.setEnabled(mode != Prefs.MODE_MJPEG);
        rowMjpeg.setEnabled(mode != Prefs.MODE_RTSP);
        rowSnapshot.setEnabled(mode != Prefs.MODE_RTSP);
        rowRtsp.setAlpha(mode == Prefs.MODE_MJPEG ? 0.5f : 1f);
        rowMjpeg.setAlpha(mode == Prefs.MODE_RTSP ? 0.5f : 1f);
        rowSnapshot.setAlpha(mode == Prefs.MODE_RTSP ? 0.5f : 1f);

        if (mode == Prefs.MODE_MJPEG) {
            tvRtsp.setText(getString(R.string.address_disabled));
        } else {
            tvRtsp.setText(String.format(Locale.US, "rtsp://%s:%d/live", ip,
                    CameraStreamService.RTSP_PORT));
        }

        if (mode == Prefs.MODE_RTSP) {
            tvMjpeg.setText(getString(R.string.address_disabled));
            tvSnapshot.setText(getString(R.string.address_disabled));
        } else {
            tvMjpeg.setText(String.format(Locale.US, "http://%s:%d/video", ip,
                    CameraStreamService.HTTP_PORT));
            tvSnapshot.setText(String.format(Locale.US, "http://%s:%d/snapshot.jpg", ip,
                    CameraStreamService.HTTP_PORT));
        }
    }

    /**
     * 把当前界面渲染成 PNG（调试用，见 {@link #EXTRA_UI_SHOT}）。
     * 手动 measure/layout 是为了兼容「界面还没被系统真正布局」的情况。
     */
    private void captureUiToFile() {
        try {
            int w = getResources().getDisplayMetrics().widthPixels;

            // 先把整页内容（ScrollView 里的 LinearLayout）按不限高量一遍，
            // 这样一次就能拍到「整页」，不用在设备上滚动。
            View content = findViewById(R.id.llContent);
            int totalH = 0;
            if (content != null) {
                content.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                totalH = content.getMeasuredHeight();
                content.layout(0, 0, w, totalH);
            }

            View header = findViewById(R.id.llHeader);
            int headerH = 0;
            if (header != null) {
                header.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                headerH = header.getMeasuredHeight();
                header.layout(0, 0, w, headerH);
            }

            Bitmap bmp = Bitmap.createBitmap(w, Math.max(1, headerH + totalH),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(getResources().getColor(R.color.background));
            if (header != null) {
                canvas.save();
                header.draw(canvas);
                canvas.restore();
            }
            if (content != null) {
                canvas.save();
                canvas.translate(0, headerH);
                content.draw(canvas);
                canvas.restore();
            }

            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File out = new File(dir, "ui.png");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Log.i("MainActivity", "ui shot -> " + out.getAbsolutePath()
                    + " " + bmp.getWidth() + "x" + bmp.getHeight());
        } catch (Throwable t) {
            Log.e("MainActivity", "ui shot failed", t);
        }
    }

    private abstract class SimpleSelect implements AdapterView.OnItemSelectedListener {
        abstract void onSelected(int position);

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!suppressListener) {
                onSelected(position);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
