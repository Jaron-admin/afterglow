package com.oldphone.ipcam;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Infrequent recording controls live here, keeping the camera screen compact. */
public final class RecordingSettingsActivity extends Activity {
    private LinearLayout content, sink, recordingPanel, timeRow, morePanel;
    private Spinner mode, target, policy, motionSensitivity, soundSensitivity;
    private Spinner schedule, recordSize;
    private Choice segment, pre, post, quota, retention, segmentSize;
    private EditText reserve;
    private CheckBox microphone, recordAudio, motion, sound;
    private TextView usage, startTime, endTime;
    private Button moreButton;
    private final List<String> targetIds = new ArrayList<String>();
    private final List<Choice> choices = new ArrayList<Choice>();
    private int nextId = 4100;
    private int startMinute = 8 * 60, endMinute = 20 * 60, usageGeneration;
    private boolean destroyed, moreOpen;
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
        content.setPadding(dp(20), dp(16), dp(20), dp(28));
        sink = content;
        scroll.addView(content);
        setContentView(scroll);
        label("录像与检测", 24);
        mode = choice("录制方式", new String[]{"关闭录像", "连续录像", "事件录像"}, Prefs.recordingMode());
        note("关闭时不写文件。连续录像会一直保存；事件录像只在画面或声音触发时保存。");
        recordingPanel = column();
        sink = recordingPanel;
        section("什么时候录");
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
        note("结束时刻不算在内。例如 22:00 到 07:00 会跨过半夜。时间外会把正在录的这一段在下一个关键帧收尾，然后停录。直播不受影响。");
        section("检测");
        motion = check("检测画面变动", Prefs.motionEnabled());
        motionSensitivity = choice("画面灵敏度", new String[]{"低", "中", "高"}, Prefs.sensitivity());
        sound = check("声响触发事件", Prefs.getBool(Prefs.KEY_SOUND, false));
        soundSensitivity = choice("声响灵敏度", new String[]{"低", "中", "高"}, Prefs.soundSensitivity());
        note("只判断画面变化和声音变大，不认人、宠物或某种叫声。连续录像里，检测只给这一段做标记。");
        section("事件前后");
        pre = preset("触发前", RecordingChoices.PRE_ROLL_SECONDS,
                new String[]{"0 秒", "3 秒", "5 秒", "10 秒", "15 秒", "30 秒"},
                Prefs.getInt(Prefs.KEY_PRE_ROLL, 5), 0, 30, "预录");
        post = preset("触发后", RecordingChoices.POST_ROLL_SECONDS,
                new String[]{"10 秒", "15 秒", "30 秒", "60 秒", "2 分钟", "5 分钟"},
                Prefs.getInt(Prefs.KEY_POST_ROLL, 15), 1, 600, "延录");
        note("预录受关键帧和 8 MiB 内存限制，高码率时可能更短。连着触发会并成一段，太长仍按下面的分段切开。");
        section("分段");
        segment = preset("每段时长", RecordingChoices.SEGMENT_SECONDS,
                new String[]{"1 分钟", "5 分钟", "10 分钟", "30 分钟", "60 分钟"},
                Prefs.getInt(Prefs.KEY_SEGMENT, 300), 10, 3600, "每段时长");
        note("到点后在下一个关键帧切开。快到单段大小上限时会提前切。");
        section("存储");
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
        quota = preset("录像总容量", RecordingChoices.QUOTA_GIB,
                new String[]{"1 GiB", "2 GiB", "5 GiB", "10 GiB", "32 GiB"},
                Prefs.getInt(Prefs.KEY_QUOTA, 2), 1, 1024, "总容量");
        policy = choice("空间满了以后", new String[]{"循环盖掉最旧的未锁定录像", "停止录像，全部留下"},
                Prefs.getBool(Prefs.KEY_LOOP, true) ? 0 : 1);
        retention = preset("保留多久", RecordingChoices.RETENTION_DAYS,
                new String[]{"不限", "3 天", "7 天", "14 天", "30 天"},
                Prefs.getInt(Prefs.KEY_RETENTION, 0), 0, 3650, "保留天数");
        note("只有循环模式才按天数删除。锁定的片段不会被自动清掉。卸载应用会删除这里的录像；卡拔掉或写满时只暂停录像，推流继续。");
        moreOpen = saved != null && saved.getBoolean("more", false);
        if (!moreOpen) moreOpen = RecordingChoices.indexOf(RecordingChoices.SEGMENT_MIB,
                Prefs.getInt(Prefs.KEY_SEGMENT_SIZE, 256)) < 0
                || Prefs.getInt(Prefs.KEY_RESERVE, 256) != 256;
        moreButton = button("更多", new View.OnClickListener() {
            @Override public void onClick(View view) {
                moreOpen = !moreOpen;
                syncMore(moreButton);
            }
        });
        morePanel = column();
        sink.addView(morePanel);
        LinearLayout previous = sink;
        sink = morePanel;
        segmentSize = preset("单段大小上限", RecordingChoices.SEGMENT_MIB,
                new String[]{"128 MiB", "256 MiB", "512 MiB", "1024 MiB"},
                Prefs.getInt(Prefs.KEY_SEGMENT_SIZE, 256), 16, 2048, "单段大小");
        reserve = number("设备最低剩余空间（MiB，64–16384）", Prefs.KEY_RESERVE, 256);
        sink = previous;
        syncMore(moreButton);
        sink = content;
        content.addView(recordingPanel);
        section("画质和声音");
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
        note("帧率和码率仍用主页的设置。录像和 RTSP 共用一个编码器。改分辨率会短暂重启采集，并结束当前片段。");
        microphone = check("启用麦克风 · RTSP 可带声音", Prefs.microphoneEnabled());
        recordAudio = check("把声音保存进录像", Prefs.getBool(Prefs.KEY_RECORD_AUDIO, true));
        note("麦克风默认关闭。MJPEG 网页始终没有声音。");
        button("保存并应用", new View.OnClickListener() {
            @Override public void onClick(View view) { save(); }
        });
        button("查看录像", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(RecordingSettingsActivity.this, RecordingsActivity.class));
            }
        });
        button("返回", new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { syncMode(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        schedule.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { syncSchedule(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refreshUsage(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        motion.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { syncDetection(); }
        });
        microphone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { syncAudio(); }
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
        for (Choice item : choices) item.sync();
        refreshUsage();
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        syncMode();
        syncSchedule();
        syncDetection();
        syncAudio();
        syncMore(moreButton);
        for (Choice item : choices) item.sync();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("startMinute", startMinute);
        state.putInt("endMinute", endMinute);
        state.putBoolean("more", moreOpen);
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
        boolean mic = microphone.isChecked();
        recordAudio.setEnabled(mic);
        sound.setEnabled(mic);
        soundSensitivity.setEnabled(mic && sound.isChecked());
    }
    private void syncMore(Button more) {
        more.setText(moreOpen ? "收起更多" : "更多");
        morePanel.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
    }
    private void showTimes() {
        startTime.setText("开始 " + clock(startMinute));
        endTime.setText("结束 " + clock(endMinute));
    }
    private TextView timeButton(final boolean start) {
        Button button = new Button(this);
        button.setId(nextId++);
        button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { pickTime(start); }
        });
        timeRow.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
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
                    microphone.isChecked() && recordAudio.isChecked());
            if (recordMode == 2 && !motion.isChecked()
                    && !(microphone.isChecked() && sound.isChecked()))
                throw new IllegalArgumentException("事件录像需要开启画面或声响检测");
            if (recordMode == 0 && Prefs.mode() == Prefs.MODE_LOCAL)
                throw new IllegalArgumentException("仅本地模式需要开启录像，请先在主页更改输出方式");
            if (microphone.isChecked() && Build.VERSION.SDK_INT >= 23
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
            Prefs.setBool(Prefs.KEY_MICROPHONE, microphone.isChecked());
            Prefs.setBool(Prefs.KEY_RECORD_AUDIO, recordAudio.isChecked());
            Prefs.setBool(Prefs.KEY_MOTION, motion.isChecked());
            Prefs.setBool(Prefs.KEY_SOUND, sound.isChecked());
            Prefs.setInt(Prefs.KEY_SENSITIVITY, motionSensitivity.getSelectedItemPosition());
            Prefs.setInt(Prefs.KEY_SOUND_SENSITIVITY, soundSensitivity.getSelectedItemPosition());
            int recordIndex = recordSize.getSelectedItemPosition();
            if (recordIndex >= 0 && recordIndex < RECORD_SIZES.length)
                Prefs.setRecordSize(RECORD_SIZES[recordIndex][0], RECORD_SIZES[recordIndex][1]);
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
        else Toast.makeText(this, "未获得麦克风权限；请授权或关闭麦克风后保存",
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
        label(title, 14);
        Spinner spinner = new Spinner(this);
        spinner.setId(nextId++);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_spinner, values);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        if (selected < 0 || selected >= values.length) selected = 0;
        spinner.setSelection(selected);
        sink.addView(spinner, new LinearLayout.LayoutParams(-1, dp(50)));
        return spinner;
    }
    private EditText number(String title, String key, int fallback) {
        label(title, 14);
        EditText field = numberField(String.valueOf(Prefs.getInt(key, fallback)));
        return field;
    }
    private EditText numberField(String value) {
        EditText field = new EditText(this);
        field.setId(nextId++);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setHint("自定义");
        field.setText(value);
        sink.addView(field, new LinearLayout.LayoutParams(-1, dp(50)));
        return field;
    }
    private CheckBox check(String title, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setId(nextId++);
        box.setText(title);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        sink.addView(box);
        return box;
    }
    private Button button(String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setId(nextId++);
        button.setAllCaps(false);
        button.setText(title);
        button.setOnClickListener(listener);
        sink.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
        return button;
    }
    private void section(String title) {
        TextView view = label(title, 19);
        view.setPadding(0, dp(24), 0, dp(10));
    }
    private void note(String text) {
        TextView view = label(text, 12);
        view.setTextColor(getResources().getColor(R.color.on_surface_variant));
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(getResources().getColor(R.color.on_surface));
        view.setPadding(0, dp(10), 0, dp(6));
        sink.addView(view);
        return view;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
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
