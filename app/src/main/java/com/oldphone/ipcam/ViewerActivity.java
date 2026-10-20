package com.oldphone.ipcam;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 查看局域网内另一台设备（或本机）的实时画面。
 *
 * 两条路：
 *   - MJPEG → 无声图像；
 *   - RTSP → 交给系统 MediaPlayer，发送端开启麦克风时可带声音。
 *
 * 两条路都会自动重连：对方刚点「开始推流」时 RTSP 端口还没 listen，
 * 或者 Wi-Fi 抖动导致 socket 断掉，都不应该让用户看到永久失败页。
 */
public final class ViewerActivity extends Activity {

    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_RTSP_ONLY = "rtsp_only";
    public static final String EXTRA_HTTP_PORT = "http_port";
    public static final String EXTRA_RTSP_PORT = "rtsp_port";

    /** 首次 + 重试总共尝试这么多次（1.2s 一次，约 9 秒） */
    private static final int MAX_ATTEMPTS = 8;
    private static final int RETRY_DELAY_MS = 1200;

    private ImageView ivFrame;
    private SurfaceView svVideo;
    private TextView tvMessage;
    private TextView tvInfo;
    private TextView tvTitle;

    private MjpegClient client;
    private MediaPlayer player;

    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final JpegFrameDecoder decoder = new JpegFrameDecoder();
    private final Handler ui = new Handler();

    private String ip;
    private int httpPort;
    private int rtspPort;
    private boolean rtspOnly;
    private boolean useRtsp;

    /** 当前会话是否在前台；onPause 后不再重连 */
    private boolean resumed;
    /** 连续失败次数，成功一帧/一次 prepare 就归零 */
    private int attempt;
    /** 每次 startMjpeg 自增，用来丢弃上一轮客户端的迟到回调 */
    private volatile int generation;
    private SurfaceHolder videoHolder;

    private final Runnable mjpegRetry = new Runnable() {
        @Override
        public void run() {
            startMjpeg();
        }
    };

    private final Runnable rtspRetry = new Runnable() {
        @Override
        public void run() {
            playRtsp();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        ip = getIntent().getStringExtra(EXTRA_IP);
        rtspOnly = getIntent().getBooleanExtra(EXTRA_RTSP_ONLY, false);
        useRtsp = rtspOnly || (savedInstanceState != null && savedInstanceState.getBoolean("use_rtsp", false));
        httpPort = getIntent().getIntExtra(EXTRA_HTTP_PORT, CameraStreamService.HTTP_PORT);
        rtspPort = getIntent().getIntExtra(EXTRA_RTSP_PORT, CameraStreamService.RTSP_PORT);

        ivFrame = (ImageView) findViewById(R.id.ivFrame);
        svVideo = (SurfaceView) findViewById(R.id.svVideo);
        tvMessage = (TextView) findViewById(R.id.tvMessage);
        tvInfo = (TextView) findViewById(R.id.tvInfo);
        tvTitle = (TextView) findViewById(R.id.tvTitle);
        addPlaybackSelector();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        String label = getIntent().getStringExtra(EXTRA_LABEL);
        tvTitle.setText(ip + (label == null || label.length() == 0 ? "" : " · " + label));

        findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Surface 回调只注册一次；重连时复用同一个 holder
        svVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                videoHolder = holder;
                if (resumed && useRtsp) {
                    playRtsp();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                videoHolder = null;
                releasePlayer();
            }
        });

