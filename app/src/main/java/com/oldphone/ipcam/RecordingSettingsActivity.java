package com.oldphone.ipcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.os.Environment;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Recording triggers, light and picture settings. Values are stored as they change. */
public final class RecordingSettingsActivity extends Activity {
    public static final String EXTRA_OPEN_LIGHT = "open_light";
    public static final String EXTRA_PAGE_SHOT = "page_shot";
    private static final int[] SEGMENTS = {60, 180, 300, 600, 1800};
    private ScrollView scroll;
    private LinearLayout page;
    private View lightAnchor;
    private View advancedAnchor;
    private boolean advanced;
    private boolean suppress;
    /** Recording state the visible card was built from; onResume rebuilds on a mismatch. */
    private boolean renderedRecording;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.init(this);
        ActivityOrientation.apply(this);
        String shot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PAGE_SHOT);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_LIGHT, false)) {
            Prefs.setBool(Prefs.KEY_RECORD_LIGHT, true);
        }
        if ("settings-light".equals(shot)) Prefs.setBool(Prefs.KEY_RECORD_LIGHT, true);
        if ("settings-recording".equals(shot)) {
            Prefs.setBool(Prefs.KEY_MATCH_ALL, true);
            Prefs.setBool(Prefs.KEY_RECORD_LIGHT, true);
            Prefs.setStr(Prefs.KEY_LIGHT_METHOD, LightPolicy.SENSOR);
            CameraStreamService.recordingNow = true;
            if (CameraStreamService.recordingSinceMs == 0) {
                CameraStreamService.recordingSinceMs = System.currentTimeMillis() - (12 * 60 + 34) * 1000L;
            }
        }
        advanced = "settings-advanced".equals(shot) || (state != null && state.getBoolean("advanced"));
        ShotChrome.beforeContent(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(color(R.color.background));
        page = column();
        page.setPadding(dp(16), dp(8), dp(16), dp(28));
        scroll.addView(page);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        page.setClipChildren(false);
        setContentView(scroll);
        ShotChrome.afterContent(this);
        rebuild();
        if (Prefs.recordLightEnabled() && ("settings-light".equals(shot)
                || (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_LIGHT, false)))) {
            scrollWhenReady(true, dp(150));
        }
        if ("settings-advanced".equals(shot)) scrollWhenReady(false, dp(230));
    }

    private void scrollWhenReady(final boolean light, final int above) {
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        View anchor = light ? lightAnchor : advancedAnchor;
                        if (anchor == null) return;
                        int top = offsetIn(page, anchor);
                        if (top <= 0) return;
                        scroll.scrollTo(0, Math.max(0, top - above));
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });
    }

    private static int offsetIn(View ancestor, View child) {
        int y = 0;
        View view = child;
        while (view != null && view != ancestor) {
            y += view.getTop();
            if (!(view.getParent() instanceof View)) break;
            view = (View) view.getParent();
        }
        return y;
    }

    @Override protected void onResume() {
        super.onResume();
        // Recording can also be started or stopped from the main screen while this page
        // sits in the back stack; rebuild only when the state actually moved.
        if (renderedRecording != CameraStreamService.recordingNow) rebuild();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("advanced", advanced);
    }

    private void rebuild() {
        renderedRecording = CameraStreamService.recordingNow;
        page.removeAllViews();
        page.addView(top());
        page.addView(recordCard());
        page.addView(section("常用"));
        page.addView(commonCard());
        page.addView(gap(advancedCard(), 0, 12, 0, 0));
        TextView note = caption("建议接通电源并关闭省电优化，避免录制被系统中断。");
        page.addView(gap(note, 6, 14, 6, 0));
    }

    private View top() {
        LinearLayout block = column();
        ImageView back = new ImageView(this);
        back.setImageDrawable(Glyph.drawable(this, "back", 24, color(R.color.on_surface), 2f));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        block.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("录制设置", 28, color(R.color.on_surface), true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        block.addView(title);
        block.addView(caption("决定什么时候录、录成什么样"));
        return block;
    }

    private View recordCard() {
        boolean recording = CameraStreamService.recordingNow;
        LinearLayout card = card();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout statusRow = row();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        Motion.BreathDot dot = new Motion.BreathDot(this);
        dot.color = recording ? Motion.RED : color(R.color.on_surface_variant);
        dot.breathe = recording && !Motion.reduced(this);
        statusRow.setClipChildren(false);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout statusText = column();
        statusText.setPadding(dp(8), 0, 0, 0);
        TextView status = text(recording ? "录制中 · " + elapsed() : "未在录制", 16,
                color(recording ? R.color.ink_red : R.color.on_surface), true);
        statusText.addView(status);
        statusText.addView(caption(recording
                ? "满足触发条件时自动分段保存 · 今天已存 " + today() + " 段"
                : (CameraStreamService.running ? "开始后，按下方触发条件自动录像"
                        : "不用开推流，本地直接录；按下方触发条件自动分段")));
        statusRow.addView(statusText);
        card.addView(statusRow);
        TextView button = text(recording ? "停止录制" : "开始录制", 17,
                recording ? Color.WHITE : color(R.color.ink_red), true);
        Motion.Mark mark = new Motion.Mark(this);
        mark.square = recording;
        mark.color = recording ? Color.WHITE : color(R.color.ink_red);
        LinearLayout group = row();
        group.setGravity(Gravity.CENTER);
        group.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
        button.setPadding(dp(8), 0, 0, 0);
        group.addView(button);
        FrameLayout face = new FrameLayout(this);
        face.addView(group, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(26));
        if (recording) {
            shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            shape.setColors(new int[]{color(R.color.recording_top), color(R.color.danger)});
            button.setTextColor(Color.WHITE);
        } else {
            shape.setColor(color(R.color.tonal_red));
            button.setTextColor(color(R.color.ink_red));
        }
        face.setBackground(shape);
        int extra = dp(42);
        Motion.Halo halo = new Motion.Halo(this);
        halo.inset = extra;
        halo.mode = recording && !Motion.reduced(this) ? Motion.Halo.RED : Motion.Halo.NONE;
        face.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean start = !CameraStreamService.recordingNow;
                if (start && triggerCount() == 0 && Prefs.recordingMode() != 1) {
                    Toast.makeText(RecordingSettingsActivity.this, "请至少选择一个触发条件", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    StreamControls.setRecording(RecordingSettingsActivity.this, start);
                } catch (Throwable failure) {
                    Toast.makeText(RecordingSettingsActivity.this, "录制未能开始", Toast.LENGTH_LONG).show();
                    return;
                }
                // The service flips these on its own thread; without the local update the
                // card would rebuild from the old value and keep saying 开始录制 / 未在录制.
                CameraStreamService.recordingNow = start;
                CameraStreamService.recordingSinceMs = start
                        ? (CameraStreamService.recordingSinceMs == 0
                            ? System.currentTimeMillis() : CameraStreamService.recordingSinceMs)
                        : 0;
                Toast.makeText(RecordingSettingsActivity.this,
                        start ? "已开始录制" : "录制已停止，片段已保存", Toast.LENGTH_SHORT).show();
                rebuild();
            }
        });
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        card.setClipChildren(false);
        wrap.addView(halo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonLp.setMargins(extra, extra, extra, extra);
        wrap.addView(face, buttonLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52) + extra * 2);
        lp.topMargin = dp(14) - extra;
        lp.leftMargin = -extra;
        lp.rightMargin = -extra;
        lp.bottomMargin = -extra;
        wrap.setLayoutParams(lp);
        card.addView(wrap);
        return card;
    }

    private android.graphics.drawable.Drawable markDrawable(Motion.Mark mark) {
        int size = dp(22);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        mark.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        mark.layout(0, 0, size, size);
        mark.draw(new android.graphics.Canvas(bitmap));
        android.graphics.drawable.BitmapDrawable drawable =
                new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    private View commonCard() {
        LinearLayout card = card();
        card.addView(settingHead("motion", R.color.ink_orange, R.color.tonal_orange,
                "触发方式", "可多选，并选择组合方式", null));
        FlowRow chips = new FlowRow(this, dp(6), dp(8));
        chips.setPadding(dp(16), 0, dp(16), 0);
        chips.addView(trigger("画面变动", Prefs.motionEnabled(), R.color.tonal_orange, R.color.ink_orange, new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(Prefs.KEY_MOTION, !Prefs.motionEnabled()); }
        }));
        chips.addView(trigger("声音", Prefs.soundSelected(), R.color.tonal_cyan, R.color.ink_cyan, new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(Prefs.KEY_SOUND, !Prefs.soundSelected()); }
        }));
        chips.addView(trigger("定时", Prefs.getBool(Prefs.KEY_SCHEDULE, false), R.color.tonal_purple, R.color.ink_purple, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggle(Prefs.KEY_SCHEDULE, !Prefs.getBool(Prefs.KEY_SCHEDULE, false));
            }
        }));
        chips.addView(trigger("光感", Prefs.recordLightEnabled(), R.color.tonal_amber, R.color.ink_amber, new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(Prefs.KEY_RECORD_LIGHT, !Prefs.recordLightEnabled()); }
        }));
        card.addView(chips);
        if (Prefs.getBool(Prefs.KEY_SCHEDULE, false)) {
            TextView window = caption("每天 " + clock(Prefs.getInt(Prefs.KEY_SCHEDULE_START, 22 * 60))
                    + " – " + clock(Prefs.getInt(Prefs.KEY_SCHEDULE_END, 7 * 60)) + " 持续录制");
            window.setTextColor(color(R.color.ink_purple));
            window.setPadding(dp(16), dp(8), dp(16), 0);
            card.addView(window);
        }
        card.addView(matchSegment());
        card.addView(explanation());
        if (Prefs.recordLightEnabled()) card.addView(lightPanel());
        card.addView(sensitivity());
        card.addView(hairline());
        card.addView(choiceRow("timer", R.color.ink_purple, R.color.tonal_purple,
                "分段时长", "长录像按此时长切分保存", segmentLabel(), new View.OnClickListener() {
            @Override public void onClick(View v) { pickSegment(); }
        }));
        boolean follow = !Prefs.hasRecordSize();
        card.addView(hairline());
        card.addView(choiceRow("aspect", R.color.ink_orange, R.color.tonal_orange,
                "录制分辨率", StreamPresets.recordCaption(follow),
                StreamPresets.recordLabel(follow, Prefs.recordWidth(), Prefs.recordHeight(),
                        Prefs.outputWidth(), Prefs.outputHeight()), new View.OnClickListener() {
                    @Override public void onClick(View v) { pickRecordSize(); }
                }));
        card.addView(hairline());
        card.addView(choiceRow("hd", R.color.ink_orange, R.color.tonal_orange,
                "画质", "码率越高越清晰，也越占空间",
                StreamPresets.qualityLabel(Prefs.getInt(Prefs.KEY_BITRATE, 2)), new View.OnClickListener() {
                    @Override public void onClick(View v) { pickQuality(); }
                }));
        return card;
    }

    private View matchSegment() {
        LinearLayout seg = row();
        seg.setBackground(round(color(R.color.surface_2), 14));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.topMargin = dp(12);
        seg.setLayoutParams(lp);
        seg.addView(segmentButton("满足任一", !Prefs.matchAll(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setBool(Prefs.KEY_MATCH_ALL, false);
                applyIfRunning();
                rebuild();
            }
        }), weight());
        seg.addView(segmentButton("全部满足", Prefs.matchAll(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setBool(Prefs.KEY_MATCH_ALL, true);
                applyIfRunning();
                rebuild();
            }
        }), weight());
        return seg;
    }

    private View explanation() {
        TriggerPolicy.Selection selection = new TriggerPolicy.Selection(
                Prefs.motionEnabled(), Prefs.soundSelected(),
                Prefs.getBool(Prefs.KEY_SCHEDULE, false), Prefs.recordLightEnabled(), Prefs.matchAll());
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), dp(8), dp(16), dp(8));
        ImageView info = new ImageView(this);
        info.setImageDrawable(Glyph.drawable(this, "info", 15, color(R.color.on_surface_variant), 2f));
        line.addView(info, new LinearLayout.LayoutParams(dp(15), dp(15)));
        TextView words = caption("");
        words.setText(emphasis(TriggerPolicy.explanation(selection)));
        words.setPadding(dp(6), 0, 0, 0);
        line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return line;
    }

    private CharSequence emphasis(String raw) {
        SpannableString span = new SpannableString(raw);
        int any = raw.indexOf("任意一个");
        if (any >= 0) ink(span, any, any + "任意一个".length());
        int together = raw.indexOf("个条件同时");
        if (together > 0) {
            int start = together;
            while (start > 0 && (Character.isDigit(raw.charAt(start - 1)) || raw.charAt(start - 1) == ' ')) start--;
            ink(span, start, together + "个条件同时".length());
        }
        return span;
    }

    private void ink(SpannableString span, int start, int end) {
        span.setSpan(new ForegroundColorSpan(color(R.color.ink_orange)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private View lightPanel() {
        LinearLayout block = column();
        lightAnchor = block;
        V22Ui.PillSwitch toggle = new V22Ui.PillSwitch(this, false);
        toggle.setOn(true);
        toggle.setOnChanged(new V22Ui.PillSwitch.Listener() {
            @Override public void onChanged(boolean on) {
                if (suppress || on) return;
                Prefs.setBool(Prefs.KEY_RECORD_LIGHT, false);
                applyIfRunning();
                rebuild();
            }
        });
        block.addView(settingHead("sun", R.color.ink_amber, R.color.tonal_amber,
                "光感控制录制", "够亮才触发 · 阈值 " + Prefs.lightThreshold() + "%", toggle));
        TextView method = text("感光方式", 13, color(R.color.on_surface), true);
        method.setPadding(dp(16), dp(4), dp(16), dp(6));
        block.addView(method);
        LinearLayout seg = row();
        seg.setBackground(round(color(R.color.surface_2), 14));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        seg.setLayoutParams(lp);
        seg.addView(segmentButton("画面亮度", LightPolicy.FRAME.equals(Prefs.lightMethod()), new View.OnClickListener() {
            @Override public void onClick(View v) { setMethod(LightPolicy.FRAME); }
        }), weight());
        seg.addView(segmentButton("光线传感器", LightPolicy.SENSOR.equals(Prefs.lightMethod()), new View.OnClickListener() {
            @Override public void onClick(View v) { setMethod(LightPolicy.SENSOR); }
        }), weight());
        block.addView(seg);
        boolean sensor = LightPolicy.SENSOR.equals(Prefs.lightMethod());
        LinearLayout methodLine = row();
        methodLine.setGravity(Gravity.CENTER_VERTICAL);
        methodLine.setPadding(dp(16), dp(8), dp(16), 0);
        ImageView methodIcon = new ImageView(this);
        methodIcon.setImageDrawable(Glyph.drawable(this, sensor ? "sun" : "camera", 15,
                color(R.color.on_surface_variant), 2f));
        methodLine.addView(methodIcon, new LinearLayout.LayoutParams(dp(15), dp(15)));
        TextView cap = caption(sensor
                ? "用手机自带感光元件。亮度不够时推流和录制都暂停，可关相机省电。"
                : "按镜头画面计算。亮度不够时只暂停录制，推流继续，相机保持开启。");
        cap.setPadding(dp(6), 0, 0, 0);
        methodLine.addView(cap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        block.addView(methodLine);
        int brightness = brightness();
        boolean met = LightPolicy.satisfied(brightness, Prefs.lightThreshold());
        LinearLayout read = row();
        read.setGravity(Gravity.CENTER_VERTICAL);
        read.setPadding(dp(16), dp(12), dp(16), dp(4));
        ImageView sun = new ImageView(this);
        sun.setImageDrawable(Glyph.drawable(this, "sun", 16, color(R.color.on_surface_variant), 2f));
        read.addView(sun, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView readLabel = text("当前亮度 ", 14, color(R.color.on_surface_variant), false);
        readLabel.setPadding(dp(6), 0, 0, 0);
        read.addView(readLabel);
        read.addView(text(brightness < 0 ? "—" : brightness + "%", 14, color(R.color.on_surface), true));
        read.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView metChip = text(met ? "已满足" : "未满足", 12,
                color(met ? R.color.ink_cyan : R.color.on_surface_variant), true);
        metChip.setPadding(dp(8), dp(2), dp(8), dp(2));
        metChip.setBackground(round(color(met ? R.color.tonal_cyan : R.color.tonal_grey), 12));
        read.addView(metChip);
        block.addView(read);
        BrightnessBar slider = new BrightnessBar(this);
        suppress = true;
        slider.setThreshold(Prefs.lightThreshold());
        slider.setReading(brightness);
        suppress = false;
        slider.setListener(new BrightnessBar.Listener() {
            @Override public void onChanged(int threshold) {
                if (suppress) return;
                Prefs.setInt(Prefs.KEY_LIGHT_THRESHOLD, threshold);
            }
            @Override public void onFinished(int threshold) {
                Prefs.setInt(Prefs.KEY_LIGHT_THRESHOLD, threshold);
                applyIfRunning();
                rebuild();
            }
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.leftMargin = dp(16);
        sliderLp.rightMargin = dp(16);
        block.addView(slider, sliderLp);
        LinearLayout ticks = row();
        ticks.setPadding(dp(16), 0, dp(16), dp(4));
        ticks.addView(caption("暗 0%"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mark = caption("▮ 当前亮度");
        mark.setTextColor(color(R.color.ink_cyan));
        mark.setGravity(Gravity.CENTER);
        ticks.addView(mark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView bright = caption("亮 100%");
        bright.setGravity(Gravity.RIGHT);
        ticks.addView(bright, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        block.addView(ticks);
        String number = Prefs.lightThreshold() + "%";
        String raw = "画面亮于 " + number + " 时触发";
        SpannableString ruleText = new SpannableString(raw);
        int at = raw.indexOf(number);
        ruleText.setSpan(new ForegroundColorSpan(color(R.color.ink_amber)), at, at + number.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ruleText.setSpan(new StyleSpan(Typeface.BOLD), at, at + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView rule = caption("");
        rule.setText(ruleText);
        rule.setPadding(dp(16), 0, dp(16), dp(12));
        block.addView(rule);
        return block;
    }

    private static String sensitivityBand(int percent) {
        return percent >= 70 ? "高" : percent >= 40 ? "中" : "低";
    }

    private TextView tickLabel(String label, int gravity) {
        TextView view = caption(label);
        view.setGravity(gravity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        view.setLayoutParams(lp);
        return view;
    }

    private View sensitivity() {
        LinearLayout block = column();
        int percent = Prefs.sensitivityPercent();
        String band = sensitivityBand(percent);
        final TextView value = text(band + " · " + percent, 14, color(R.color.ink_orange), true);
        block.addView(settingHead("gauge", R.color.ink_cyan, R.color.tonal_cyan,
                "灵敏度", "越高越容易被小动作触发", value));
        LevelBar slider = new LevelBar(this);
        slider.setValue(percent);
        slider.setListener(new LevelBar.Listener() {
            @Override public void onChanged(int progress) {
                Prefs.setSensitivityPercent(progress);
                value.setText(sensitivityBand(progress) + " · " + progress);
            }
            @Override public void onFinished(int progress) {
                applyIfRunning();
                rebuild();
            }
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.leftMargin = dp(16);
        sliderLp.rightMargin = dp(16);
        block.addView(slider, sliderLp);
        LinearLayout ticks = row();
        ticks.setPadding(dp(16), 0, dp(16), dp(8));
        ticks.addView(tickLabel("低", Gravity.LEFT));
        ticks.addView(tickLabel("中", Gravity.CENTER));
        ticks.addView(tickLabel("高", Gravity.RIGHT));
        block.addView(ticks);
        return block;
    }

    private View advancedCard() {
        LinearLayout card = card();
        advancedAnchor = card;
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(Glyph.drawable(this, "down", 22, color(R.color.on_surface_variant), 2f));
        chevron.setRotation(advanced ? 180f : 0f);
        View head = settingHead("tune", R.color.on_surface_variant, R.color.tonal_grey,
                "高级", "自动清理、帧率、存储空间", chevron);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                advanced = !advanced;
                rebuild();
            }
        });
        card.addView(head);
        if (!advanced) return card;
        String used = ScreenSnapshot.usedSpace != null ? ScreenSnapshot.usedSpace : usedLabel();
        String free = ScreenSnapshot.freeSpace != null ? ScreenSnapshot.freeSpace : freeLabel();
        card.addView(rule(true));
        card.addView(settingHead("folder", R.color.ink_cyan, R.color.tonal_cyan,
                "存储空间", "已用 " + used + " · 剩余 " + free, null));
        View bar = new View(this);
        int percent = ScreenSnapshot.usedPercent >= 0 ? ScreenSnapshot.usedPercent : 62;
        GradientDrawable track = round(color(R.color.surface_3), 3);
        bar.setBackground(track);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        barLp.leftMargin = dp(16);
        barLp.rightMargin = dp(16);
        bar.setLayoutParams(barLp);
        card.addView(bar);
        boolean clean = Prefs.getBool(Prefs.KEY_LOOP, true);
        int days = Prefs.getInt(Prefs.KEY_RETENTION, 0);
        if (days <= 0) days = 7;
        V22Ui.PillSwitch cleanSwitch = new V22Ui.PillSwitch(this, false);
        cleanSwitch.setOn(clean);
        final int keepDays = days;
        cleanSwitch.setOnChanged(new V22Ui.PillSwitch.Listener() {
            @Override public void onChanged(boolean on) {
                Prefs.setBool(Prefs.KEY_LOOP, on);
                if (on && Prefs.getInt(Prefs.KEY_RETENTION, 0) <= 0) Prefs.setInt(Prefs.KEY_RETENTION, keepDays);
                rebuild();
            }
        });
        card.addView(hairline());
        card.addView(settingHead("trash", R.color.ink_red, R.color.tonal_red,
                "自动清理", clean ? "保留最近 " + keepDays + " 天，空间不足时删最旧的" : "已关闭，需手动删除", cleanSwitch));
        card.addView(hairline());
        card.addView(choiceRow("bolt", R.color.ink_orange, R.color.tonal_orange,
                "帧率", "越低越省电、省空间", fpsLabel(), new View.OnClickListener() {
            @Override public void onClick(View v) { pickFps(); }
        }));
        return card;
    }

    private void toggle(String key, boolean value) {
        Prefs.setBool(key, value);
        if (Prefs.recordingMode() == 1) Prefs.setInt(Prefs.KEY_RECORDING, 2);
        applyIfRunning();
        rebuild();
    }

    private void setMethod(String method) {
        Prefs.setStr(Prefs.KEY_LIGHT_METHOD, method);
        applyIfRunning();
        rebuild();
    }

    private void pickSegment() {
        String[] labels = new String[SEGMENTS.length];
        int selected = 2;
        int current = Prefs.getInt(Prefs.KEY_SEGMENT, 300);
        for (int i = 0; i < SEGMENTS.length; i++) {
            labels[i] = (SEGMENTS[i] / 60) + " 分钟";
            if (SEGMENTS[i] == current) selected = i;
        }
        choose("分段时长", labels, selected, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                Prefs.setInt(Prefs.KEY_SEGMENT, SEGMENTS[which]);
                applyIfRunning();
                rebuild();
            }
        });
    }

    private void pickRecordSize() {
        final String[] labels = {"跟随推流", "720p", "1080p", "1440p"};
        int selected = 0;
        if (Prefs.hasRecordSize()) {
            int index = StreamPresets.indexOf(Prefs.recordWidth(), Prefs.recordHeight());
            selected = index < 0 ? 0 : index + 1;
        }
        choose("录制分辨率", labels, selected, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 0) Prefs.setRecordSize(0, 0);
                else Prefs.setRecordSize(StreamPresets.SIZES[which - 1][0], StreamPresets.SIZES[which - 1][1]);
                applyIfRunning();
                rebuild();
            }
        });
    }

    private void pickQuality() {
        final String[] labels = {"标准", "高", "超高"};
        String current = StreamPresets.qualityLabel(Prefs.getInt(Prefs.KEY_BITRATE, 2));
        int selected = "标准".equals(current) ? 0 : "超高".equals(current) ? 2 : 1;
        choose("画质", labels, selected, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                Prefs.setInt(Prefs.KEY_BITRATE, StreamPresets.bitrateIndex(labels[which]));
                applyIfRunning();
                rebuild();
            }
        });
    }

    private void pickFps() {
        final int[] choices = {15, 24, 30};
        String[] labels = {"15 fps", "24 fps", "30 fps"};
        int current = Prefs.fps();
        int selected = current >= 30 ? 2 : current == 24 ? 1 : 0;
        choose("帧率", labels, selected, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                int fps = choices[which];
                int index = 2;
                for (int i = 0; i < Prefs.FPS_OPTIONS.length; i++) if (Prefs.FPS_OPTIONS[i] == fps) index = i;
                Prefs.setInt(Prefs.KEY_FPS, index);
                applyIfRunning();
                rebuild();
            }
        });
    }

    private void choose(String title, String[] labels, int selected, DialogInterface.OnClickListener click) {
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected, click).show();
    }

    private void applyIfRunning() {
        if (CameraStreamService.running) {
            try { StreamControls.reconfigure(this); }
            catch (Throwable ignored) { }
        }
    }

    /** 1dp rule between list rows, inset to the title (16 + 40 + 14). */
    private View hairline() { return rule(false); }

    private View rule(boolean full) {
        View line = new View(this);
        line.setBackgroundColor(color(R.color.outline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        if (!full) {
            lp.leftMargin = dp(70);
            lp.rightMargin = dp(16);
        }
        line.setLayoutParams(lp);
        return line;
    }

    private View choiceRow(String icon, int ink, int well, String title, String cap, String value,
                           View.OnClickListener click) {
        LinearLayout valueRow = row();
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        valueRow.addView(text(value, 14, color(R.color.on_surface_variant), false));
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(Glyph.drawable(this, "chev", 18, color(R.color.on_surface_variant), 2f));
        valueRow.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout line = (LinearLayout) settingHead(icon, ink, well, title, cap, valueRow);
        line.setOnClickListener(click);
        return line;
    }

    private View settingHead(String icon, int ink, int well, String title, String cap, View trailing) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.addView(Glyph.well(this, icon, color(well), color(ink)),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, dp(8), 0);
        text.addView(this.text(title, 16, color(R.color.on_surface), true));
        if (cap != null && cap.length() > 0) text.addView(caption(cap));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    private View labeled(String title, String cap) {
        LinearLayout block = column();
        block.setPadding(dp(16), dp(14), dp(16), dp(8));
        block.addView(text(title, 16, color(R.color.on_surface), true));
        block.addView(caption(cap));
        return block;
    }

    private View trigger(String label, boolean on, int well, int ink, View.OnClickListener click) {
        LinearLayout chip = row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        if (on) {
            ImageView check = new ImageView(this);
            check.setImageDrawable(Glyph.drawable(this, "check", 15, color(ink), 2.6f));
            chip.addView(check, new LinearLayout.LayoutParams(dp(15), dp(15)));
        }
        TextView name = text(label, 14, color(on ? ink : R.color.on_surface), on);
        if (on) name.setPadding(dp(4), 0, 0, 0);
        chip.addView(name);
        GradientDrawable shape = round(on ? color(well) : Color.TRANSPARENT, 17);
        if (!on) shape.setStroke(dp(1), color(R.color.outline));
        chip.setBackground(shape);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        chip.setLayoutParams(lp);
        return chip;
    }

    private TextView segmentButton(String label, boolean on, View.OnClickListener click) {
        TextView button = text(label, 14, color(on ? R.color.on_surface : R.color.on_surface_variant), on);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(34));
        button.setBackground(on ? round(color(R.color.surface), 11) : null);
        button.setOnClickListener(click);
        return button;
    }

    private String segmentLabel() {
        int seconds = Prefs.getInt(Prefs.KEY_SEGMENT, 300);
        if (seconds % 60 == 0) return (seconds / 60) + " 分钟";
        return seconds + " 秒";
    }

    private String fpsLabel() {
        return Prefs.fps() + " fps";
    }

    private static String clock(int minute) {
        int value = ((minute % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", value / 60, value % 60);
    }

    private static String elapsed() {
        long ms = CameraStreamService.recordingSinceMs <= 0 ? 0
                : System.currentTimeMillis() - CameraStreamService.recordingSinceMs;
        long seconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }

    private int today() { return Math.max(0, v22Fallback()); }

    private int v22Fallback() {
        return ScreenSnapshot.todayClips >= 0 ? ScreenSnapshot.todayClips : 0;
    }

    private int brightness() {
        if (ScreenSnapshot.brightness >= 0) return ScreenSnapshot.brightness;
        return LightPolicy.SENSOR.equals(Prefs.lightMethod())
                ? CameraStreamService.sensorBrightness : CameraStreamService.frameBrightness;
    }

    private static int triggerCount() {
        return (Prefs.motionEnabled() ? 1 : 0) + (Prefs.soundSelected() ? 1 : 0)
                + (Prefs.getBool(Prefs.KEY_SCHEDULE, false) ? 1 : 0)
                + (Prefs.recordLightEnabled() ? 1 : 0);
    }

    private String freeLabel() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return String.format(Locale.US, "%.1f GB", fs.getAvailableBytes() / (1024d * 1024d * 1024d));
        } catch (Throwable failure) {
            return "—";
        }
    }

    private String usedLabel() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            return String.format(Locale.US, "%.1f GB", (total - free) / (1024d * 1024d * 1024d));
        } catch (Throwable failure) {
            return "—";
        }
    }

    private void applyColors() {}

    private LinearLayout card() {
        return new V22Ui.SurfaceCard(this);
    }

    private TextView section(String label) {
        TextView view = text(label, 20, color(R.color.on_surface), true);
        view.setPadding(dp(4), dp(20), dp(4), dp(8));
        return view;
    }

    private TextView caption(String value) {
        TextView view = text(value, 13, color(R.color.on_surface_variant), false);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(medium ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
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

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(fill);
        return shape;
    }

    private int color(int id) { return getResources().getColor(id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
