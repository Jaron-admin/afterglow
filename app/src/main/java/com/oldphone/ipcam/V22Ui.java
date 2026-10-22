package com.oldphone.ipcam;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    final MainActivity activity;
    private View home;
    private View stream;
    private ScrollView streamScroll;
    private int streamPushTop;
    private boolean resMenu;
    private boolean wantStream;
    private ImageView previewImage;
    private ImageView miniImage;
    private View previewScene;
    private View miniScene;
    private TextView deviceLine;
    private TextView previewClock;
    private TextView resChip;
    private View resMenuView;
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
    private TextView addressBlock;
    private PillSwitch streamSwitch;
    private PillSwitch lightStreamSwitch;
    private TextView lightSummary;
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
        String shot = activity.getIntent() == null ? null
                : activity.getIntent().getStringExtra(MainActivity.EXTRA_PAGE_SHOT);
        resMenu = "home-res".equals(shot);
        wantStream = "stream".equals(shot) || "stream-push".equals(shot)
                || (activity.getIntent() != null && activity.getIntent().getBooleanExtra(MainActivity.EXTRA_STREAM_PAGE, false));
        home = buildHome();
        stream = buildStream();
        content.addView(home, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(stream, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showStream(wantStream);
        if ("stream-push".equals(shot)) {
            stream.post(new Runnable() {
                @Override public void run() { streamScroll.scrollTo(0, Math.max(0, streamPushTop - dp(8))); }
            });
        }
    }

    void showStream(boolean show) {
        wantStream = show;
        if (home != null) home.setVisibility(show ? View.GONE : View.VISIBLE);
        if (stream != null) stream.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    boolean showingStream() { return wantStream; }

    void setPreview(Bitmap bitmap) {
        if (bitmap == null) return;
        if (previewImage != null) {
            previewImage.setImageBitmap(bitmap);
            previewImage.setVisibility(View.VISIBLE);
            if (previewScene != null) previewScene.setVisibility(View.GONE);
        }
        if (miniImage != null) {
            miniImage.setImageBitmap(bitmap);
            miniImage.setVisibility(View.VISIBLE);
            if (miniScene != null) miniScene.setVisibility(View.GONE);
        }
    }

    void refresh() {
        if (home == null) return;
        boolean running = CameraStreamService.running;
        boolean streaming = StreamControls.streaming();
        int brightness = shownBrightness();
        boolean paused = streaming && LightPolicy.pauseStream(Prefs.lightMethod(),
                Prefs.streamLightEnabled(), brightness, Prefs.lightThreshold());
        boolean recording = CameraStreamService.recordingNow;
        boolean front = frontLens();
        String res = StreamPresets.label(Prefs.outputWidth(), Prefs.outputHeight());
        deviceLine.setText(deviceCaption(res));
        if (ScreenSnapshot.preview != null) setPreview(ScreenSnapshot.preview);
        previewClock.setText(ScreenSnapshot.clock != null ? ScreenSnapshot.clock
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
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
        resChip.setCompoundDrawables(Prefs.microphoneEnabled()
                ? Glyph.drawable(activity, "mic", 12, Color.WHITE, 2.4f) : null, null, null,
                Glyph.drawable(activity, "down", 14, resMenu ? color(R.color.primary) : Color.WHITE, 2.4f));
        resChip.setCompoundDrawablePadding(dp(4));
        resChip.setTextColor(resMenu ? color(R.color.primary) : Color.WHITE);
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
        refreshAddresses(streaming);
        suppress = true;
        if (streamSwitch != null) streamSwitch.setOn(streaming);
        if (lightStreamSwitch != null) lightStreamSwitch.setOn(Prefs.streamLightEnabled());
        if (haSwitch != null) haSwitch.setOn(Prefs.haEnabled());
        if (haAudioSwitch != null) haAudioSwitch.setOn(Prefs.haAudioEnabled());
        if (haNotifySwitch != null) haNotifySwitch.setOn(Prefs.haNotifyEnabled());
        suppress = false;
        if (haHost != null) haHost.setText(Prefs.haEnabled() ? "● 已连接 · homeassistant.local" : "未接入");
        if (haAudioCaption != null) haAudioCaption.setText(Prefs.haAudioEnabled()
                ? "HA 通过 RTSP 接收声音" : "仅推送画面");
        if (haBody != null) haBody.setAlpha(Prefs.haEnabled() ? 1f : 0.45f);
        if (lightSummary != null) lightSummary.setText(lightSummaryText(brightness));
        if (lightSummaryBox != null) lightSummaryBox.setAlpha(Prefs.streamLightEnabled() ? 1f : 0.5f);
        if (entityId != null) entityId.setText("camera.afterglow_" + safeId());
        paintSegments(home);
        paintSegments(stream);
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
        if ("后置镜头".equals(text) || "后置".equals(text)) on = !frontLens();
        else if ("前置镜头".equals(text) || "前置".equals(text)) on = frontLens();
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
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(header());
        FrameLayout preview = preview(false);
        page.addView(preview);
        page.addView(lensSegment());
        page.addView(stats());
        View streamControl = bigButton();
        streamButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22ToggleStream(); }
        });
        page.addView(streamControl);
        pauseNote = caption("");
        pauseNote.setGravity(Gravity.CENTER);
        page.addView(gap(pauseNote, 0, 8, 0, 0));
        page.addView(gap(soundCard(), 0, 12, 0, 0));
        page.addView(section("功能"));
        page.addView(entries());
        return scroll;
    }

    private View buildStream() {
        streamScroll = scroll();
        LinearLayout page = page();
        streamScroll.addView(page);
        page.addView(topBar("画面与推流", "画面参数、局域网推流与 Home Assistant", true));
        page.addView(preview(true));
        page.addView(section("画面"));
        LinearLayout picture = card();
        picture.addView(valueRow("aspect", R.color.ink_orange, R.color.tonal_orange, "推流分辨率",
                "主界面预览右下角也能切", StreamPresets.label(Prefs.outputWidth(), Prefs.outputHeight()),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { cycleResolution(); }
                }));
        picture.addView(lensRow());
        picture.addView(rotationBlock());
        picture.addView(switchRow("text", R.color.on_surface_variant, R.color.tonal_grey, "时间水印",
                "在画面左下角显示日期时间", Prefs.watermarkEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        Prefs.setBool(Prefs.KEY_WATERMARK, on);
                        refresh();
                    }
                }));
        page.addView(picture);
        TextView pushTitle = section("推流");
        page.addView(pushTitle);
        streamPushTop = 0;
        pushTitle.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                streamPushTop = v.getTop();
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
        addressBlock = caption("");
        push.addView(addressBlock);
        lightStreamSwitch = null;
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
        lightSummaryBox = summaryBox();
        lightSummary = caption("");
        ((LinearLayout) lightSummaryBox).addView(lightSummary);
        TextView go = text("去录制设置调整 ›", 14, color(R.color.ink_amber), true);
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22OpenSettings(true); }
        });
        ((LinearLayout) lightSummaryBox).addView(gap(go, 0, 8, 0, 0));
        push.addView(lightSummaryBox);
        page.addView(push);
        page.addView(section("Home Assistant 接入"));
        LinearLayout ha = card();
        haSwitch = null;
        ha.addView(switchRow("home", R.color.ink_purple, R.color.tonal_purple, "接入 Home Assistant",
                "", Prefs.haEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_HA, on);
                        refresh();
                    }
                }));
        haHost = caption("");
        ha.addView(haHost);
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
        ((LinearLayout) haBody).addView(switchRow("bell", R.color.ink_orange, R.color.tonal_orange, "触发事件通知",
                "画面变动 / 声音触发时发送事件", Prefs.haNotifyEnabled(), false, new PillSwitch.Listener() {
                    @Override public void onChanged(boolean on) {
                        if (suppress) return;
                        Prefs.setBool(Prefs.KEY_HA_NOTIFY, on);
                    }
                }));
        entityId = caption("");
        TextView entityRow = text("实体 ID", 16, color(R.color.on_surface), true);
        LinearLayout entity = row();
        entity.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(entityId.getText().toString(), "已复制实体 ID"); }
        });
        entity.addView(entityRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        entity.addView(entityId);
        ((LinearLayout) haBody).addView(entity);
        ha.addView(haBody);
        page.addView(ha);
        return streamScroll;
    }

    private View header() {
        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, dp(16));
        row.setGravity(Gravity.CENTER_VERTICAL);
        View logo = new LogoView(activity);
        row.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
        RoomScene scene = new RoomScene(activity);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        frame.addView(scene, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (mini) {
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
            frame.setClipChildren(false);
            frame.setClipToPadding(false);
            FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int glowPad = dp(40);
            glowLp.setMargins(-glowPad, -glowPad, -glowPad, -glowPad);
            frame.addView(glow, glowLp);
            leftPill = pillView();
            rightPill = pillView();
            previewClock = overlayText();
            resChip = new TextView(activity);
            resChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            resChip.setTypeface(Typeface.DEFAULT_BOLD);
            resChip.setTextColor(Color.WHITE);
            resChip.setPadding(dp(10), 0, dp(8), 0);
            resChip.setGravity(Gravity.CENTER_VERTICAL);
            resChip.setBackground(round(0xCC1A1614, 14));
            resChip.setOnClickListener(new View.OnClickListener() {
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
            frame.addView(resChip, chip);
            resMenuView = resolutionMenu();
            frame.addView(resMenuView, new FrameLayout.LayoutParams(
                    dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frame.setLayoutParams(lp);
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int width = r - l;
                int height = width * 9 / 16;
                if (b - t != height && width > 0) {
                    v.getLayoutParams().height = height;
                    v.requestLayout();
                }
            }
        });
        return frame;
    }

    private View resolutionMenu() {
        LinearLayout menu = card();
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView title = text("推流分辨率", 14, color(R.color.on_surface), true);
        menu.addView(title);
        for (int i = 0; i < StreamPresets.SIZES.length; i++) {
            final int width = StreamPresets.SIZES[i][0];
            final int height = StreamPresets.SIZES[i][1];
            TextView item = text(StreamPresets.LABELS[i] + "    " + width + "×" + height,
                    14, color(R.color.on_surface), false);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setTag(width + "x" + height);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    resMenu = false;
                    activity.v22SetResolution(width, height);
                }
            });
            menu.addView(item);
        }
        TextView hint = caption("推流中切换会断开 1–2 秒后自动重连；正在录的一段会结束");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
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
            if (child.getTag() == null) continue;
            boolean on = current.equals(child.getTag());
            child.setBackground(on ? round(color(R.color.tonal_orange), 10) : null);
        }
    }

    private View lensSegment() {
        LinearLayout seg = segment();
        seg.addView(segmentButton("camera", "后置镜头", !frontLens(), new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(false); }
        }), segmentLp());
        seg.addView(segmentButton("person", "前置镜头", frontLens(), new View.OnClickListener() {
            @Override public void onClick(View v) { activity.v22SetLens(true); }
        }), segmentLp());
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
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setGravity(Gravity.CENTER);
        label.setGravity(Gravity.CENTER);
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

    private View entry(String icon, String title, TextView summary, int well, int ink, View.OnClickListener click) {
        LinearLayout card = card();
        card.setMinimumHeight(dp(118));
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
        if (addressBlock == null) return;
        String ip = NetUtils.localIp();
        String rtsp = "rtsp://" + ip + ":8554/live";
        String mjpeg = "http://" + ip + ":8080/stream.mjpeg";
        addressBlock.setText("两路同时推，按播放器选一个地址\nRTSP  " + rtsp + "\nMJPEG  " + mjpeg
                + "\nMJPEG 不带声音，RTSP 和录像有声音");
        addressBlock.setAlpha(streaming ? 1f : 0.45f);
        addressBlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (StreamControls.streaming()) copy(rtspLine(), "已复制推流地址");
            }
        });
    }

    private String rtspLine() {
        return "rtsp://" + NetUtils.localIp() + ":8554/live";
    }

    private String lightSummaryText(int brightness) {
        boolean met = LightPolicy.satisfied(brightness, Prefs.lightThreshold());
        String method = LightPolicy.SENSOR.equals(Prefs.lightMethod()) ? "光线传感器" : "画面亮度";
        String rule = LightPolicy.SENSOR.equals(Prefs.lightMethod())
                ? "光线传感器模式：亮度不够时推流和录制都暂停"
                : "画面亮度模式：亮度不够时推流继续，只暂停录制";
        String now = brightness < 0 ? "—" : brightness + "%";
        return "共用阈值    够亮才触发 · " + Prefs.lightThreshold() + "%\n"
                + "感光方式    " + method + "\n"
                + "当前亮度    " + now + "  " + (met ? "已满足" : "未满足") + "\n"
                + rule;
    }

    private void cycleResolution() {
        int index = StreamPresets.indexOf(Prefs.outputWidth(), Prefs.outputHeight());
        int next = (index + 1) % StreamPresets.SIZES.length;
        if (index < 0) next = 1;
        activity.v22SetResolution(StreamPresets.SIZES[next][0], StreamPresets.SIZES[next][1]);
    }

    private View lensRow() {
        LinearLayout row = row();
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
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
        LinearLayout block = column();
        if (back) {
            ImageView button = new ImageView(activity);
            button.setImageDrawable(Glyph.drawable(activity, "back", 24, color(R.color.on_surface), 2f));
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showStream(false);
                    refresh();
                }
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
        int extra = dp(36);
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setClipChildren(false);
        streamHalo = new Motion.Halo(activity);
        streamHalo.inset = extra;
        streamButton = text("开始推流", 18, Color.WHITE, true);
        streamButton.setGravity(Gravity.CENTER);
        streamButton.setMinimumHeight(dp(60));
        streamButton.setTypeface(Typeface.DEFAULT_BOLD);
        wrap.addView(streamHalo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        buttonLp.setMargins(extra, extra, extra, extra);
        wrap.addView(streamButton, buttonLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60) + extra * 2);
        lp.leftMargin = -extra;
        lp.rightMargin = -extra;
        lp.topMargin = dp(18) - extra;
        lp.bottomMargin = -extra;
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private void styleStreamButton(boolean streaming, boolean paused) {
        streamButton.setText(streaming ? "停止推流" : "开始推流");
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(30));
        Motion.Mark mark = streamButton.getTag() instanceof Motion.Mark
                ? (Motion.Mark) streamButton.getTag() : new Motion.Mark(activity);
        streamButton.setTag(mark);
        if (!streaming) {
            shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            shape.setColors(new int[]{color(R.color.primary_button_top), color(R.color.primary_dark)});
            streamButton.setTextColor(Color.WHITE);
            mark.color = Color.WHITE;
            mark.square = false;
            streamHalo.mode = Motion.Halo.SHADOW;
        } else {
            shape.setColor(color(R.color.tonal_orange));
            streamButton.setTextColor(color(R.color.ink_orange));
            mark.color = color(R.color.ink_orange);
            mark.square = true;
            streamHalo.mode = paused || Motion.reduced(activity) ? Motion.Halo.STATIC : Motion.Halo.ORANGE;
        }
        mark.invalidate();
        streamButton.setCompoundDrawablesWithIntrinsicBounds(markBitmap(mark), null, null, null);
        streamButton.setCompoundDrawablePadding(dp(8));
        streamButton.setBackground(shape);
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
        if ("home".equals(icon)) haSwitch = toggle;
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
        scroll.setBackgroundColor(color(R.color.background));
        return scroll;
    }

    private LinearLayout page() {
        LinearLayout page = column();
        page.setClipChildren(false);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        return page;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        GradientDrawable shape = round(color(R.color.surface), 20);
        shape.setStroke(dp(1), color(R.color.outline));
        card.setBackground(shape);
        return card;
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
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
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
            float c = getWidth() / 2f;
            paint.setColor(0x40FF7A3D);
            canvas.drawCircle(c, c, c, paint);
            paint.setColor(0xFFFF7A3D);
            canvas.drawCircle(c, c, c * 0.46f, paint);
            paint.setColor(0xFFFFD2B5);
            canvas.drawCircle(c, c, c * 0.22f, paint);
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
}
