package com.oldphone.ipcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Infrequent recording controls live here, keeping the camera screen compact. */
public final class RecordingSettingsActivity extends Activity {
    private LinearLayout content;
    private Spinner mode, target, policy, sensitivity;
    private EditText segment, size, quota, reserve, retention, pre, post;
    private CheckBox microphone, recordAudio, motion, sound;
    private final List<String> targetIds = new ArrayList<String>();
    private int nextId = 4100;
    private static final int AUDIO_PERMISSION = 41;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.init(this);
        ActivityOrientation.apply(this);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        label("录像与检测", 24);
        mode = choice("录制方式", new String[]{"关闭录像", "连续录像", "事件录像"},
                Prefs.recordingMode());
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
        note("录像保存在应用专属目录，卸载会删除。重要片段请在录像列表中导出。SD 卡不可用时暂停录像，推流继续。");
        section("分段与空间");
        segment = number("每段时长（秒，10–3600）", Prefs.KEY_SEGMENT, 300);
        size = number("单段大小上限（MiB，16–2048）", Prefs.KEY_SEGMENT_SIZE, 256);
        note("默认 300 秒，即 5 分钟。时长到达后在下一关键帧分段；接近大小上限时提前分段，无可用关键帧时暂停。");
        quota = number("录像总容量（GiB，1–1024）", Prefs.KEY_QUOTA, 2);
        reserve = number("设备最低剩余空间（MiB，64–16384）", Prefs.KEY_RESERVE, 256);
        policy = choice("达到空间上限时", new String[]{"循环覆盖最旧的未锁定录像", "停止录像，保留全部片段"},
                Prefs.getBool(Prefs.KEY_LOOP, true) ? 0 : 1);
        retention = number("保留天数（0 为不限，最多 3650）", Prefs.KEY_RETENTION, 0);
        note("循环模式也会清理超过保留天数的未锁定片段；停止模式不自动清理。锁定片段始终受保护。");
        section("事件录像");
        pre = number("事件前预录（秒，0–30）", Prefs.KEY_PRE_ROLL, 5);
        post = number("最后一次触发后延录（秒，1–600）", Prefs.KEY_POST_ROLL, 15);
        note("预录受关键帧和 8 MiB 内存上限约束，高码率时可能短于设置。连续触发会合并，长事件遵守分段设置。");
        motion = check("检测画面变动", Prefs.motionEnabled());
        sensitivity = choice("检测灵敏度", new String[]{"低", "中", "高"}, Prefs.sensitivity());
        section("声音");
        microphone = check("启用麦克风 · RTSP 可带声音", Prefs.microphoneEnabled());
        recordAudio = check("将声音保存到录像", Prefs.getBool(Prefs.KEY_RECORD_AUDIO, true));
        sound = check("声响触发事件", Prefs.getBool(Prefs.KEY_SOUND, false));
        microphone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { syncAudioControls(); }
        });
        syncAudioControls();
        note("麦克风默认关闭。MJPEG 网页始终无声；声响检测判断声音变化，不识别说话或犬吠。");
        section("录像画质");
        note(Prefs.outputWidth() + " × " + Prefs.outputHeight() + " · " + Prefs.fps()
                + " fps · " + (Prefs.bitrate() / 1000) + " kbps\n分辨率、比例、帧率和码率与主页推流设置共用。");
        note("录像修改保存后，运行中的采集会短暂重启，并结束当前片段。");
        button("保存并应用", new View.OnClickListener() {
            @Override public void onClick(View view) { save(); }
        });
        button("查看本地录像", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(RecordingSettingsActivity.this, RecordingsActivity.class));
            }
        });
        button("返回", new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        syncAudioControls();
    }

    private void syncAudioControls() {
        recordAudio.setEnabled(microphone.isChecked());
        sound.setEnabled(microphone.isChecked());
    }

    private void save() {
        try {
            int seg = read(segment, 10, 3600, "每段时长");
            int max = read(size, 16, 2048, "单段大小");
            int cap = read(quota, 1, 1024, "总容量");
            int free = read(reserve, 64, 16384, "剩余空间");
            int days = read(retention, 0, 3650, "保留天数");
            int before = read(pre, 0, 30, "预录");
            int after = read(post, 1, 600, "延录");
            int recordMode = mode.getSelectedItemPosition();
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
            Prefs.setInt(Prefs.KEY_SENSITIVITY, sensitivity.getSelectedItemPosition());
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
        spinner.setSelection(selected);
        content.addView(spinner, new LinearLayout.LayoutParams(-1, dp(50)));
        return spinner;
    }
    private EditText number(String title, String key, int fallback) {
        label(title, 14);
        EditText field = new EditText(this);
        field.setId(nextId++);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setText(String.valueOf(Prefs.getInt(key, fallback)));
        content.addView(field, new LinearLayout.LayoutParams(-1, dp(50)));
        return field;
    }
    private CheckBox check(String title, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setId(nextId++);
        box.setText(title);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        content.addView(box);
        return box;
    }
    private void button(String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(listener);
        content.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
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
        content.addView(view);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
