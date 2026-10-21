package com.oldphone.ipcam;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Common recording controls first. Segment, picture, storage and hours stay folded. */
public final class RecordingSettingsActivity extends Activity {
    private LinearLayout content, sink, recordingPanel, timeRow, advancedPanel;
    private Spinner mode, target, policy, motionSensitivity, soundSensitivity, bitrate;
    private Spinner schedule, recordSize;
    private Choice segment, pre, post, quota, retention, segmentSize;
    private EditText reserve;
    private CompoundButton motion, sound;
    private TextView soundNote;
    private TextView usage, startTime, endTime, advancedSummary;
    private Button advancedButton;
    private final List<String> targetIds = new ArrayList<String>();
    private final List<Choice> choices = new ArrayList<Choice>();
    private int nextId = 4100;
    private int startMinute = 8 * 60, endMinute = 20 * 60, usageGeneration;
    private boolean destroyed, advancedOpen;
    private static final int AUDIO_PERMISSION = 41;
    private static final int[][] RECORD_SIZES = {
            {0, 0}, {640, 360}, {960, 540}, {1280, 720}
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.init(this);
        ActivityOrientation.apply(this);
        ScrollView scroll = new ScrollView(this);
        content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        sink = content;
        TextView title = label("录制设置", 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), dp(8), dp(4), 0);
        TextView intro = note("常用的放在上面。分段、画质、存储和定时收在高级里。");
        intro.setPadding(dp(4), 0, dp(4), dp(4));

        card(content, "录制方式", "决定什么时候写文件", "录", R.color.primary);
        mode = choice("当前", new String[]{"关闭录像", "连续录像", "事件录像"}, Prefs.recordingMode());
        note("关闭时不写文件。连续录像会一直保存；事件录像只在画面或声音触发时保存。");

        recordingPanel = column();
        content.addView(recordingPanel);
        card(recordingPanel, "触发", "画面变动和声音变大。不认人，也不认宠物。", "检", R.color.secondary);
        motion = check("画面变动", Prefs.motionEnabled());
        motionSensitivity = choice("画面灵敏度", new String[]{"低", "中", "高"}, Prefs.sensitivity());
        sound = check("声音触发", Prefs.getBool(Prefs.KEY_SOUND, false));
        soundNote = note("");
        soundSensitivity = choice("声音灵敏度", new String[]{"低", "中", "高"}, Prefs.soundSensitivity());