        decodeThread = new HandlerThread("viewer-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        showMessage(getString(R.string.viewer_connecting));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        attempt = 0;
        if (useRtsp) {
            startRtsp();
        } else {
            startMjpeg();
        }
        if (getIntent().getBooleanExtra(MainActivity.EXTRA_UI_SHOT, false)) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    captureUi();
                }
            }, 4000);
        }
    }

    private void addPlaybackSelector() {
        RadioGroup selector = new RadioGroup(this);
        selector.setOrientation(RadioGroup.HORIZONTAL);
        selector.setPadding(dp(8), 0, dp(8), 0);
        selector.setBackgroundColor(0xff16181d);
        RadioButton silent = new RadioButton(this);
        silent.setId(View.generateViewId());
        silent.setText("MJPEG · 无声");
        silent.setTextSize(13);
        silent.setTextColor(0xfff1f3f4);
        silent.setEnabled(!rtspOnly);
        silent.setAlpha(rtspOnly ? 0.4f : 1f);
        final RadioButton av = new RadioButton(this);
        av.setId(View.generateViewId());
        av.setText("RTSP · 可带声音");
        av.setTextSize(13);
        av.setTextColor(0xfff1f3f4);
        selector.addView(silent, new RadioGroup.LayoutParams(0, dp(48), 1));
        selector.addView(av, new RadioGroup.LayoutParams(0, dp(48), 1));
        selector.check(useRtsp ? av.getId() : silent.getId());
        LinearLayout root = (LinearLayout) tvInfo.getParent();
        root.addView(selector, 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvInfo.setSingleLine(false);
        selector.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean nextRtsp = checkedId == av.getId();
                if (nextRtsp == useRtsp) return;
                stopAll();
                useRtsp = nextRtsp;
                attempt = 0;
                showMessage(getString(R.string.viewer_connecting));
                if (useRtsp) startRtsp(); else startMjpeg();
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("use_rtsp", useRtsp);
        super.onSaveInstanceState(state);
    }

    /** 调试用：把查看页渲染成 PNG（锁屏状态下也能验证画面） */
    private void captureUi() {
        try {
            View root = getWindow().getDecorView();
            int w = root.getWidth() > 0 ? root.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            int h = root.getHeight() > 0 ? root.getHeight()
                    : getResources().getDisplayMetrics().heightPixels;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bmp));

            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File out = new File(dir, "ui_viewer.png");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Log.i("ViewerActivity", "shot -> " + out.getAbsolutePath() + " " + w + "x" + h);
        } catch (Throwable t) {
            Log.e("ViewerActivity", "shot failed", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopAll();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ MJPEG

    private String httpUrl() {
        return String.format(Locale.US, "http://%s:%d/video", ip, httpPort);
    }

    private void startMjpeg() {
        if (!resumed || useRtsp) {
            return;
        }
        final String url = httpUrl();
        tvInfo.setText("MJPEG 为无声画面，听声音请切换 RTSP\n" + url);

        svVideo.setVisibility(View.GONE);
        ivFrame.setVisibility(View.VISIBLE);

        stopClient();
        final int myGen = ++generation;

        client = new MjpegClient(ip, httpPort, "/video");
        client.start(new MjpegClient.Listener() {
            @Override
            public void onFrame(final byte[] jpeg) {
                if (myGen != generation) {
                    return;
                }
                final Handler h = decodeHandler;
                if (h == null) {
                    return;
                }
                if (!decoding.compareAndSet(false, true)) {
                    return;   // 解码跟不上就丢帧，避免延迟越滚越大
                }
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap bmp = null;
                        try {
                            bmp = decoder.decode(jpeg);
                        } finally {
                            decoding.set(false);
                        }
                        if (bmp != null) {
                            final Bitmap out = bmp;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (myGen != generation || !resumed) {
                                        return;
                                    }
                                    attempt = 0;
                                    ivFrame.setImageBitmap(out);
                                    tvMessage.setVisibility(View.GONE);
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void onError(final String message) {
                Log.w("ViewerActivity", "mjpeg error: " + message);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGen != generation) {
                            return;
                        }
                        retryMjpeg(url);
                    }
                });
            }
        });
    }

    private void retryMjpeg(String url) {
        stopClient();
        scheduleRetry(mjpegRetry, url);
    }

    // ------------------------------------------------------------------ RTSP

    private String rtspUrl() {
        return String.format(Locale.US, "rtsp://%s:%d/live", ip, rtspPort);
    }

    private void startRtsp() {
        if (!resumed || !useRtsp) return;
        final String url = rtspUrl();
        tvInfo.setText("声音需发送端开启麦克风；音量由系统媒体音量控制\n" + url);

        svVideo.setVisibility(View.VISIBLE);
        ivFrame.setVisibility(View.GONE);

        if (videoHolder != null) {
            playRtsp();
        }
        // holder 还没就绪时由 surfaceCreated 回调接手
    }

    private void playRtsp() {
        if (!resumed || !useRtsp) {
            return;
        }
        final SurfaceHolder holder = videoHolder;
        if (holder == null) {
            return;
        }
        releasePlayer();

        final String url = rtspUrl();
        try {
            final MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(url);
            mp.setDisplay(holder);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (m != player || !resumed || !useRtsp) {
                        return;
                    }
                    attempt = 0;
                    Log.i("ViewerActivity", "rtsp prepared "
                            + m.getVideoWidth() + "x" + m.getVideoHeight());
                    tvMessage.setVisibility(View.GONE);
                    m.start();
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // SurfaceView 画在独立图层上，View.draw() 抓不到，
                            // 只能用播放位置和尺寸来确认"真的在播"
                            MediaPlayer m = player;
                            if (m == null) {
                                return;
                            }
                            Log.i("ViewerActivity", "rtsp playing "
                                    + m.getVideoWidth() + "x" + m.getVideoHeight()
                                    + " pos=" + m.getCurrentPosition() + "ms");
                        }
                    }, 3000);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w("ViewerActivity", "rtsp error " + what + "," + extra);
                    if (m == player) {
                        retryRtsp(url);
                    }
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mediaPlayer) {
                    if (mediaPlayer == player && resumed && useRtsp) retryRtsp(url);
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w("ViewerActivity", "rtsp prepare: " + t);
            retryRtsp(url);
        }
    }

    private void retryRtsp(String url) {
        releasePlayer();
        scheduleRetry(rtspRetry, url);
    }

    // ------------------------------------------------------------------

    private void scheduleRetry(Runnable action, String url) {
        ui.removeCallbacks(mjpegRetry);
        ui.removeCallbacks(rtspRetry);
        if (!resumed || isFinishing()) {
            return;
        }
        attempt++;
        if (attempt >= MAX_ATTEMPTS) {
            showMessage(getString(R.string.viewer_failed, url));
            return;
        }
        showMessage(getString(R.string.viewer_reconnecting, attempt, MAX_ATTEMPTS - 1));
        ui.postDelayed(action, RETRY_DELAY_MS);
    }

    private void showMessage(String msg) {
        if (isFinishing()) {
            return;
        }
        tvMessage.setText(msg);
        tvMessage.setVisibility(View.VISIBLE);
    }

    private void stopClient() {
        // Invalidate queued decode/UI callbacks before a disconnect can leave its
        // final frame over the reconnect message or a later camera session.
        generation++;
        ivFrame.setImageDrawable(null);
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopAll() {
        ui.removeCallbacks(mjpegRetry);
        ui.removeCallbacks(rtspRetry);
        stopClient();
        releasePlayer();
    }
}
