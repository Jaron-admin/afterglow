package com.oldphone.ipcam;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** v2.2 screens. Layout numbers follow the design handoff. */
final class V22Ui {
    private static final SimpleDateFormat CLOCK =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    final MainActivity activity;
    private View home;
    private View stream;
    private View advanced;
    private LinearLayout advancedHost;
    private boolean advancedShowing;
    private boolean paintedFront;
    private int paintedRotation = -1;
    private ScrollView streamScroll;
    private ScrollView homeScroll;
    private FrameLayout homeOverlay;
    private final int[] menuFrame = new int[2];
    private final int[] menuOverlay = new int[2];
    private FrameLayout previewFrame;
    private FrameLayout miniFrame;
    /** Aspect of the full oriented frame; the preview card follows it instead of a fixed 16:9. */
    private int frameWidth = 16;
    private int frameHeight = 9;
    private Split homeSplit;
    private Split streamSplit;
    private int streamPushTop;
    private boolean resMenu;
    private boolean wantStream;
    private PreviewPane previewImage;
    private PreviewPane miniImage;
    private View previewScene;
    private View miniScene;
    private TextView deviceLine;
    private TextView previewClock;
    private LinearLayout resChipBox;
    private ImageView resMic;
    private TextView resChip;
    private ImageView resChevron;
    private View resMenuView;
    private LinearLayout homeRear;
    private LinearLayout homeFront;
    private View streamFace;
    private Motion.Mark streamMark;
    private String pageShot;
    private LinearLayout streamLiveChip;
    private ImageView streamLiveIcon;
    private Motion.BreathDot streamLiveDot;
    private TextView streamLiveLabel;
    private LinearLayout addressBox;
    private TextView rtspValue;
    private TextView mjpegValue;
    private TextView lightThValue;
    private TextView lightMethodValue;
    private TextView lightNowValue;
    private TextView lightMet;
    private Motion.StatusPill leftPill;
    private Motion.StatusPill rightPill;
    private Motion.Plate glow;
    private Motion.Halo streamHalo;
    private TextView pauseNote;
    private TextView streamTime;
    private TextView streamTimeCaption;
    private TextView todayStat;
    private TextView freeStat;
    private TextView soundCaption;
    private PillSwitch soundSwitch;
    private TextView recordCard;
    private TextView libraryCard;
    private TextView streamCard;
    private TextView streamButton;
    private Motion.StatusPill miniPill;
    private TextView miniMeta;
    private TextView streamResValue;
    private TextView lightNote;
    private PillSwitch streamSwitch;
    private PillSwitch lightStreamSwitch;
    private View lightSummaryBox;
    private TextView haHost;
    private View haBody;
    private PillSwitch haSwitch;
    private PillSwitch haAudioSwitch;
    private TextView haAudioCaption;
    private PillSwitch haNotifySwitch;
    private TextView entityId;
    private boolean suppress;
    V22Ui(MainActivity activity) { this.activity = activity; }