        advancedOpen = saved != null && saved.getBoolean("advanced", false);
        if (!advancedOpen) advancedOpen = RecordingChoices.indexOf(RecordingChoices.SEGMENT_MIB,
                Prefs.getInt(Prefs.KEY_SEGMENT_SIZE, 256)) < 0
                || Prefs.getInt(Prefs.KEY_RESERVE, 256) != 256;
        LinearLayout advancedHeader = column();
        advancedHeader.setBackgroundResource(R.drawable.bg_card);
        advancedHeader.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.topMargin = dp(12);
        recordingPanel.addView(advancedHeader, headerParams);
        TextView advancedTitle = new TextView(this);
        advancedTitle.setText("高级");
        advancedTitle.setTextSize(16);
        advancedTitle.setTextColor(color(R.color.on_surface));
        advancedTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        advancedHeader.addView(advancedTitle);
        advancedSummary = new TextView(this);
        advancedSummary.setTextSize(12);
        advancedSummary.setTextColor(color(R.color.secondary));
        advancedSummary.setPadding(0, dp(4), 0, dp(4));
        advancedHeader.addView(advancedSummary);
        advancedButton = new Button(this);
        advancedButton.setAllCaps(false);
        advancedButton.setTextColor(color(R.color.accent_text));
        advancedButton.setBackgroundResource(R.drawable.bg_field);
        advancedButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                advancedOpen = !advancedOpen;
                syncAdvanced();
            }
        });
        advancedHeader.addView(advancedButton, new LinearLayout.LayoutParams(-1, dp(48)));
        advancedPanel = column();
        recordingPanel.addView(advancedPanel);
        sink = advancedPanel;

        card(advancedPanel, "分段", "到点后在下一个关键帧切开。", "段", R.color.primary);
        pre = preset("触发前", RecordingChoices.PRE_ROLL_SECONDS,
                new String[]{"0 秒", "3 秒", "5 秒", "10 秒", "15 秒", "30 秒"},
                Prefs.getInt(Prefs.KEY_PRE_ROLL, 5), 0, 30, "预录");
        post = preset("触发后", RecordingChoices.POST_ROLL_SECONDS,
                new String[]{"10 秒", "15 秒", "30 秒", "60 秒", "2 分钟", "5 分钟"},
                Prefs.getInt(Prefs.KEY_POST_ROLL, 15), 1, 600, "延录");
        note("预录受关键帧和 8 MiB 内存限制，高码率时可能更短。连着触发会并成一段。");
        segment = preset("每段时长", RecordingChoices.SEGMENT_SECONDS,
                new String[]{"1 分钟", "5 分钟", "10 分钟", "30 分钟", "60 分钟"},
                Prefs.getInt(Prefs.KEY_SEGMENT, 300), 10, 3600, "每段时长");
        segmentSize = preset("单段大小上限", RecordingChoices.SEGMENT_MIB,
                new String[]{"128 MiB", "256 MiB", "512 MiB", "1024 MiB"},
                Prefs.getInt(Prefs.KEY_SEGMENT_SIZE, 256), 16, 2048, "单段大小");
        note("快到单段大小上限时会提前切。");

        card(advancedPanel, "画质", "录像和直播共用一个编码器。", "质", R.color.secondary);
        String[] recordLabels = new String[RECORD_SIZES.length];
        recordLabels[0] = "跟随推流（" + Prefs.outputWidth() + "×" + Prefs.outputHeight() + "）";
        for (int i = 1; i < RECORD_SIZES.length; i++)
            recordLabels[i] = RECORD_SIZES[i][0] + "×" + RECORD_SIZES[i][1];
        int recordSelected = 0;
        for (int i = 1; i < RECORD_SIZES.length; i++) {
            if (Prefs.recordWidth() == RECORD_SIZES[i][0] && Prefs.recordHeight() == RECORD_SIZES[i][1])
                recordSelected = i;
        }
        recordSize = choice("录像分辨率", recordLabels, recordSelected);
        int bitrateIndex = Prefs.getInt(Prefs.KEY_BITRATE, 2);
        if (bitrateIndex < 0 || bitrateIndex >= Prefs.BITRATE_LABELS.length) bitrateIndex = 2;
        bitrate = choice("码率", Prefs.BITRATE_LABELS, bitrateIndex);
        note("码率与「画面与推流」是同一项。帧率也在那里。改分辨率会短暂重启采集，并结束当前片段。");

        card(advancedPanel, "存储", "满了以后怎么处理，以及保留多久。", "存", R.color.primary);
        List<RecordingStore.Target> targets = RecordingStore.targets(this);
        List<String> labels = new ArrayList<String>();
        String selected = Prefs.recordingTarget();
        int selectedIndex = -1;
        for (RecordingStore.Target item : targets) {
            if (item.id.equals(selected) || (selected.length() == 0 && !item.external))
                selectedIndex = labels.size();
            targetIds.add(item.id);
            labels.add(item.label);
        }
        if (selectedIndex < 0) {
            selectedIndex = labels.size();
            targetIds.add(selected);
            labels.add("所选存储暂不可用");
        }
        target = choice("保存位置", labels.toArray(new String[0]), selectedIndex);
        usage = label("正在读取用量…", 13);
        usage.setTextColor(color(R.color.secondary));
        quota = preset("录像总容量", RecordingChoices.QUOTA_GIB,
                new String[]{"1 GiB", "2 GiB", "5 GiB", "10 GiB", "32 GiB"},
                Prefs.getInt(Prefs.KEY_QUOTA, 2), 1, 1024, "总容量");
        policy = choice("空间满了以后", new String[]{"循环盖掉最旧的未锁定录像", "停止录像，全部留下"},
                Prefs.getBool(Prefs.KEY_LOOP, true) ? 0 : 1);
        retention = preset("保留多久", RecordingChoices.RETENTION_DAYS,
                new String[]{"不限", "3 天", "7 天", "14 天", "30 天"},
                Prefs.getInt(Prefs.KEY_RETENTION, 0), 0, 3650, "保留天数");
        reserve = number("设备最低剩余空间（MiB，64–16384）", Prefs.KEY_RESERVE, 256);
        note("只有循环模式才按天数删除。锁定的片段不会被自动清掉。卸载应用会删除这里的录像；卡拔掉或写满时只暂停录像，推流继续。");

        card(advancedPanel, "定时", "结束时刻不算在内，可以跨过半夜。", "时", R.color.secondary);
        boolean daily = Prefs.recordingSchedule().limited();
        schedule = choice("录像时间", new String[]{"全天", "每天一段时间"}, daily ? 1 : 0);
        startMinute = clampMinute(Prefs.getInt(Prefs.KEY_SCHEDULE_START, 8 * 60), 8 * 60);
        endMinute = clampMinute(Prefs.getInt(Prefs.KEY_SCHEDULE_END, 20 * 60), 20 * 60);
        if (startMinute == endMinute) endMinute = startMinute == 20 * 60 ? 8 * 60 : 20 * 60;
        timeRow = column();
        startTime = timeButton(true);
        endTime = timeButton(false);
        showTimes();
        sink.addView(timeRow);
        note("时间外会把正在录的这一段在下一个关键帧收尾，然后停录。直播不受影响。");

        sink = content;
        button("保存并应用", true, new View.OnClickListener() {
            public void onClick(View view) { save(); }
        });
        button("查看录像", false, new View.OnClickListener() {
            public void onClick(View view) {
                startActivity(new Intent(RecordingSettingsActivity.this, RecordingsActivity.class));
            }
        });
        button("返回", false, new View.OnClickListener() {
            public void onClick(View view) { finish(); }
        });

        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { syncMode(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        schedule.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                syncSchedule();
                refreshAdvancedSummary();
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        recordSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refreshAdvancedSummary(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refreshUsage(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        motion.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { syncDetection(); }
        });
        sound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { syncAudio(); }
        });
        if (saved != null) {
            startMinute = saved.getInt("startMinute", startMinute);
            endMinute = saved.getInt("endMinute", endMinute);
            showTimes();
        }
        syncMode();
        syncSchedule();
        syncDetection();
        syncAudio();
        syncAdvanced();
        for (Choice item : choices) item.sync();
        refreshAdvancedSummary();
        refreshUsage();
    }

    @Override protected void onResume() {
        super.onResume();
        syncAudio();
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        syncMode();
        syncSchedule();
        syncDetection();
        syncAudio();
        syncAdvanced();
        for (Choice item : choices) item.sync();
        refreshAdvancedSummary();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("startMinute", startMinute);
        state.putInt("endMinute", endMinute);
        state.putBoolean("advanced", advancedOpen);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        usageGeneration++;
        super.onDestroy();
    }

    private void syncMode() {
        recordingPanel.setVisibility(mode.getSelectedItemPosition() == 0 ? View.GONE : View.VISIBLE);
    }
    private void syncSchedule() {
        timeRow.setVisibility(schedule.getSelectedItemPosition() == 1 ? View.VISIBLE : View.GONE);
    }
    private void syncDetection() {
        motionSensitivity.setEnabled(motion.isChecked());
    }
    private void syncAudio() {
        boolean mic = Prefs.microphoneEnabled();
        boolean soundOn = RecordingAudio.soundControlsEnabled(mic);
        sound.setEnabled(soundOn);
        sound.setTextColor(color(soundOn ? R.color.on_surface : R.color.on_surface_disabled));
        soundSensitivity.setEnabled(RecordingAudio.soundSensitivityEnabled(mic, sound.isChecked()));
        String note = RecordingAudio.soundDisabledNote(mic);
        soundNote.setText(note);
        soundNote.setVisibility(note.length() == 0 ? View.GONE : View.VISIBLE);
    }
    private void syncAdvanced() {
        advancedButton.setText(advancedOpen ? "收起高级" : "展开高级");
        advancedPanel.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
    }
    private void refreshAdvancedSummary() {
        if (advancedSummary == null || segment == null || quota == null || recordSize == null || schedule == null)
            return;
        String when = schedule.getSelectedItemPosition() == 1
                ? clock(startMinute) + "–" + clock(endMinute) : "全天";
        advancedSummary.setText(text(segment.spinner) + " · " + text(recordSize) + " · "
                + text(quota.spinner) + " · " + when);
    }
    private void showTimes() {
        startTime.setText("开始 " + clock(startMinute));
        endTime.setText("结束 " + clock(endMinute));
        refreshAdvancedSummary();
    }
    private TextView timeButton(final boolean start) {
        Button button = new Button(this);
        button.setId(nextId++);
        button.setAllCaps(false);
        button.setTextColor(color(R.color.on_surface));
        button.setBackgroundResource(R.drawable.bg_field);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { pickTime(start); }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        timeRow.addView(button, params);
        return button;
    }
    private void pickTime(final boolean start) {
        int minute = start ? startMinute : endMinute;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker view, int hour, int minuteOfHour) {
                int value = hour * 60 + minuteOfHour;
                if (start) startMinute = value; else endMinute = value;
                showTimes();
            }
        }, minute / 60, minute % 60, true).show();
    }

    private void refreshUsage() {
        if (target == null || usage == null) return;
        final int position = target.getSelectedItemPosition();
        if (position < 0 || position >= targetIds.size()) return;
        final String id = targetIds.get(position);
        final int token = ++usageGeneration;
        final long cap = (long) quota.valueOrDefault(Prefs.getInt(Prefs.KEY_QUOTA, 2)) << 30;
        usage.setText("正在读取用量…");
        new Thread(new Runnable() {
            public void run() {
                String text;
                try {
                    FileTarget found = findTarget(id);
                    if (found == null) text = "所选存储暂不可用";
                    else {
                        long used = 0;
                        RecordingStore store = RecordingStore.open(getApplicationContext(), id, cap);
                        for (RecordingStore.Clip clip : store.list()) used += clip.bytes;
                        long free = new StatFs(found.directory.getPath()).getAvailableBytes();
                        text = "已用 " + amount(used) + " · 上限 " + amount(cap) + " · 设备剩余 " + amount(free);
                    }
                } catch (Exception failure) {
                    text = failure.getMessage() == null ? "暂时读不到用量" : failure.getMessage();
                }
                final String shown = text;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!destroyed && token == usageGeneration) usage.setText(shown);
                    }
                });
            }
        }, "afterglow-usage").start();
    }
    private FileTarget findTarget(String id) {
        for (RecordingStore.Target item : RecordingStore.targets(getApplicationContext()))
            if (item.id.equals(id)) return new FileTarget(item.directory);
        return null;
    }
    private static final class FileTarget {
        final java.io.File directory;
        FileTarget(java.io.File directory) { this.directory = directory; }
    }

    private void save() {
        try {
            int recordMode = mode.getSelectedItemPosition();
            boolean daily = schedule.getSelectedItemPosition() == 1;
            if (daily) RecordingSchedule.daily(startMinute, endMinute);
            int seg = segment.read();
            int max = segmentSize.read();
            int cap = quota.read();
            int free = read(reserve, 64, 16384, "剩余空间");
            int days = retention.read();
            int before = pre.read();
            int after = post.read();
            new RecordingOptions(Math.max(1, recordMode), seg, before, after, days,
                    (long) cap << 30, (long) free << 20, (long) max << 20,
                    policy.getSelectedItemPosition() == 0,
                    RecordingAudio.fileAudio(Prefs.microphoneEnabled(),
                            Prefs.getBool(Prefs.KEY_RECORD_AUDIO, true)));
            if (recordMode == 2 && !motion.isChecked()
                    && !(Prefs.microphoneEnabled() && sound.isChecked()))
                throw new IllegalArgumentException("事件录像需要开启画面变动，或先在主界面打开录制声音再开声音触发");
            if (recordMode == 0 && Prefs.mode() == Prefs.MODE_LOCAL)
                throw new IllegalArgumentException("仅本地模式需要开启录像，请先在「画面与推流」更改输出方式");
            if (Prefs.microphoneEnabled() && Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                return;
            }
            Prefs.setInt(Prefs.KEY_RECORDING, recordMode);
            Prefs.setStr(Prefs.KEY_STORAGE, targetIds.get(target.getSelectedItemPosition()));
            Prefs.setBool(Prefs.KEY_SCHEDULE, daily);
            Prefs.setInt(Prefs.KEY_SCHEDULE_START, startMinute);
            Prefs.setInt(Prefs.KEY_SCHEDULE_END, endMinute);
            Prefs.setInt(Prefs.KEY_SEGMENT, seg);
            Prefs.setInt(Prefs.KEY_SEGMENT_SIZE, max);
            Prefs.setInt(Prefs.KEY_QUOTA, cap);
            Prefs.setInt(Prefs.KEY_RESERVE, free);
            Prefs.setInt(Prefs.KEY_RETENTION, days);
            Prefs.setInt(Prefs.KEY_PRE_ROLL, before);
            Prefs.setInt(Prefs.KEY_POST_ROLL, after);
            Prefs.setBool(Prefs.KEY_LOOP, policy.getSelectedItemPosition() == 0);
            Prefs.setBool(Prefs.KEY_MOTION, motion.isChecked());
            Prefs.setBool(Prefs.KEY_SOUND, sound.isChecked());
            Prefs.setInt(Prefs.KEY_SENSITIVITY, motionSensitivity.getSelectedItemPosition());
            Prefs.setInt(Prefs.KEY_SOUND_SENSITIVITY, soundSensitivity.getSelectedItemPosition());
            int recordIndex = recordSize.getSelectedItemPosition();
            if (recordIndex >= 0 && recordIndex < RECORD_SIZES.length)
                Prefs.setRecordSize(RECORD_SIZES[recordIndex][0], RECORD_SIZES[recordIndex][1]);
            int rate = bitrate.getSelectedItemPosition();
            if (rate >= 0 && rate < Prefs.BITRATE_LABELS.length) Prefs.setInt(Prefs.KEY_BITRATE, rate);
            if (CameraStreamService.running) startService(new Intent(this, CameraStreamService.class)
                    .setAction(CameraStreamService.ACTION_RECONFIGURE));
            Toast.makeText(this, "录像设置已保存", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IllegalArgumentException failure) {
            Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != AUDIO_PERMISSION) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) save();
        else Toast.makeText(this, "未获得麦克风权限。请回到主界面打开「录制声音」并允许麦克风。",
                Toast.LENGTH_LONG).show();
    }

    private final class Choice {
        final Spinner spinner;
        final EditText custom;
        final int[] values;
        final int min, max, fallback;
        final String name;
        Choice(Spinner spinner, EditText custom, int[] values, int min, int max, int fallback, String name) {
            this.spinner = spinner; this.custom = custom; this.values = values;
            this.min = min; this.max = max; this.fallback = fallback; this.name = name;
        }
        void sync() {
            custom.setVisibility(spinner.getSelectedItemPosition() >= values.length ? View.VISIBLE : View.GONE);
        }
        int valueOrDefault(int fallback) {
            try { return read(); } catch (IllegalArgumentException ignored) { return fallback; }
        }
        int read() {
            int selected = spinner.getSelectedItemPosition();
            if (selected >= 0 && selected < values.length) return values[selected];
            return RecordingSettingsActivity.this.read(custom, min, max, name);
        }
    }

    private Choice preset(String title, int[] values, String[] labels, int current, int min, int max, String name) {
        String[] items = new String[labels.length + 1];
        System.arraycopy(labels, 0, items, 0, labels.length);
        items[labels.length] = "自定义";
        int index = RecordingChoices.indexOf(values, current);
        final Choice choice = new Choice(choice(title, items, index < 0 ? labels.length : index),
                numberField(String.valueOf(current)), values, min, max, current, name);
        choice.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                choice.sync();
                if (choice == quota) refreshUsage();
                refreshAdvancedSummary();
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        choices.add(choice);
        choice.sync();
        return choice;
    }

    private int read(EditText field, int min, int max, String name) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        field.requestFocus();
        throw new IllegalArgumentException(name + "应在 " + min + "–" + max + " 之间");
    }
    private Spinner choice(String title, String[] values, int selected) {
        label(title, 13);
        Spinner spinner = new Spinner(this);
        spinner.setId(nextId++);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_spinner, values);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        if (selected < 0 || selected >= values.length) selected = 0;
        spinner.setSelection(selected);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundResource(R.drawable.bg_field);
        frame.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(4);
        sink.addView(frame, params);
        return spinner;
    }
    private EditText number(String title, String key, int fallback) {
        label(title, 13);
        return numberField(String.valueOf(Prefs.getInt(key, fallback)));
    }
    private EditText numberField(String value) {
        EditText field = new EditText(this);
        field.setId(nextId++);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setHint("自定义");
        field.setText(value);
        field.setTextColor(color(R.color.on_surface));
        field.setHintTextColor(color(R.color.on_surface_disabled));
        field.setBackgroundResource(R.drawable.bg_field);
        field.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(4);
        sink.addView(field, params);
        return field;
    }
    private Switch check(String title, boolean checked) {
        Switch box = new Switch(this);
        box.setId(nextId++);
        box.setText(title);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        box.setTextColor(color(R.color.on_surface));
        sink.addView(box);
        return box;
    }
    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setId(nextId++);
        button.setAllCaps(false);
        button.setText(title);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(10);
        if (primary) {
            button.setBackgroundResource(R.drawable.bg_btn_primary);
            button.setTextColor(color(R.color.on_primary));
        } else {
            button.setBackgroundResource(R.drawable.bg_field);
            button.setTextColor(color(R.color.secondary));
        }
        sink.addView(button, params);
        return button;
    }
    private void card(LinearLayout host, String title, String summary, String mark, int markColor) {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        host.addView(card, params);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(this);
        icon.setText(mark);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(14);
        icon.setTextColor(color(markColor));
        icon.setBackgroundResource(R.drawable.bg_icon_well);
        icon.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = column();
        titles.setPadding(dp(10), 0, 0, 0);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(16);
        heading.setTextColor(color(R.color.on_surface));
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView detail = new TextView(this);
        detail.setText(summary);
        detail.setTextSize(12);
        detail.setTextColor(color(R.color.on_surface_variant));
        titles.addView(heading);
        titles.addView(detail);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        head.addView(titles, titleParams);
        card.addView(head);
        sink = column();
        card.addView(sink);
    }
    private TextView note(String text) {
        TextView view = label(text, 12);
        view.setTextColor(color(R.color.on_surface_variant));
        return view;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color(R.color.on_surface));
        view.setPadding(0, dp(8), 0, dp(2));
        sink.addView(view);
        return view;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private int color(int id) { return getResources().getColor(id); }
    private static String text(Spinner spinner) {
        Object item = spinner.getSelectedItem();
        return item == null ? "" : item.toString();
    }
    private static int clampMinute(int minute, int fallback) {
        return minute >= 0 && minute <= 1439 ? minute : fallback;
    }
    private static String clock(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }
    private static String amount(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L)
            return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024d * 1024d * 1024d));
        return String.format(Locale.ROOT, "%.0f MiB", bytes / (1024d * 1024d));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
