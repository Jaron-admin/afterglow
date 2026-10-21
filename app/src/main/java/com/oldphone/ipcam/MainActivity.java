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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
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
    private TextView tvHttp;
    private TextView btnAddress;
    private AlertDialog haSetupDialog;
    private List<NetUtils.Address> currentAddresses = new ArrayList<NetUtils.Address>();
    private NetUtils.Address displayedAddress;
    private String selectedAddressKey;
    private Button btnToggle;
    private TextView tvRecordingStatus;
    private TextView tvLastEvent;
    private boolean startAfterPermission;

    private View rowRtsp;
    private View rowMjpeg;
    private View rowSnapshot;
    private View rowHttp;

    private CropPreviewView ivPreview;
    private View flPreview;
    private TextView tvGeometry;
    private View llPreviewEmpty;
    private TextView tvPreviewEmptyTitle;
    private TextView tvPreviewEmptyHint;
    private LinearLayout llBody;
    private LinearLayout llContent;
    private LinearLayout previewPanel;
    private View settingsPanel;
    private ScrollView svMain;
    private ScrollView svSettings;

    private Spinner spResolution;
    private Spinner spAspectRatio;
    private Spinner spFps;
    private Spinner spBitrate;
    private Spinner spMode;
    private Spinner spCamera;
    private Spinner spOutputLayout;
    private CheckBox cbOrientationLock;
    private View llResolution;
    private View llAspectRatio;
    private View llCustomSize;
    private EditText etOutputWidth;
    private EditText etOutputHeight;
    private final List<CameraCatalog.Entry> cameraEntries = new ArrayList<CameraCatalog.Entry>();
    private final List<String> cameraKeys = new ArrayList<String>();
    private final List<int[]> outputSizes = new ArrayList<int[]>();
    private final List<OutputSizeOptions.Ratio> outputRatios = new ArrayList<OutputSizeOptions.Ratio>();
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
    /** 预览 JPEG 编解码均在独立线程执行。 */
    private HandlerThread decodeThread;
    private volatile Handler decodeHandler;
    /** 上一帧还没解完就丢新帧，避免慢机器上越积越多 */
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final LocalPreviewDecoder previewDecoder = new LocalPreviewDecoder();
    /** Capture callback owns these only while the single-frame gate is held. */
    private FrameGeometry previewTransform;
    private byte[] previewBuffer;
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
                public void onPreviewFrame(byte[] nv21, final FrameGeometry geometry, final long generation) {
                    final Handler h = decodeHandler;
                    final long epoch = previewEpoch;
                    if (h == null || !acceptPreview(geometry, generation, epoch)
                            || !decoding.compareAndSet(false, true)) {
                        return;
                    }
                    boolean queued = false;
                    try {
                        if (!acceptPreview(geometry, generation, epoch)) {
                            return;
                        }
                        if (previewTransform == null || previewTransform.revision != geometry.revision
                                || previewTransform.rawWidth != geometry.rawWidth
                                || previewTransform.rawHeight != geometry.rawHeight
                                || previewTransform.rotation != geometry.rotation) {
                            previewTransform = FrameGeometry.preview(geometry.rawWidth, geometry.rawHeight,
                                    geometry.rotation, 960, geometry.revision);
                        }
                        int bytes = previewTransform.outputWidth * previewTransform.outputHeight * 3 / 2;
                        if (previewBuffer == null || previewBuffer.length != bytes) previewBuffer = new byte[bytes];
                        // Camera owns nv21. Finish scaling/copying before this callback returns.
                        Nv21Transform.render(nv21, previewTransform, previewBuffer);
                        final PreviewFrame frame = new PreviewFrame(previewBuffer,
                                previewTransform.outputWidth, previewTransform.outputHeight, geometry, generation);
                        queued = h.post(new Runnable() {
                            @Override public void run() { decodePreview(frame, epoch); }
                        });
                    } catch (Throwable failure) {
                        Log.w("MainActivity", "preview transform or handoff failed", failure);
                    } finally {
                        if (!queued) decoding.set(false);
                    }
                }
            };

    private void decodePreview(final PreviewFrame frame, final long epoch) {
        boolean handedToUi = false;
        try {
            if (!acceptPreview(frame.geometry, frame.generation, epoch)) return;
            final Bitmap bitmap = previewDecoder.decode(frame);
            if (bitmap == null) return;
            // Keep both NV21 and bitmap ownership until the UI accepts or discards the frame.
            handedToUi = handler.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (acceptPreview(frame.geometry, frame.generation, epoch) && !isFinishing()) {
                            showPreviewFrame(bitmap, frame);
                            previewDecoder.presented(bitmap);
                        }
                    } finally {
                        decoding.set(false);
                    }
                }
            });
        } catch (Throwable failure) {
            Log.w("MainActivity", "preview decode or UI handoff failed", failure);
        } finally {
            if (!handedToUi) decoding.set(false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        applyRequestedOrientation();
        setContentView(R.layout.activity_main);
        bindViews();
        setupSpinners();
        setupListeners();
        arrangePanes();
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
        if (!CameraStreamService.running) beginStart();
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
        // 只有界面在前台时才接收预览帧，退到后台立刻停止，省 CPU。
        CameraStreamService.setPreviewListener(previewListener);

        // 首次进入自动扫一次，之后交给「重新扫描」按钮，避免每次切回都扫全网段
        if (!autoScanned && Prefs.mode() != Prefs.MODE_LOCAL) {
            autoScanned = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (Prefs.mode() != Prefs.MODE_LOCAL) scanDevices();
                }
            }, 500);
        } else if (!autoScanned && Prefs.mode() == Prefs.MODE_LOCAL && !scanning) {
            tvDevicesStatus.setText("仅本地模式，可手动扫描局域网");
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
        if (haSetupDialog != null) haSetupDialog.dismiss();
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
        rowHttp = findViewById(R.id.rowHttp);
        tvHttp = (TextView) findViewById(R.id.tvHttp);
        btnAddress = (TextView) findViewById(R.id.btnAddress);

        ivPreview = (CropPreviewView) findViewById(R.id.ivPreview);
        flPreview = findViewById(R.id.flPreview);
        tvGeometry = (TextView) findViewById(R.id.tvGeometry);
        llPreviewEmpty = findViewById(R.id.llPreviewEmpty);
        tvPreviewEmptyTitle = (TextView) findViewById(R.id.tvPreviewEmptyTitle);
        tvPreviewEmptyHint = (TextView) findViewById(R.id.tvPreviewEmptyHint);
        llBody = (LinearLayout) findViewById(R.id.llBody);
        llContent = (LinearLayout) findViewById(R.id.llContent);
        previewPanel = (LinearLayout) findViewById(R.id.previewPanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        svMain = (ScrollView) findViewById(R.id.svMain);
        svSettings = (ScrollView) findViewById(R.id.svSettings);
        svMain.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    resizePreview(displayedGeometry);
                }
            }
        });

        spResolution = (Spinner) findViewById(R.id.spResolution);
        spAspectRatio = (Spinner) findViewById(R.id.spAspectRatio);
        spFps = (Spinner) findViewById(R.id.spFps);
        spBitrate = (Spinner) findViewById(R.id.spBitrate);
        spMode = (Spinner) findViewById(R.id.spMode);
        spCamera = (Spinner) findViewById(R.id.spCamera);
        spOutputLayout = (Spinner) findViewById(R.id.spOutputLayout);
        cbOrientationLock = (CheckBox) findViewById(R.id.cbOrientationLock);
        llResolution = findViewById(R.id.llResolution);
        llAspectRatio = findViewById(R.id.llAspectRatio);
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
        addRecordingControls();
    }

    private void addRecordingControls() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.topMargin = dp(12);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button settings = new Button(this);
        settings.setText("录像与检测");
        settings.setTextSize(13);
        settings.setAllCaps(false);
        settings.setTextColor(getResources().getColor(R.color.primary));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, RecordingSettingsActivity.class));
            }
        });
        Button recordings = new Button(this);
        recordings.setText("录像列表");
        recordings.setTextSize(13);
        recordings.setAllCaps(false);
        recordings.setTextColor(getResources().getColor(R.color.primary));
        recordings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, RecordingsActivity.class));
            }
        });
        actions.addView(settings, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(recordings, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(actions);
        tvRecordingStatus = new TextView(this);
        tvRecordingStatus.setTextSize(12);
        tvRecordingStatus.setTextColor(getResources().getColor(R.color.on_surface_variant));
        tvRecordingStatus.setPadding(0, dp(4), 0, 0);
        panel.addView(tvRecordingStatus);
        tvLastEvent = new TextView(this);
        tvLastEvent.setTextSize(12);
        tvLastEvent.setTextColor(getResources().getColor(R.color.on_surface_variant));
        tvLastEvent.setPadding(0, dp(4), 0, 0);
        panel.addView(tvLastEvent);
        LinearLayout parent = (LinearLayout) settingsPanel;
        parent.addView(panel, parent.indexOfChild(tvDetail) + 1, panelParams);
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
        CameraCatalog.Entry camera = selectedCamera();
        int[][] cameraSizes = camera == null ? null : camera.sizes;
        int width = Prefs.outputWidth();
        int height = Prefs.outputHeight();
        int layout = Prefs.outputLayout();
        OutputSizeOptions.Ratio ratio = OutputSizeOptions.ratioOf(width, height);
        outputRatios.clear();
        outputRatios.addAll(OutputSizeOptions.ratios(cameraSizes, width, height));
        List<String> ratioLabels = new ArrayList<String>();
        int selectedRatio = 0;
        for (int i = 0; i < outputRatios.size(); i++) {
            OutputSizeOptions.Ratio option = outputRatios.get(i);
            ratioLabels.add(option.label(layout));
            if (option.equals(ratio)) selectedRatio = i;
        }
        bindSpinner(spAspectRatio, ratioLabels.toArray(new String[0]), selectedRatio);
        outputSizes.clear();
        outputSizes.addAll(OutputSizeOptions.sizes(ratio, layout, cameraSizes, width, height));
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

    private void showOutputEditors() {
        boolean custom = Prefs.outputLayout() == 2;
        llAspectRatio.setVisibility(custom ? View.GONE : View.VISIBLE);
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
            refreshResolutionOptions();
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
        ActivityOrientation.apply(this);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        arrangePanes();
    }

    private void arrangePanes() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        ViewGroup target = landscape ? svSettings : llContent;
        if (settingsPanel.getParent() != target) {
            ((ViewGroup) settingsPanel.getParent()).removeView(settingsPanel);
            target.addView(settingsPanel);
        }
        llBody.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        svMain.setLayoutParams(new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, landscape ? 1.1f : 1f));
        svSettings.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        svSettings.setVisibility(landscape ? View.VISIBLE : View.GONE);
        flPreview.post(new Runnable() {
            @Override public void run() { resizePreview(displayedGeometry); }
        });
    }

    private void resizePreview(FrameGeometry geometry) {
        int fullWidth = geometry == null ? 16 : geometry.fullWidth;
        int fullHeight = geometry == null ? 9 : geometry.fullHeight;
        if (fullWidth <= 0 || fullHeight <= 0) return;
        int availableWidth = previewPanel.getWidth() - previewPanel.getPaddingLeft() - previewPanel.getPaddingRight();
        if (availableWidth <= 0) return; // Wait for the pane's actual measurement after a rotation.
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int controlsHeight = Math.max(0, previewPanel.getHeight() - flPreview.getHeight());
        int limit = landscape
                ? Math.max(dp(72), svMain.getHeight() - controlsHeight
                    - llContent.getPaddingTop() - llContent.getPaddingBottom())
                : Math.max(dp(120), (int) (screenHeight * 0.42f));
        int width = Math.max(1, Math.min(availableWidth, (int) (limit * (float) fullWidth / fullHeight)));
        int height = Math.max(1, Math.round(width * (float) fullHeight / fullWidth));
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
                    beginStart();
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
        spAspectRatio.setOnItemSelectedListener(new SimpleSelect() {
            @Override public void onSelected(int position) {
                if (Prefs.outputLayout() == 2 || position < 0 || position >= outputRatios.size()) return;
                OutputSizeOptions.Ratio ratio = outputRatios.get(position);
                if (ratio.equals(OutputSizeOptions.ratioOf(Prefs.outputWidth(), Prefs.outputHeight()))) return;
                CameraCatalog.Entry camera = selectedCamera();
                List<int[]> sizes = OutputSizeOptions.sizes(ratio, Prefs.outputLayout(),
                        camera == null ? null : camera.sizes, Prefs.outputWidth(), Prefs.outputHeight());
                int[] size = OutputSizeOptions.chooseSize(sizes, Prefs.outputWidth(), Prefs.outputHeight());
                Prefs.setOutputSize(size[0], size[1]);
                etOutputWidth.setText(String.valueOf(size[0]));
                etOutputHeight.setText(String.valueOf(size[1]));
                etOutputWidth.setError(null);
                etOutputHeight.setError(null);
                refreshResolutionOptions();
                updateGeometryLabel();
                scheduleStreamChanges();
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
                if (position == Prefs.MODE_LOCAL && Prefs.recordingMode() == 0
                        && CameraStreamService.running) {
                    suppressListener = true;
                    spMode.setSelection(Prefs.mode(), false);
                    suppressListener = false;
                    Toast.makeText(MainActivity.this, "请先在“录像与检测”启用录像", Toast.LENGTH_LONG).show();
                    return;
                }
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
                        .setNeutralButton(R.string.ha_setup_title, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { showHaSetup(); }
                        })
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        btnAddress.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAddressPicker(); }
        });
        findViewById(R.id.btnHaSetup).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHaSetup(); }
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
        rowHttp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(tvHttp.getText().toString()); }
        });

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

    private void openViewer(final LanScanner.Device device) {
        if (device.httpAuthRequired && !device.rtspOnly) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.viewer_http_auth_title)
                    .setMessage(R.string.viewer_http_auth_hint)
                    .setPositiveButton(R.string.copy_http_address, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            copy(String.format(Locale.US, "http://%s:%d/", device.ip, device.httpPort));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.putExtra(ViewerActivity.EXTRA_IP, device.ip);
        intent.putExtra(ViewerActivity.EXTRA_LABEL, device.detail());
        intent.putExtra(ViewerActivity.EXTRA_RTSP_ONLY, device.rtspOnly);
        intent.putExtra(ViewerActivity.EXTRA_HTTP_PORT, device.httpPort);
        intent.putExtra(ViewerActivity.EXTRA_RTSP_PORT, device.rtspPort);
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

    private boolean hasCapturePermissions() {
        return hasCameraPermission() && (!Prefs.microphoneEnabled() || Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
    }

    private void beginStart() {
        if (!saveSettings() || !validateLocalRecording()) return;
        if (!hasCapturePermissions()) {
            startAfterPermission = true;
            requestPermissions();
            return;
        }
        startServiceNow();
    }

    private boolean validateLocalRecording() {
        if (Prefs.mode() != Prefs.MODE_LOCAL || Prefs.recordingMode() != 0) return true;
        Toast.makeText(this, "仅本地模式需要先启用连续或事件录像", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, RecordingSettingsActivity.class));
        return false;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        List<String> perms = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA);
        }
        if (Prefs.microphoneEnabled()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
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
            boolean requestedStart = startAfterPermission;
            startAfterPermission = false;
            if (hasCapturePermissions()) {
                loadCameras();
                if (requestedStart && saveSettings() && validateLocalRecording()) {
                    startServiceNow();
                }
            } else {
                Toast.makeText(this, Prefs.microphoneEnabled()
                        ? "未启动：需要摄像头和麦克风权限；也可在设置中关闭麦克风"
                        : "未启动：需要摄像头权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startServiceNow() {
        if (!hasCapturePermissions() || !validateLocalRecording()) return;
        startAfterPermission = false;
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
        startAfterPermission = false;
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
            String detail = String.format(Locale.US,
                    "%s · %d×%d · 处理 %d fps",
                    CameraStreamService.cameraLabel,
                    CameraStreamService.videoWidth, CameraStreamService.videoHeight,
                    CameraStreamService.measuredFps);
            if (Prefs.mode() != Prefs.MODE_LOCAL) detail += " · "
                    + (CameraStreamService.rtspClients + CameraStreamService.httpClients) + " 连接";
            tvDetail.setText(detail);
        } else {
            tvDetail.setText(Prefs.mode() == Prefs.MODE_LOCAL ? "仅在本机保存录像" : getString(R.string.detail_idle));
        }

        String recording = CameraStreamService.recordingStatus;
        if (!running && !CameraStreamService.recordingFailed) {
            recording = Prefs.recordingMode() == 0 ? "录像已关闭"
                    : (Prefs.recordingMode() == 1 ? "连续录像 · 待启动" : "事件录像 · 待启动");
        }
        tvRecordingStatus.setText(recording);
        tvRecordingStatus.setTextColor(getResources().getColor(CameraStreamService.recordingFailed
                ? R.color.danger : R.color.on_surface_variant));
        String event = CameraStreamService.lastEvent;
        tvLastEvent.setVisibility(event == null || event.length() == 0 ? View.GONE : View.VISIBLE);
        tvLastEvent.setText(event == null ? "" : "最近事件：" + event);
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

    private boolean acceptPreview(FrameGeometry geometry, long generation, long epoch) {
        FrameGeometry latest = CameraStreamService.frameGeometry;
        return previewActive && epoch == previewEpoch && CameraStreamService.running
                && !CameraStreamService.reconfiguring
                && generation != rejectedPreviewGeneration
                && generation == CameraStreamService.streamGeneration
                && geometry != null && latest != null
                && geometry.revision == latest.revision;
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
        boolean rtsp = Prefs.needRtsp(), mjpeg = Prefs.needMjpeg();
        boolean network = rtsp || mjpeg;
        currentAddresses = network ? NetUtils.localAddresses() : new ArrayList<NetUtils.Address>();
        displayedAddress = null;
        for (NetUtils.Address address : currentAddresses) {
            if (addressKey(address).equals(selectedAddressKey)) {
                displayedAddress = address;
                break;
            }
        }
        if (displayedAddress == null && !currentAddresses.isEmpty()) {
            // An unavailable explicit choice falls back to the preferred live interface.
            // Automatic selection remains automatic when a LAN interface later appears.
            selectedAddressKey = null;
            displayedAddress = currentAddresses.get(0);
        }
        boolean available = network && displayedAddress != null;
        String ip = available ? displayedAddress.ip : "";
        String unavailable = getString(network ? R.string.address_unavailable : R.string.address_disabled);
        btnAddress.setEnabled(available);
        btnAddress.setAlpha(available ? 1f : 0.5f);
        btnAddress.setText(available ? getString(R.string.address_choice,
                addressLabel(displayedAddress), currentAddresses.size()) : unavailable);
        rowHttp.setEnabled(available);
        rowHttp.setAlpha(available ? 1f : 0.5f);
        tvHttp.setText(available ? String.format(Locale.US, "http://%s:%d/", ip,
                CameraStreamService.HTTP_PORT) : unavailable);
        rowRtsp.setEnabled(rtsp && available);
        rowMjpeg.setEnabled(mjpeg && available);
        rowSnapshot.setEnabled(mjpeg && available);
        rowRtsp.setAlpha(rtsp && available ? 1f : 0.5f);
        rowMjpeg.setAlpha(mjpeg && available ? 1f : 0.5f);
        rowSnapshot.setAlpha(mjpeg && available ? 1f : 0.5f);

        if (!rtsp || !available) {
            tvRtsp.setText(rtsp ? unavailable : getString(R.string.address_disabled));
        } else {
            tvRtsp.setText(String.format(Locale.US, "rtsp://%s:%d/live", ip,
                    CameraStreamService.RTSP_PORT));
        }

        if (!mjpeg || !available) {
            tvMjpeg.setText(mjpeg ? unavailable : getString(R.string.address_disabled));
            tvSnapshot.setText(mjpeg ? unavailable : getString(R.string.address_disabled));
        } else {
            tvMjpeg.setText(String.format(Locale.US, "http://%s:%d/video", ip,
                    CameraStreamService.HTTP_PORT));
            tvSnapshot.setText(String.format(Locale.US, "http://%s:%d/snapshot.jpg", ip,
                    CameraStreamService.HTTP_PORT));
        }
        updateHaSetupDialog();
    }

    private static String addressKey(NetUtils.Address address) {
        return address.interfaceName + "/" + address.ip;
    }

    private String addressLabel(NetUtils.Address address) {
        int kind;
        switch (address.kind) {
            case NetUtils.WIFI: kind = R.string.network_wifi; break;
            case NetUtils.ETHERNET: kind = R.string.network_ethernet; break;
            case NetUtils.HOTSPOT: kind = R.string.network_hotspot; break;
            case NetUtils.USB: kind = R.string.network_usb; break;
            case NetUtils.VPN: kind = R.string.network_vpn; break;
            case NetUtils.MOBILE: kind = R.string.network_mobile; break;
            default: kind = R.string.network_other;
        }
        return getString(R.string.address_label, getString(kind), address.interfaceName, address.ip);
    }

    private void showAddressPicker() {
        refreshAddresses();
        if (currentAddresses.isEmpty()) return;
        final List<NetUtils.Address> choices = new ArrayList<NetUtils.Address>(currentAddresses);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = addressLabel(choices.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.address_select_title)
                .setSingleChoiceItems(labels, choices.indexOf(displayedAddress),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                selectedAddressKey = addressKey(choices.get(which));
                                refreshAddresses();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showHaSetup() {
        refreshAddresses();
        final AlertDialog guide = new AlertDialog.Builder(this)
                .setTitle(R.string.ha_setup_title)
                .setMessage(haSetupMessage())
                .setPositiveButton(R.string.ha_copy_rtsp, null)
                .setNegativeButton(R.string.ha_copy_snapshot, null)
                .setNeutralButton(R.string.ha_advanced_title, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showHaAdvanced(); }
                }).create();
        haSetupDialog = guide;
        guide.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                if (haSetupDialog == guide) haSetupDialog = null;
            }
        });
        guide.show();
        // Keep the instructions open while either address is copied.
        guide.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshAddresses();
                if (rowRtsp.isEnabled()) copy(tvRtsp.getText().toString());
            }
        });
        guide.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshAddresses();
                if (rowSnapshot.isEnabled()) copy(tvSnapshot.getText().toString());
            }
        });
        updateHaSetupDialog();
        TextView message = (TextView) guide.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }

    private String haSetupMessage() {
        String unavailable = getString(R.string.ha_address_not_ready);
        return getString(R.string.ha_setup_steps,
                rowSnapshot.isEnabled() ? tvSnapshot.getText().toString() : unavailable,
                rowRtsp.isEnabled() ? tvRtsp.getText().toString() : unavailable);
    }

    private void updateHaSetupDialog() {
        if (haSetupDialog == null || !haSetupDialog.isShowing()) return;
        haSetupDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(rowRtsp.isEnabled());
        haSetupDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(rowSnapshot.isEnabled());
        TextView message = (TextView) haSetupDialog.findViewById(android.R.id.message);
        String updated = haSetupMessage();
        // Avoid resetting selection/scroll position on every normal status refresh.
        if (message != null && !updated.contentEquals(message.getText())) {
            message.setText(updated);
        }
    }

    private void showHaAdvanced() {
        refreshAddresses();
        final String http = rowHttp.isEnabled() ? tvHttp.getText().toString() : null;
        AlertDialog.Builder guide = new AlertDialog.Builder(this)
                .setTitle(R.string.ha_advanced_title)
                .setMessage(getString(R.string.ha_advanced_steps,
                        http == null ? getString(R.string.ha_address_not_ready) : http))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.ha_online_guide, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(getString(R.string.ha_guide_url))));
                        } catch (android.content.ActivityNotFoundException unavailable) {
                            Toast.makeText(MainActivity.this, R.string.browser_unavailable,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        if (http != null) {
            guide.setNeutralButton(R.string.copy_http_address, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    refreshAddresses();
                    if (rowHttp.isEnabled()) copy(tvHttp.getText().toString());
                }
            });
        }
        guide.show();
    }

    /** Render the current viewport, including both panes, without relaying out live views. */
    private void captureUiToFile() {
        try {
            View root = findViewById(android.R.id.content);
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
            Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            root.draw(canvas);

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
            if (!suppressListener && position == parent.getSelectedItemPosition()) {
                onSelected(position);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