    void attach(ViewGroup content) {
        pageShot = activity.getIntent() == null ? null
                : activity.getIntent().getStringExtra(MainActivity.EXTRA_PAGE_SHOT);
        String shot = pageShot;
        resMenu = "home-res".equals(shot);
        wantStream = "stream".equals(shot) || "stream-push".equals(shot)
                || (activity.getIntent() != null && activity.getIntent().getBooleanExtra(MainActivity.EXTRA_STREAM_PAGE, false));
        home = buildHome();
        stream = buildStream();
        advanced = buildAdvanced();
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.addView(home, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(stream, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(advanced, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        advanced.setVisibility(View.GONE);
        showStream(wantStream);
        applyOrientation();
    }

    /**
     * Portrait stacks the picture over its settings, the way the handoff does. Landscape
     * puts the picture on the left and the settings on the right, which is what the pre-2.2
     * layout did on wide screens and what the camera wants: you watch the frame while you
     * change a setting.
     */
    void applyOrientation() {
        arrange(homeSplit);
        arrange(streamSplit);
    }

    private void arrange(Split split) {
        if (split == null) return;
        boolean wide = landscape();
        int viewport = split.host == null ? 0 : split.host.getHeight();
        if (split.wide == wide && split.page.getChildCount() > 0) {
            split.fit(viewport);
            split.picture.requestLayout();
            return;
        }
        split.wide = wide;
        split.page.removeAllViews();
        split.wideRow.removeAllViews();
        split.picturePane.removeAllViews();
        detach(split.rest);
        detach(split.side);
        if (wide) {
            if (split.lead != null) split.picturePane.addView(split.lead, fillWidth());
            split.picturePane.addView(split.picture, fillWidth());
            split.side.addView(split.rest,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            split.wideRow.addView(split.picturePane, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
            split.wideRow.addView(split.side, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            split.page.addView(split.wideRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            if (split.lead != null) split.page.addView(split.lead, fillWidth());
            split.page.addView(split.picture, fillWidth());
            split.page.addView(split.rest, fillWidth());
        }
        split.fit(viewport);
        split.picture.requestLayout();
    }

    private LinearLayout.LayoutParams fillWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void detach(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    /**
     * Landscape has to fill the viewport exactly: the outer scroll must not move, or the
     * picture would slide away while the settings column is being read.
     */
    private void setPageHeight(LinearLayout page, int height) {
        ViewGroup.LayoutParams lp = page.getLayoutParams();
        if (lp == null) {
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            return;
        }
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == height) return;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = height;
        page.setLayoutParams(lp);
    }

    /** One screen's picture and the settings that belong to it. */
    private final class Split {
        final LinearLayout page;
        final View lead;
        final View picture;
        final LinearLayout rest;
        final ScrollView host;
        final LinearLayout wideRow;
        final LinearLayout picturePane;
        final ScrollView side;
        boolean wide;

        Split(LinearLayout page, View lead, View picture, LinearLayout rest) {
            this.page = page;
            this.lead = lead;
            this.picture = picture;
            this.rest = rest;
            host = page.getParent() instanceof ScrollView ? (ScrollView) page.getParent() : null;
            wideRow = row();
            wideRow.setClipChildren(false);
            wideRow.setClipToPadding(false);
            picturePane = column();
            picturePane.setClipChildren(false);
            side = scroll();
            side.setFillViewport(false);
            side.setPadding(dp(16), 0, 0, 0);
            if (host != null) {
                host.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                         int ol, int ot, int or, int ob) {
                        if (r - l != or - ol || b - t != ob - ot) fit(b - t);
                    }
                });
            }
        }

        void fit(int viewportHeight) {
            if (!wide) {
                setPageHeight(page, ViewGroup.LayoutParams.WRAP_CONTENT);
                return;
            }
            if (viewportHeight > 0) setPageHeight(page, viewportHeight);
        }
    }

    void showStream(boolean show) {
        wantStream = show;
        advancedShowing = false;
        if (advanced != null) advanced.setVisibility(View.GONE);
        if (home != null) home.setVisibility(show ? View.GONE : View.VISIBLE);
        if (stream != null) stream.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    boolean showingStream() { return wantStream; }

    /**
     * A live frame plus the transform it was produced from. The transform drives two things
     * the plain bitmap cannot express: the card takes the frame's own aspect ratio so the
     * whole sensor view fits without cropping, and the band the encoder discards is dimmed.
     */
    void setPreview(Bitmap bitmap, FrameGeometry geometry) {
        if (geometry != null) {
            int width = Math.max(1, geometry.fullWidth);
            int height = Math.max(1, geometry.fullHeight);
            if (width != frameWidth || height != frameHeight) {
                frameWidth = width;
                frameHeight = height;
                reheight(previewFrame, homeScroll);
                reheight(miniFrame, streamScroll);
            }
            if (previewImage != null) previewImage.setMask(geometry);
            if (miniImage != null) miniImage.setMask(geometry);
        }
        setPreview(bitmap);
    }

    void setPreview(Bitmap bitmap) {
        if (bitmap == null) return;
        if (previewImage != null) {
            previewImage.setFrame(bitmap);
            previewImage.setVisibility(View.VISIBLE);
            if (previewScene != null) previewScene.setVisibility(View.GONE);
        }
        if (miniImage != null) {
            miniImage.setFrame(bitmap);
            miniImage.setVisibility(View.VISIBLE);
            if (miniScene != null) miniScene.setVisibility(View.GONE);
        }
    }

    void refresh() {
        if (home == null) return;
        if (advancedShowing) return;
        boolean running = CameraStreamService.running;
        boolean streaming = StreamControls.streaming();
        int brightness = shownBrightness();
        boolean paused = streaming && LightPolicy.pauseStream(Prefs.lightMethod(),
                Prefs.streamLightEnabled(), brightness, Prefs.lightThreshold());
        boolean recording = CameraStreamService.recordingNow;
        boolean front = frontLens();
        String res = StreamPresets.label(Prefs.outputWidth(), Prefs.outputHeight());
        setTextIfChanged(deviceLine, deviceCaption(res));
        if (ScreenSnapshot.preview != null) setPreview(ScreenSnapshot.preview);
        setTextIfChanged(previewClock, ScreenSnapshot.clock != null ? ScreenSnapshot.clock
                : CLOCK.format(new Date()));
        previewClock.setVisibility(Prefs.watermarkEnabled() ? View.VISIBLE : View.GONE);
        float mirror = front ? -1f : 1f;
        if (previewImage != null) previewImage.setScaleX(mirror);
        if (previewScene != null) previewScene.setScaleX(mirror);
        if (miniImage != null) {
            miniImage.setScaleX(mirror);
            miniImage.setRotation(Prefs.manualRotationDegrees());
        }
        if (miniScene != null) {
            miniScene.setScaleX(mirror);
            miniScene.setRotation(Prefs.manualRotationDegrees());
        }
        float dim = paused ? 0.38f : 1f;
        if (previewImage != null) previewImage.setAlpha(dim);
        if (previewScene != null) previewScene.setAlpha(dim);
        if (miniImage != null) miniImage.setAlpha(dim);
        if (miniScene != null) miniScene.setAlpha(dim);

        boolean live = streaming && !paused;
        glow.setVisibility(live ? View.VISIBLE : View.GONE);
        applyGlow(live);
        if (paused) {
            pill(leftPill, "推流已暂停 · 亮度不足", R.color.pill_pause, false);
            rightPill.setVisibility(recording ? View.VISIBLE : View.GONE);
        } else if (live) {
            pill(leftPill, "推流中", R.color.pill_stream, true);
            rightPill.setVisibility(recording ? View.VISIBLE : View.GONE);
        } else if (recording) {
            pill(leftPill, "录制中 · " + hms(recordElapsed()), R.color.pill_record, true);
            rightPill.setVisibility(View.GONE);
        } else {
            pill(leftPill, "未开启", R.color.pill_idle, false);
            rightPill.setVisibility(View.GONE);
        }
        if (recording && (live || paused)) {
            pill(rightPill, "录制中 · " + hms(recordElapsed()), R.color.pill_record, true);
        }
        resChip.setText((front ? "前置" : "后置") + " · " + res);
        resChip.setSingleLine(true);
        int chipInk = resMenu ? color(R.color.primary) : Color.WHITE;
        resChip.setTextColor(chipInk);
        resMic.setVisibility(Prefs.microphoneEnabled() ? View.VISIBLE : View.GONE);
        resMic.setImageDrawable(Glyph.drawable(activity, "mic", 12, Color.WHITE, 2.4f));
        resChevron.setImageDrawable(Glyph.drawable(activity, "down", 14, chipInk, 2.4f));
        GradientDrawable chipBg = round(resMenu ? 0xD9FF7A3D : 0x73000000, 14);
        if (!resMenu) chipBg.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        resChipBox.setBackground(chipBg);
        if (homeRear != null) paintLens(homeRear, !front);
        if (homeFront != null) paintLens(homeFront, front);
        resMenuView.setVisibility(resMenu ? View.VISIBLE : View.GONE);
        markResolution();

        if (streaming) {
            streamTime.setText(hms(System.currentTimeMillis() - CameraStreamService.streamSinceMs));
            streamTime.setTextColor(color(paused ? R.color.ink_amber : R.color.on_surface));
            streamTimeCaption.setText(paused ? "已暂停" : "推流时长");
        } else {
            streamTime.setText("未推流");
            streamTime.setTextColor(color(R.color.on_surface));
            streamTimeCaption.setText("推流时长");
        }
        int today = ScreenSnapshot.todayClips >= 0 ? ScreenSnapshot.todayClips : activity.v22Today;
        int total = ScreenSnapshot.totalClips >= 0 ? ScreenSnapshot.totalClips : activity.v22Total;
        todayStat.setText((today >= 0 ? today : 0) + " 段");
        freeStat.setText(freeLabel());
        soundCaption.setText(Prefs.microphoneEnabled()
                ? "MJPEG 不带声音，RTSP 和录像有声音" : "仅画面，不含声音");
        suppress = true;
        soundSwitch.setOn(Prefs.microphoneEnabled());
        suppress = false;
        int kinds = triggerCount();
        recordCard.setText(HomeEntrySummaries.triggerCard(Prefs.matchAll(), kinds)
                + (recording ? "\n● 录制中" : ""));
        libraryCard.setText(HomeEntrySummaries.libraryCard(Math.max(today, 0), Math.max(total, 0)));
        streamCard.setText(HomeEntrySummaries.streamCard(streaming, paused, Prefs.haEnabled()));
        styleStreamButton(streaming, paused);

        pauseNote.setVisibility(paused ? View.VISIBLE : View.GONE);
        if (paused) pauseNote.setText("光线传感器 " + brightness + "%，低于 "
                + Prefs.lightThreshold() + "%，恢复后自动继续");

        if (miniPill != null) {
            if (paused) pill(miniPill, "推流已暂停 · 亮度不足", R.color.pill_pause, false);
            else if (streaming) pill(miniPill, "推流中", R.color.pill_stream, true);
            else pill(miniPill, "预览", R.color.pill_idle, false);
        }
        if (miniMeta != null) miniMeta.setText((front ? "前置" : "后置") + "镜头 · " + Prefs.manualRotationDegrees() + "°");
        if (streamResValue != null) streamResValue.setText(res + " ›");
        paintStreamChip(streaming, paused);
        refreshAddresses(streaming);
        suppress = true;
        if (streamSwitch != null) streamSwitch.setOn(streaming);
        if (lightStreamSwitch != null) lightStreamSwitch.setOn(Prefs.streamLightEnabled());
        if (haSwitch != null) haSwitch.setOn(Prefs.haEnabled());
        if (haAudioSwitch != null) haAudioSwitch.setOn(Prefs.haAudioEnabled());
        if (haNotifySwitch != null) haNotifySwitch.setOn(Prefs.haNotifyEnabled());
        suppress = false;
        if (haHost != null) haHost.setText(haStatus());
        if (haAudioCaption != null) haAudioCaption.setText(Prefs.haAudioEnabled()
                ? "HA 通过 RTSP 接收声音" : "仅推送画面");
        if (haBody != null) haBody.setAlpha(Prefs.haEnabled() ? 1f : 0.45f);
        paintLightSummary(brightness);
        if (lightSummaryBox != null) lightSummaryBox.setAlpha(Prefs.streamLightEnabled() ? 1f : 0.5f);
        if (entityId != null) setTextIfChanged(entityId, "camera.afterglow_" + safeId());
        // The lens and rotation segments only change when the preference does; walking
        // both full view trees every second was pure overhead.
        int rotation = Prefs.manualRotationDegrees();
        if (front != paintedFront || rotation != paintedRotation) {
            paintedFront = front;
            paintedRotation = rotation;
            paintSegments(home);
            paintSegments(stream);
        }
    }

    /** Assigns only on change so a steady screen does not request layout every second. */
    private static void setTextIfChanged(TextView view, String value) {
        if (view == null) return;
        CharSequence current = view.getText();
        if (current != null && current.toString().equals(value)) return;
        view.setText(value);
    }

    private void paintSegments(View view) {
        if (!(view instanceof TextView)) {
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) paintSegments(group.getChildAt(i));
            }
            return;
        }
        CharSequence value = ((TextView) view).getText();
        if (value == null) return;
        String text = value.toString();
        Boolean on = null;
        if ("后置镜头".equals(text) || "前置镜头".equals(text)) return;
        if ("后置".equals(text)) on = !frontLens();
        else if ("前置".equals(text)) on = frontLens();
        else if ("0°".equals(text)) on = Prefs.manualRotationDegrees() == 0;
        else if ("90°".equals(text)) on = Prefs.manualRotationDegrees() == 90;
        else if ("180°".equals(text)) on = Prefs.manualRotationDegrees() == 180;
        else if ("270°".equals(text)) on = Prefs.manualRotationDegrees() == 270;
        else if ("满足任一".equals(text) || "全部满足".equals(text)) return;
        if (on == null) return;
        TextView button = (TextView) view;
        button.setBackground(on ? round(color(R.color.surface), 11) : null);
        int ink = color(on ? R.color.on_surface : R.color.on_surface_variant);
        button.setTextColor(ink);
        if (button.getCompoundDrawables()[0] != null) {
            String icon = text.startsWith("后置") ? "camera" : "person";
            button.setCompoundDrawables(Glyph.drawable(activity, icon, 16, ink, 2f), null, null, null);
        }
    }

    private View buildHome() {
        ScrollView scroll = scroll();
        homeScroll = scroll;
        LinearLayout page = page();
        scroll.addView(page);
        View head = header();
        FrameLayout preview = preview(false);
        previewFrame = preview;
        LinearLayout rest = column();
        rest.addView(lensSegment());
        rest.addView(stats());
        View streamControl = bigButton();
        streamFace.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22ToggleStream(); }
        });
        rest.addView(streamControl);
        pauseNote = caption("");
        pauseNote.setGravity(Gravity.CENTER);
        rest.addView(gap(pauseNote, 0, 8, 0, 0));
        rest.addView(gap(soundCard(), 0, 12, 0, 0));
        rest.addView(section("功能"));
        rest.addView(entries());
        rest.addView(gap(advancedEntry(), 0, 12, 0, 0));
        homeSplit = new Split(page, head, preview, rest);
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homeOverlay = overlay;
        if (resMenuView != null && resMenuView.getParent() == null) {
            FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                    dp(196), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            overlay.addView(resMenuView, menuLp);
        }
        preview.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                positionResolutionMenu();
            }
        });
        scroll.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { positionResolutionMenu(); }
        });
        return overlay;
    }

    /**
     * Anchors the resolution menu under the preview's top-right corner. Measured in window
     * space so the same code works whether the picture sits under the header or beside it.
     */
    private void positionResolutionMenu() {
        if (resMenuView == null || previewFrame == null || homeScroll == null || homeOverlay == null) return;
        if (!(resMenuView.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        previewFrame.getLocationInWindow(menuFrame);
        homeOverlay.getLocationInWindow(menuOverlay);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) resMenuView.getLayoutParams();
        int top = menuFrame[1] - menuOverlay[1] + dp(6);
        int right = Math.max(dp(8), homeOverlay.getWidth()
                - (menuFrame[0] - menuOverlay[0] + previewFrame.getWidth()) + dp(8));
        if (lp.topMargin == top && lp.rightMargin == right && lp.gravity == (Gravity.TOP | Gravity.RIGHT)) return;
        lp.topMargin = top;
        lp.rightMargin = right;
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        resMenuView.setLayoutParams(lp);
    }

    private View buildStream() {
        streamScroll = scroll();
        LinearLayout page = page();
        streamScroll.addView(page);
        View bar = topBar("画面与推流", "画面参数、局域网推流与 Home Assistant", true);
        FrameLayout mini = preview(true);
        LinearLayout rest = column();
        rest.addView(section("画面"));
        LinearLayout picture = card();
        picture.addView(valueRow("aspect", R.color.ink_orange, R.color.tonal_orange, "推流分辨率",
                "主界面预览右下角也能切", StreamPresets.label(Prefs.outputWidth(), Prefs.outputHeight()),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { cycleResolution(); }
                }));
        picture.addView(hairline());
        picture.addView(lensRow());
        picture.addView(hairline());
        picture.addView(rotationBlock());
        picture.addView(hairline());
        picture.addView(switchRow("text", R.color.on_surface_variant, R.color.tonal_grey, "时间水印",
                "在画面左下角显示日期时间", Prefs.watermarkEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        Prefs.setBool(Prefs.KEY_WATERMARK, on);
                        refresh();
                    }
                }));
        rest.addView(picture);
        LinearLayout pushTitle = sectionBar("推流");
        streamLiveChip = liveChip();
        pushTitle.addView(streamLiveChip);
        rest.addView(pushTitle);
        streamPushTop = 0;
        pushTitle.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                ScrollView scroller = streamSplit != null && streamSplit.wide
                        ? streamSplit.side : streamScroll;
                if (scroller == null) return;
                int[] here = new int[2];
                int[] host = new int[2];
                v.getLocationInWindow(here);
                scroller.getLocationInWindow(host);
                // Offset inside the scroll content, whichever pane happens to hold it.
                streamPushTop = here[1] - host[1] + scroller.getScrollY();
                if ("stream-push".equals(pageShot) && streamPushTop > 0) {
                    scroller.scrollTo(0, Math.max(0, streamPushTop - dp(8)));
                }
            }
        });
        LinearLayout push = card();
        streamSwitch = null;
        push.addView(switchRow("cast", R.color.ink_cyan, R.color.tonal_cyan, "局域网推流",
                "同一 Wi-Fi 下用 VLC 等打开", StreamControls.streaming(), true, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        activity.v22ToggleStream();
                    }
                }));
        addressBox = addressBlock();
        push.addView(addressBox);
        lightStreamSwitch = null;
        push.addView(hairline());
        push.addView(switchRow("sun", R.color.ink_amber, R.color.tonal_amber, "光感控制推流",
                "与录制共用亮度阈值。亮度不达标时的行为取决于感光方式。",
                Prefs.streamLightEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_STREAM_LIGHT, on);
                        if (CameraStreamService.running) StreamControls.reconfigure(activity);
                        refresh();
                    }
                }));
        lightSummaryBox = lightSummaryCard();
        push.addView(lightSummaryBox);
        rest.addView(push);
        rest.addView(section("Home Assistant 接入"));
        LinearLayout ha = card();
        haSwitch = null;
        ha.addView(switchRow("home", R.color.ink_purple, R.color.tonal_purple, "接入 Home Assistant",
                Prefs.haEnabled() ? "● 已连接 · homeassistant.local" : "未接入", Prefs.haEnabled(), false,
                new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_HA, on);
                        refresh();
                    }
                }));
        haBody = column();
        haAudioSwitch = null;
        ((LinearLayout) haBody).addView(switchRow("mic", R.color.ink_cyan, R.color.tonal_cyan, "推送音频到 HA",
                "HA 通过 RTSP 接收声音", Prefs.haAudioEnabled(), true, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_HA_AUDIO, on);
                        if (CameraStreamService.running) StreamControls.reconfigure(activity);
                        refresh();
                    }
                }));
        haNotifySwitch = null;
        ((LinearLayout) haBody).addView(hairline());
        ((LinearLayout) haBody).addView(switchRow("bell", R.color.ink_orange, R.color.tonal_orange, "触发事件通知",
                "画面变动 / 声音触发时发送事件", Prefs.haNotifyEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_HA_NOTIFY, on);
                    }
                }));
        entityId = caption("");
        entityId.setTypeface(Typeface.MONOSPACE);
        entityId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ImageView copyIcon = new ImageView(activity);
        copyIcon.setImageDrawable(Glyph.drawable(activity, "copy", 18, color(R.color.on_surface_variant), 2f));
        View entity = labeledRow("link", R.color.on_surface_variant, R.color.tonal_grey,
                "实体 ID", entityId, copyIcon);
        entity.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(entityId.getText().toString(), "已复制实体 ID"); }
        });
        ((LinearLayout) haBody).addView(hairline());
        ((LinearLayout) haBody).addView(entity);
        ha.addView(haBody);
        rest.addView(ha);
        streamSplit = new Split(page, bar, mini, rest);
        return streamScroll;
    }

    private View header() {
        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, dp(28));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LogoView logo = new LogoView(activity);
        int glowPad = dp(32);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(40) + glowPad * 2, dp(40) + glowPad * 2);
        logoLp.setMargins(-glowPad, -glowPad, -glowPad, -glowPad);
        row.addView(logo, logoLp);
        LinearLayout text = column();
        text.setPadding(dp(12), 0, 0, 0);
        TextView title = text("余光", 28, color(R.color.on_surface), true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        deviceLine = caption("");
        text.addView(title);
        text.addView(deviceLine);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private FrameLayout preview(boolean mini) {
        final FrameLayout frame = new FrameLayout(activity);
        frame.setBackground(round(color(R.color.preview_bg), 20));
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        FrameLayout clip = roundedClip();
        frame.addView(clip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        RoomScene scene = new RoomScene(activity);
        PreviewPane image = new PreviewPane(activity);
        image.setVisibility(View.GONE);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clip.addView(scene, fill);
        clip.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (mini) {
            miniFrame = frame;
            miniScene = scene;
            miniImage = image;
            miniPill = pillView();
            miniMeta = overlayText();
            FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
            pillLp.gravity = Gravity.TOP | Gravity.LEFT;
            pillLp.leftMargin = dp(10);
            pillLp.topMargin = dp(10);
            frame.addView(miniPill, pillLp);
            FrameLayout.LayoutParams metaLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            metaLp.rightMargin = dp(12);
            metaLp.bottomMargin = dp(10);
            frame.addView(miniMeta, metaLp);
        } else {
            previewScene = scene;
            previewImage = image;
            glow = new Motion.Plate(activity);
            glow.setClickable(false);
            FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int glowPad = Motion.glowPad(activity);
            glowLp.setMargins(-glowPad, -glowPad, -glowPad, -glowPad);
            frame.addView(glow, glowLp);
            leftPill = pillView();
            rightPill = pillView();
            previewClock = overlayText();
            resChipBox = row();
            resChipBox.setGravity(Gravity.CENTER_VERTICAL);
            resChipBox.setPadding(dp(10), 0, dp(6), 0);
            resMic = new ImageView(activity);
            resChip = text("", 12, Color.WHITE, true);
            resChip.setSingleLine(true);
            resChevron = new ImageView(activity);
            resChipBox.addView(resMic, new LinearLayout.LayoutParams(dp(12), dp(12)));
            LinearLayout.LayoutParams resText = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            resText.leftMargin = dp(4);
            resText.rightMargin = dp(2);
            resChipBox.addView(resChip, resText);
            resChipBox.addView(resChevron, new LinearLayout.LayoutParams(dp(14), dp(14)));
            resChipBox.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    resMenu = !resMenu;
                    refresh();
                }
            });
            FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            left.gravity = Gravity.TOP | Gravity.LEFT;
            left.leftMargin = dp(10);
            left.topMargin = dp(10);
            FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            right.gravity = Gravity.TOP | Gravity.RIGHT;
            right.rightMargin = dp(10);
            right.topMargin = dp(10);
            FrameLayout.LayoutParams clock = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clock.gravity = Gravity.BOTTOM | Gravity.LEFT;
            clock.leftMargin = dp(14);
            clock.bottomMargin = dp(10);
            FrameLayout.LayoutParams chip = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            chip.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            chip.rightMargin = dp(10);
            chip.bottomMargin = dp(10);
            frame.addView(leftPill, left);
            frame.addView(rightPill, right);
            frame.addView(previewClock, clock);
            frame.addView(resChipBox, chip);
            resMenuView = resolutionMenu();
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frame.setLayoutParams(lp);
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int width = r - l;
                if (width <= 0) return;
                int height = pictureHeight(width, mini ? streamScroll : homeScroll);
                if (b - t != height) {
                    v.getLayoutParams().height = height;
                    v.requestLayout();
                }
            }
        });
        return frame;
    }

    /**
     * The picture takes the frame's own aspect ratio so the whole sensor view fits without
     * cropping. In landscape it shares the width with the settings column, so it is also
     * capped to the room the page actually has.
     */
    private int pictureHeight(int width, ScrollView host) {
        int height = (int) ((long) width * frameHeight / Math.max(1, frameWidth));
        if (landscape() && host != null && host.getHeight() > 0) {
            int room = host.getHeight() - dp(132);
            if (room > dp(120)) height = Math.min(height, room);
        }
        return Math.max(dp(96), height);
    }

    /** Applies {@link #pictureHeight} now; a layout change alone does not fire on a new aspect. */
    private void reheight(View frame, ScrollView host) {
        if (frame == null) return;
        int width = frame.getWidth();
        if (width <= 0) return;
        int height = pictureHeight(width, host);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        if (lp == null || lp.height == height) return;
        lp.height = height;
        frame.requestLayout();
    }

    private boolean landscape() {
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private View resolutionMenu() {
        LinearLayout menu = plainCard();
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView title = text("推流分辨率", 12, color(R.color.on_surface_variant), false);
        title.setPadding(dp(10), dp(6), dp(10), dp(4));
        menu.addView(title);
        for (int i = 0; i < StreamPresets.SIZES.length; i++) {
            final int width = StreamPresets.SIZES[i][0];
            final int height = StreamPresets.SIZES[i][1];
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(40));
            row.setPadding(dp(8), 0, dp(10), 0);
            row.setTag(width + "x" + height);
            ImageView mark = new ImageView(activity);
            mark.setImageDrawable(Glyph.drawable(activity, "check", 16, color(R.color.ink_orange), 2.6f));
            row.addView(mark, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView label = text(StreamPresets.LABELS[i], 15, color(R.color.on_surface), false);
            label.setPadding(dp(8), 0, 0, 0);
            row.addView(label);
            View spacer = new View(activity);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            TextView pixels = text(width + "×" + height, 12, color(R.color.on_surface_disabled), false);
            row.addView(pixels);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    resMenu = false;
                    activity.v22SetResolution(width, height);
                }
            });
            menu.addView(row);
        }
        View line = new View(activity);
        line.setBackgroundColor(color(R.color.outline));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lineLp.topMargin = dp(4);
        menu.addView(line, lineLp);
        LinearLayout hint = row();
        hint.setPadding(dp(8), dp(6), dp(8), dp(4));
        ImageView info = new ImageView(activity);
        info.setImageDrawable(Glyph.drawable(activity, "info", 13, color(R.color.on_surface_variant), 2f));
        hint.addView(info, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView hintText = caption("推流中切换会断开 1–2 秒后自动重连；正在录的一段会结束");
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hintText.setPadding(dp(5), 0, 0, 0);
        hint.addView(hintText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        menu.addView(hint);
        menu.setVisibility(View.GONE);
        return menu;
    }

    private void markResolution() {
        if (!(resMenuView instanceof LinearLayout)) return;
        LinearLayout menu = (LinearLayout) resMenuView;
        String current = Prefs.outputWidth() + "x" + Prefs.outputHeight();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (!(child instanceof LinearLayout) || child.getTag() == null) continue;
            LinearLayout row = (LinearLayout) child;
            boolean on = current.equals(row.getTag());
            row.setBackground(on ? round(color(R.color.tonal_orange), 10) : null);
            int ink = color(on ? R.color.ink_orange : R.color.on_surface);
            if (row.getChildAt(0) != null) row.getChildAt(0).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (row.getChildAt(1) instanceof TextView) {
                TextView label = (TextView) row.getChildAt(1);
                label.setTextColor(ink);
                label.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
            }
            View last = row.getChildAt(row.getChildCount() - 1);
            if (last instanceof TextView) {
                ((TextView) last).setTextColor(color(on ? R.color.ink_orange : R.color.on_surface_disabled));
            }
        }
    }

    private View lensSegment() {
        LinearLayout seg = segment();
        homeRear = lensButton("camera", "后置镜头", new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(false); }
        });
        homeFront = lensButton("users", "前置镜头", new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(true); }
        });
        seg.addView(homeRear, segmentLp());
        seg.addView(homeFront, segmentLp());
        return gap(seg, 0, 12, 0, 0);
    }

    private View stats() {
        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, 0);
        streamTime = text("未推流", 16, color(R.color.on_surface), true);
        streamTimeCaption = caption("推流时长");
        todayStat = text("0 段", 16, color(R.color.on_surface), true);
        freeStat = text("—", 16, color(R.color.on_surface), true);
        row.addView(stat(streamTime, streamTimeCaption), statLp());
        row.addView(stat(todayStat, caption("今日录像")), statLp());
        row.addView(stat(freeStat, caption("剩余空间")), statLp());
        return row;
    }

    private View stat(TextView value, TextView label) {
        LinearLayout box = column();
        box.setBackground(round(color(R.color.surface), 16));
        box.setPadding(dp(12), dp(9), dp(12), dp(9));
        box.setGravity(Gravity.LEFT);
        value.setGravity(Gravity.LEFT);
        label.setGravity(Gravity.LEFT);
        box.addView(value);
        box.addView(label);
        return box;
    }

    private View soundCard() {
        LinearLayout card = card();
        soundCaption = caption("");
        soundSwitch = new PillSwitch(activity, true);
        soundSwitch.setOnChanged(new PillSwitch.Listener() {
            @Override public void onChanged(boolean on) {
                if (!suppress) activity.v22SetMic(on);
            }
        });
        card.addView(labeledRow("mic", R.color.ink_cyan, R.color.tonal_cyan, "录制声音", soundCaption, soundSwitch));
        return card;
    }

    private View entries() {
        LinearLayout row = row();
        recordCard = caption("");
        libraryCard = caption("");
        streamCard = caption("");
        row.addView(entry("tune", "录制设置", recordCard, R.color.tonal_orange, R.color.ink_orange, new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22OpenSettings(false); }
        }), statLp());
        row.addView(entry("film", "录像列表", libraryCard, R.color.tonal_purple, R.color.ink_purple, new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22OpenLibrary(); }
        }), statLp());
        row.addView(entry("cast", "画面与推流", streamCard, R.color.tonal_cyan, R.color.ink_cyan, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showStream(true);
                refresh();
            }
        }), statLp());
        return row;
    }

    /**
     * The screens that only the legacy panel still owns: RTSP credentials, keep-alive,
     * calibration, aspect ratio, custom sizes and LAN discovery. Hiding the old layout
     * without a replacement dropped every one of them, so the panel itself is moved into
     * this page instead of being rebuilt.
     */
    private View advancedEntry() {
        LinearLayout card = card();
        card.addView(valueRow("tune", R.color.ink_amber, R.color.tonal_amber, "高级设置",
                "认证、保活、校准、画幅、自定义尺寸与设备发现", "",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showAdvanced(true); }
                }));
        return card;
    }

    private View buildAdvanced() {
        ScrollView scroll = scroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(topBar("高级设置", "认证、保活、校准、画幅与设备发现", new Runnable() {
            @Override public void run() { showAdvanced(false); }
        }));
        advancedHost = column();
        page.addView(advancedHost);
        return scroll;
    }

    /** Adopts the legacy settings panel, which keeps every listener bound in MainActivity. */
    void hostAdvanced(View panel) {
        if (advancedHost == null || panel == null || panel.getParent() == advancedHost) return;
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        advancedHost.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void showAdvanced(boolean show) {
        if (advanced == null) return;
        advancedShowing = show;
        advanced.setVisibility(show ? View.VISIBLE : View.GONE);
        if (home != null) home.setVisibility(show || wantStream ? View.GONE : View.VISIBLE);
        if (stream != null) stream.setVisibility(show ? View.GONE : (wantStream ? View.VISIBLE : View.GONE));
        if (show) activity.v22RefreshAdvanced();
        else refresh();
    }

    boolean showingAdvanced() { return advancedShowing; }

    private View entry(String icon, String title, TextView summary, int well, int ink, View.OnClickListener click) {
        LinearLayout card = card();
        card.setMinimumHeight(dp(118));
        card.setPadding(dp(12), dp(14), dp(12), dp(14));
        card.setOnClickListener(click);
        card.addView(Glyph.well(activity, icon, color(well), color(ink)),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = text(title, 14, color(R.color.on_surface), true);
        name.setPadding(0, dp(8), 0, 0);
        summary.setTextColor(color(R.color.on_surface_variant));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(name);
        card.addView(summary);
        return card;
    }

    private void refreshAddresses(boolean streaming) {
        if (addressBox == null) return;
        String ip = NetUtils.localIp();
        rtspValue.setText("rtsp://" + ip + ":8554/live");
        mjpegValue.setText("http://" + ip + ":8080/stream.mjpeg");
        addressBox.setAlpha(streaming ? 1f : 0.45f);
        enableCopy(addressBox, streaming);
    }

    private void enableCopy(View view, boolean enabled) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableCopy(group.getChildAt(i), enabled);
        }
        if ("copy".equals(view.getTag())) {
            view.setEnabled(enabled);
            view.setClickable(enabled);
        }
    }

    private String rtspLine() {
        return "rtsp://" + NetUtils.localIp() + ":8554/live";
    }

    private void paintLightSummary(int brightness) {
        if (lightThValue == null) return;
        boolean sensor = LightPolicy.SENSOR.equals(Prefs.lightMethod());
        boolean met = LightPolicy.satisfied(brightness, Prefs.lightThreshold());
        lightThValue.setText("够亮才触发 · " + Prefs.lightThreshold() + "%");
        lightMethodValue.setText(sensor ? "光线传感器" : "画面亮度");
        lightNowValue.setText(brightness < 0 ? "—" : brightness + "%");
        lightMet.setText(met ? "已满足" : "未满足");
        lightMet.setTextColor(color(met ? R.color.ink_cyan : R.color.on_surface_variant));
        lightMet.setBackground(round(color(met ? R.color.tonal_cyan : R.color.tonal_grey), 12));
        lightNote.setText(sensor
                ? "光线传感器模式：亮度不够时推流和录制都暂停"
                : "画面亮度模式：亮度不够时推流继续，只暂停录制");
    }

    private void paintStreamChip(boolean streaming, boolean paused) {
        if (streamLiveChip == null) return;
        streamLiveChip.setVisibility(streaming ? View.VISIBLE : View.GONE);
        if (!streaming) return;
        int ink = color(paused ? R.color.ink_amber : R.color.ink_cyan);
        streamLiveChip.setBackground(round(color(paused ? R.color.tonal_amber : R.color.tonal_cyan), 12));
        streamLiveLabel.setText(paused ? "已暂停 · 亮度不足" : "推流中");
        streamLiveLabel.setTextColor(ink);
        streamLiveIcon.setVisibility(paused ? View.VISIBLE : View.GONE);
        streamLiveDot.setVisibility(paused ? View.GONE : View.VISIBLE);
        if (paused) {
            streamLiveIcon.setImageDrawable(Glyph.drawable(activity, "sun", 12, ink, 2.6f));
        } else {
            streamLiveDot.color = color(R.color.secondary);
            streamLiveDot.radiusDp = 3.5f;
            streamLiveDot.breathe = !Motion.reduced(activity);
            streamLiveDot.invalidate();
        }
    }

    private CharSequence haStatus() {
        if (!Prefs.haEnabled()) return "未接入";
        String lead = "● 已连接";
        SpannableString span = new SpannableString(lead + " · homeassistant.local");
        span.setSpan(new ForegroundColorSpan(color(R.color.ink_cyan)), 0, lead.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private LinearLayout sectionBar(String label) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(20), dp(4), dp(8));
        bar.addView(text(label, 20, color(R.color.on_surface), true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    private LinearLayout liveChip() {
        LinearLayout chip = row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), 0, dp(9), 0);
        chip.setMinimumHeight(dp(24));
        streamLiveIcon = new ImageView(activity);
        streamLiveDot = new Motion.BreathDot(activity);
        streamLiveDot.radiusDp = 3.5f;
        streamLiveLabel = text("推流中", 12, color(R.color.ink_cyan), true);
        chip.addView(streamLiveIcon, new LinearLayout.LayoutParams(dp(12), dp(12)));
        chip.addView(streamLiveDot, new LinearLayout.LayoutParams(dp(16), dp(16)));
        streamLiveLabel.setPadding(dp(4), 0, 0, 0);
        chip.addView(streamLiveLabel);
        chip.setVisibility(View.GONE);
        return chip;
    }

    private LinearLayout addressBlock() {
        LinearLayout box = column();
        box.setPadding(dp(16), 0, dp(16), dp(12));
        TextView hint = caption("两路同时推，按播放器选一个地址");
        hint.setPadding(0, dp(2), 0, dp(8));
        box.addView(hint);
        rtspValue = monoAddress();
        mjpegValue = monoAddress();
        box.addView(addressCard("RTSP", R.color.tonal_cyan, R.color.ink_cyan, rtspValue),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(gap(addressCard("MJPEG", R.color.tonal_grey, R.color.on_surface_variant, mjpegValue),
                0, 6, 0, 0));
        LinearLayout note = row();
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(0, dp(8), 0, 0);
        ImageView mic = new ImageView(activity);
        mic.setImageDrawable(Glyph.drawable(activity, "mic", 14, color(R.color.on_surface_variant), 2f));
        note.addView(mic, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView words = caption("MJPEG 不带声音，RTSP 和录像有声音");
        words.setPadding(dp(6), 0, 0, 0);
        note.addView(words);
        box.addView(note);
        return box;
    }

    private TextView monoAddress() {
        TextView view = text("", 12, color(R.color.on_surface), false);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private View addressCard(String tag, int well, int ink, final TextView value) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(12), dp(6), dp(6), dp(6));
        row.setBackground(round(color(R.color.surface_2), 14));
        TextView badge = text(tag, 11, color(ink), true);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        badge.setBackground(round(color(well), 6));
        row.addView(badge);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        valueLp.leftMargin = dp(8);
        valueLp.rightMargin = dp(8);
        row.addView(value, valueLp);
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(Glyph.drawable(activity, "copy", 16, color(R.color.ink_cyan), 2.2f));
        FrameLayout button = new FrameLayout(activity);
        button.setTag("copy");
        button.setBackground(round(color(R.color.tonal_cyan), 17));
        button.setContentDescription("复制" + tag + "地址");
        button.addView(icon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!StreamControls.streaming()) return;
                copy(value.getText().toString(), "已复制推流地址");
            }
        });
        row.addView(button, new LinearLayout.LayoutParams(dp(34), dp(34)));
        return row;
    }

    private View lightSummaryCard() {
        LinearLayout box = column();
        box.setBackground(round(color(R.color.surface_2), 16));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        lightThValue = text("", 14, color(R.color.on_surface), true);
        lightMethodValue = text("", 14, color(R.color.on_surface), true);
        lightNowValue = text("", 14, color(R.color.on_surface), true);
        lightMet = text("已满足", 12, color(R.color.ink_cyan), true);
        lightMet.setPadding(dp(8), dp(2), dp(8), dp(2));
        box.addView(kv("共用阈值", lightThValue));
        box.addView(kv("感光方式", lightMethodValue));
        LinearLayout now = kv("当前亮度", lightNowValue);
        LinearLayout.LayoutParams metLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metLp.leftMargin = dp(6);
        now.addView(lightMet, metLp);
        box.addView(now);
        View line = new View(activity);
        line.setBackgroundColor(color(R.color.outline));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lineLp.topMargin = dp(6);
        box.addView(line, lineLp);
        LinearLayout note = row();
        note.setGravity(Gravity.TOP);
        note.setPadding(0, dp(8), 0, 0);
        ImageView info = new ImageView(activity);
        info.setImageDrawable(Glyph.drawable(activity, "info", 14, color(R.color.on_surface_variant), 2f));
        note.addView(info, new LinearLayout.LayoutParams(dp(14), dp(14)));
        lightNote = caption("");
        lightNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lightNote.setPadding(dp(6), 0, 0, 0);
        note.addView(lightNote, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(note);
        TextView go = text("去录制设置调整 ›", 14, color(R.color.ink_amber), true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(round(color(R.color.tonal_amber), 18));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22OpenSettings(true); }
        });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        goLp.topMargin = dp(10);
        box.addView(go, goLp);
        return box;
    }

    private LinearLayout kv(String key, TextView value) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(30));
        line.addView(text(key, 14, color(R.color.on_surface_variant), false), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(value);
        return line;
    }

    private LinearLayout lensButton(final String icon, String label, View.OnClickListener click) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(34));
        ImageView image = new ImageView(activity);
        image.setTag(icon);
        TextView name = text(label, 14, color(R.color.on_surface_variant), false);
        name.setPadding(dp(6), 0, 0, 0);
        button.addView(image, new LinearLayout.LayoutParams(dp(16), dp(16)));
        button.addView(name);
        button.setOnClickListener(click);
        return button;
    }

    private void paintLens(LinearLayout button, boolean on) {
        button.setBackground(on ? round(color(R.color.surface), 11) : null);
        int ink = color(on ? R.color.on_surface : R.color.on_surface_variant);
        ImageView image = (ImageView) button.getChildAt(0);
        TextView name = (TextView) button.getChildAt(1);
        name.setTextColor(ink);
        name.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        image.setImageDrawable(Glyph.drawable(activity, String.valueOf(image.getTag()), 16, ink, 2f));
    }

    private void cycleResolution() {
        int width = Prefs.outputWidth();
        int height = Prefs.outputHeight();
        int index = StreamPresets.indexOf(width, height);
        int next;
        if (index >= 0) {
            next = (index + 1) % StreamPresets.SIZES.length;
        } else {
            // A custom size (set in 高级设置) is not a preset. Step to the next larger
            // preset instead of jumping to a fixed one, so the control never moves
            // backwards and the stored value is only replaced by an explicit tap.
            long pixels = (long) width * height;
            next = 0;
            for (int i = StreamPresets.SIZES.length - 1; i >= 0; i--) {
                if ((long) StreamPresets.SIZES[i][0] * StreamPresets.SIZES[i][1] > pixels) next = i;
            }
        }
        activity.v22SetResolution(StreamPresets.SIZES[next][0], StreamPresets.SIZES[next][1]);
    }

    private View lensRow() {
        LinearLayout row = row();
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.addView(Glyph.well(activity, "camera", color(R.color.tonal_cyan), color(R.color.ink_cyan)),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView lensTitle = text("镜头切换", 16, color(R.color.on_surface), true);
        lensTitle.setPadding(dp(14), 0, 0, 0);
        row.addView(lensTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout seg = segment();
        seg.addView(segmentButton("后置", !frontLens(), new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(false); }
        }), segmentLp());
        seg.addView(segmentButton("前置", frontLens(), new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(true); }
        }), segmentLp());
        row.addView(seg, new LinearLayout.LayoutParams(dp(132), dp(40)));
        return row;
    }

    private View rotationBlock() {
        LinearLayout block = column();
        block.addView(labeledRow("rotate", R.color.ink_purple, R.color.tonal_purple, "画面旋转",
                caption("手机横放或倒挂时调整"), null));
        LinearLayout seg = segment();
        final int[] degrees = {0, 90, 180, 270};
        for (int i = 0; i < degrees.length; i++) {
            final int value = degrees[i];
            seg.addView(segmentButton(value + "°", Prefs.manualRotationDegrees() == value,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { activity.v22SetRotation(value); }
                    }), segmentLp());
        }
        block.addView(gap(seg, 16, 0, 16, 12));
        return block;
    }

    private View topBar(String title, String subtitle, boolean back) {
        return topBar(title, subtitle, back ? new Runnable() {
            @Override public void run() {
                showStream(false);
                refresh();
            }
        } : null);
    }

    private View topBar(String title, String subtitle, final Runnable onBack) {
        LinearLayout block = column();
        if (onBack != null) {
            ImageView button = new ImageView(activity);
            button.setImageDrawable(Glyph.drawable(activity, "back", 24, color(R.color.on_surface), 2f));
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onBack.run(); }
            });
            block.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView name = text(title, 28, color(R.color.on_surface), true);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        block.addView(name);
        block.addView(caption(subtitle));
        return block;
    }

    private View bigButton() {
        int extra = dp(40);
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setClipChildren(false);
        streamHalo = new Motion.Halo(activity);
        streamHalo.inset = extra;
        FrameLayout face = new FrameLayout(activity);
        streamFace = face;
        LinearLayout group = row();
        group.setGravity(Gravity.CENTER);
        streamMark = new Motion.Mark(activity);
        streamButton = text("开始推流", 18, Color.WHITE, true);
        streamButton.setTypeface(Typeface.DEFAULT_BOLD);
        group.addView(streamMark, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = dp(10);
        group.addView(streamButton, textLp);
        face.addView(group, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        wrap.addView(streamHalo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        buttonLp.setMargins(extra, extra, extra, extra);
        wrap.addView(face, buttonLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60) + extra * 2);
        lp.leftMargin = -extra;
        lp.rightMargin = -extra;
        lp.topMargin = dp(18) - extra;
        lp.bottomMargin = dp(22) - extra;
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private void styleStreamButton(boolean streaming, boolean paused) {
        streamButton.setText(streaming ? "停止推流" : "开始推流");
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(30));
        if (!streaming) {
            // Same orange ramp in both themes. Night primary_dark is the page
            // color, used by the status bar; using it here paints a hard dark band.
            shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            shape.setColors(new int[]{0xFFF7743A, 0xFFE2561F});
            streamButton.setTextColor(Color.WHITE);
            streamMark.color = Color.WHITE;
            streamMark.square = false;
            streamHalo.mode = Motion.Halo.SHADOW;
        } else {
            shape.setColor(color(R.color.tonal_orange));
            float strokeAlpha = paused || Motion.reduced(activity) ? 0.4f
                    : 0.5f + 0.4f * Motion.glow(activity);
            shape.setStroke(dp(2), (Math.round(strokeAlpha * 255f) << 24) | 0x00FF7A3D);
            streamButton.setTextColor(color(R.color.ink_orange));
            streamMark.color = color(R.color.ink_orange);
            streamMark.square = true;
            streamHalo.mode = paused || Motion.reduced(activity) ? Motion.Halo.NONE : Motion.Halo.ORANGE;
        }
        streamMark.invalidate();
        streamFace.setBackground(shape);
        streamHalo.invalidate();
    }

    private android.graphics.drawable.Drawable markBitmap(Motion.Mark mark) {
        int size = dp(22);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        mark.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        mark.layout(0, 0, size, size);
        mark.draw(new android.graphics.Canvas(bitmap));
        android.graphics.drawable.BitmapDrawable drawable = new android.graphics.drawable.BitmapDrawable(activity.getResources(), bitmap);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    private void applyGlow(boolean live) {
        if (glow == null) return;
        glow.live = live;
        glow.setVisibility(live ? View.VISIBLE : View.GONE);
        glow.invalidate();
    }

    private static String deviceCaption(String resolution) {
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
        if (model.length() == 0 || "unknown".equalsIgnoreCase(model)) return resolution;
        return model + " · " + resolution;
    }

    private View switchRow(String icon, int ink, int well, String title, String cap, boolean on,
                           boolean cyan, PillSwitch.Listener listener) {
        TextView captionView = caption(cap);
        PillSwitch toggle = new PillSwitch(activity, cyan);
        toggle.setOn(on);
        toggle.setOnChanged(listener);
        if ("cast".equals(icon)) streamSwitch = toggle;
        if ("sun".equals(icon) && title.contains("推流")) lightStreamSwitch = toggle;
        if ("home".equals(icon)) {
            haSwitch = toggle;
            haHost = captionView;
        }
        if ("推送音频到 HA".equals(title)) {
            haAudioSwitch = toggle;
            haAudioCaption = captionView;
        }
        if ("bell".equals(icon)) haNotifySwitch = toggle;
        return labeledRow(icon, ink, well, title, captionView, toggle);
    }

    private View valueRow(String icon, int ink, int well, String title, String cap, String value,
                          View.OnClickListener click) {
        TextView valueView = text(value, 14, color(R.color.on_surface_variant), false);
        valueView.setCompoundDrawables(null, null,
                Glyph.drawable(activity, "chev", 18, color(R.color.on_surface_variant), 2f), null);
        if ("aspect".equals(icon)) streamResValue = valueView;
        LinearLayout row = (LinearLayout) labeledRow(icon, ink, well, title, caption(cap), valueView);
        row.setOnClickListener(click);
        return row;
    }

    private View labeledRow(String icon, int ink, int well, String title, TextView captionView, View trailing) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        View badge = Glyph.well(activity, icon, color(well), color(ink));
        row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, dp(8), 0);
        text.addView(this.text(title, 16, color(R.color.on_surface), true));
        if (captionView != null) text.addView(captionView);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    private View summaryBox() {
        LinearLayout box = column();
        box.setBackground(round(color(R.color.surface_2), 16));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private void pill(Motion.StatusPill view, String label, int background, boolean breath) {
        int dot = label.startsWith("录制") ? Motion.RED
                : label.startsWith("推流已暂停") ? color(R.color.amber)
                : label.startsWith("推流") ? color(R.color.secondary)
                : color(R.color.on_surface_variant);
        view.show(label, color(background), dot, breath);
    }

    private Motion.StatusPill pillView() {
        Motion.StatusPill view = new Motion.StatusPill(activity);
        view.setMinimumHeight(dp(30));
        return view;
    }

    private TextView overlayText() {
        TextView view = text("", 11, Color.WHITE, false);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private LinearLayout segment() {
        LinearLayout seg = row();
        seg.setBackground(round(color(R.color.surface_2), 14));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        return seg;
    }

    private TextView segmentButton(String label, boolean on, View.OnClickListener click) {
        return segmentButton(null, label, on, click);
    }

    private TextView segmentButton(String icon, String label, boolean on, View.OnClickListener click) {
        TextView button = text(label, 14, color(on ? R.color.on_surface : R.color.on_surface_variant), on);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(34));
        button.setBackground(on ? round(color(R.color.surface), 11) : null);
        if (icon != null) {
            button.setCompoundDrawables(Glyph.drawable(activity, icon, 16,
                    color(on ? R.color.on_surface : R.color.on_surface_variant), 2f), null, null, null);
            button.setCompoundDrawablePadding(dp(4));
        }
        button.setOnClickListener(click);
        return button;
    }

    private LinearLayout.LayoutParams segmentLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private ScrollView scroll() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(color(R.color.background));
        return scroll;
    }

    private LinearLayout page() {
        LinearLayout page = column();
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        return page;
    }

    private LinearLayout card() {
        return new SurfaceCard(activity);
    }

    /** Popup and other surfaces that keep a stroke without the page-card shadow. */
    private LinearLayout plainCard() {
        LinearLayout card = column();
        GradientDrawable shape = round(color(R.color.surface), 20);
        shape.setStroke(dp(1), color(R.color.outline));
        card.setBackground(shape);
        return card;
    }

    /** Clips the camera picture to the same 20dp radius as the preview stroke. */
    private FrameLayout roundedClip() {
        FrameLayout clip = new FrameLayout(activity) {
            private final Path path = new Path();

            @Override protected void dispatchDraw(Canvas canvas) {
                float radius = 20f * getResources().getDisplayMetrics().density;
                path.reset();
                path.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, Path.Direction.CW);
                int save = canvas.save();
                canvas.clipPath(path);
                super.dispatchDraw(canvas);
                canvas.restoreToCount(save);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clip.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int width = view.getWidth();
                    int height = view.getHeight();
                    if (width <= 0 || height <= 0) return;
                    outline.setRoundRect(0, 0, width, height,
                            20f * view.getResources().getDisplayMetrics().density);
                }
            });
            clip.setClipToOutline(true);
        }
        return clip;
    }

    private TextView section(String label) {
        TextView view = text(label, 20, color(R.color.on_surface), true);
        view.setPadding(dp(4), dp(20), dp(4), dp(8));
        return view;
    }

    private TextView caption(String value) {
        return text(value, 13, color(R.color.on_surface_variant), false);
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(medium ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setClipChildren(false);
        return layout;
    }

    /** 1dp rule between list rows, inset to the title (16 + 40 + 14). */
    private View hairline() {
        View line = new View(activity);
        line.setBackgroundColor(color(R.color.outline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(70);
        lp.rightMargin = dp(16);
        line.setLayoutParams(lp);
        return line;
    }

    private View gap(View child, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        child.setLayoutParams(lp);
        return child;
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(fill);
        return shape;
    }

    private void copy(String value, String toast) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("afterglow", value));
        Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show();
    }

    private int shownBrightness() {
        if (ScreenSnapshot.brightness >= 0) return ScreenSnapshot.brightness;
        return LightPolicy.SENSOR.equals(Prefs.lightMethod())
                ? CameraStreamService.sensorBrightness : CameraStreamService.frameBrightness;
    }

    private String freeLabel() {
        if (ScreenSnapshot.freeSpace != null) return ScreenSnapshot.freeSpace;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long bytes = fs.getAvailableBytes();
            return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024d * 1024d));
        } catch (Throwable failure) {
            return "—";
        }
    }

    private static int triggerCount() {
        return (Prefs.motionEnabled() ? 1 : 0) + (Prefs.soundSelected() ? 1 : 0)
                + (Prefs.getBool(Prefs.KEY_SCHEDULE, false) ? 1 : 0)
                + (Prefs.recordLightEnabled() ? 1 : 0);
    }

    private static boolean frontLens() {
        String key = Prefs.cameraKey();
        return key != null && key.contains("front");
    }

    private static long recordElapsed() {
        if (CameraStreamService.recordingSinceMs <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - CameraStreamService.recordingSinceMs);
    }

    private static String hms(long ms) {
        long seconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }

    private String safeId() {
        try {
            String id = Prefs.deviceId();
            return id.length() >= 8 ? id.substring(0, 8) : id;
        } catch (Throwable failure) {
            return "local";
        }
    }

    private int color(int id) { return activity.getResources().getColor(id); }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static final class PillSwitch extends View {
        interface Listener { void onChanged(boolean on); }
        private boolean on;
        private boolean cyan = true;
        private Listener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PillSwitch(Context context, boolean cyan) {
            super(context);
            this.cyan = cyan;
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    on = !on;
                    invalidate();
                    if (listener != null) listener.onChanged(on);
                }
            });
        }

        void setOnChanged(Listener listener) { this.listener = listener; }
        void setOn(boolean value) { on = value; invalidate(); }
        boolean isOn() { return on; }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float density = getResources().getDisplayMetrics().density;
            setMeasuredDimension(Math.round(52 * density), Math.round(32 * density));
        }

        @Override protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? getResources().getColor(cyan ? R.color.secondary : R.color.primary)
                    : getResources().getColor(R.color.surface_3));
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), 16 * d, 16 * d, paint);
            float thumb = (on ? 24 : 16) * d;
            float cx = on ? getWidth() - 6 * d - thumb / 2f : 6 * d + thumb / 2f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, getHeight() / 2f, thumb / 2f, paint);
        }
    }

    static final class LogoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LogoView(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            // 40dp mark. Gradient stops are fractions of the farthest corner,
            // so the light spot stays small and the orange disc is not a ring.
            // Outside that: a 25% orange rim, then 0 0 18px rgba(255,122,61,.45).
            float outer = 20f * density;
            float farthest = outer * (float) Math.sqrt(2.0);
            float peach = 0.22f * farthest;
            float solid = 0.46f * farthest;
            RectF box = new RectF(cx - outer, cy - outer, cx + outer, cy + outer);
            Motion.discGlow(canvas, box, outer, Motion.ORANGE, 18f * density, 0.45f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x40FF7A3D);
            canvas.drawCircle(cx, cy, outer, paint);
            paint.setColor(Motion.ORANGE);
            canvas.drawCircle(cx, cy, solid, paint);
            paint.setColor(0xFFFFD2B5);
            canvas.drawCircle(cx, cy, peach, paint);
        }
    }

    static final class RoomScene extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RoomScene(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(0xFFC4B5A5);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(0xFFE7D7C4);
            canvas.drawRect(getWidth() * 0.18f, getHeight() * 0.16f, getWidth() * 0.62f, getHeight() * 0.62f, paint);
            paint.setColor(0xFF8C7468);
            float sofa = getHeight() * 0.58f;
            canvas.drawRoundRect(new RectF(getWidth() * 0.42f, sofa, getWidth() * 0.88f, getHeight() * 0.82f),
                    18, 18, paint);
            paint.setColor(0xFF4E7350);
            Path plant = new Path();
            plant.moveTo(getWidth() * 0.12f, getHeight() * 0.7f);
            plant.quadTo(getWidth() * 0.08f, getHeight() * 0.4f, getWidth() * 0.2f, getHeight() * 0.48f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getWidth() * 0.03f);
            canvas.drawPath(plant, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * Surface card. Light theme keeps the handoff shadow inside extra padding and
     * pulls that padding back with negative margins so the face stays put.
     * Dark theme is the stroked surface only.
     */
    static class SurfaceCard extends LinearLayout {
        private final int shadow;
        private final float radius;
        private final int stroke;
        private final int fill;
        private final int outline;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int faceLeft, faceTop, faceRight, faceBottom;
        private boolean faceKnown;

        SurfaceCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClipChildren(false);
            float density = context.getResources().getDisplayMetrics().density;
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            shadow = night ? 0 : Math.round(12f * density);
            radius = 20f * density;
            stroke = Math.max(1, Math.round(density));
            fill = context.getResources().getColor(R.color.surface);
            outline = context.getResources().getColor(R.color.outline);
            super.setPadding(shadow, shadow, shadow, shadow);
        }

        @Override public void setPadding(int left, int top, int right, int bottom) {
            super.setPadding(left + shadow, top + shadow, right + shadow, bottom + shadow);
        }

        @Override public void setMinimumHeight(int minHeight) {
            super.setMinimumHeight(minHeight + shadow * 2);
        }

        @Override public void setLayoutParams(ViewGroup.LayoutParams params) {
            if (shadow > 0 && params instanceof MarginLayoutParams) {
                MarginLayoutParams margin = (MarginLayoutParams) params;
                boolean already = faceKnown
                        && margin.leftMargin == faceLeft - shadow
                        && margin.topMargin == faceTop - shadow
                        && margin.rightMargin == faceRight - shadow
                        && margin.bottomMargin == faceBottom - shadow;
                if (!already) {
                    faceLeft = margin.leftMargin;
                    faceTop = margin.topMargin;
                    faceRight = margin.rightMargin;
                    faceBottom = margin.bottomMargin;
                    faceKnown = true;
                    margin.leftMargin = faceLeft - shadow;
                    margin.topMargin = faceTop - shadow;
                    margin.rightMargin = faceRight - shadow;
                    margin.bottomMargin = faceBottom - shadow;
                }
            }
            super.setLayoutParams(params);
        }

        @Override protected void onDraw(Canvas canvas) {
            RectF face = new RectF(shadow, shadow, getWidth() - shadow, getHeight() - shadow);
            if (face.width() <= 0f || face.height() <= 0f) {
                super.onDraw(canvas);
                return;
            }
            if (shadow > 0) {
                Motion.lightCardShadow(canvas, face, radius, getResources().getDisplayMetrics().density);
            }
            float inset = stroke / 2f;
            RectF box = new RectF(face.left + inset, face.top + inset, face.right - inset, face.bottom - inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(box, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(outline);
            canvas.drawRoundRect(box, radius, radius, paint);
        }
    }
}
