package com.oldphone.ipcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recording list with export, lock, delete, and handoff to another app. */
public final class RecordingsActivity extends Activity {
    private static final int EXPORT = 53;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final List<RecordingStore.Clip> clips = new ArrayList<RecordingStore.Clip>();
    private List<RecordingStore.Target> targets = Collections.emptyList();
    private Spinner targetPicker;
    private TextView summary;
    private ArrayAdapter<String> rows;
    private String selectedTarget = "", exportTarget, exportId;
    private int generation;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.init(this);
        ActivityOrientation.apply(this);
        if (state != null) {
            selectedTarget = state.getString("target", "");
            exportTarget = state.getString("exportTarget");
            exportId = state.getString("exportId");
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("本地录像"); title.setTextSize(22);
        content.addView(title);
        TextView notice = new TextView(this);
        notice.setText("卸载应用会删除这里的录像。重要片段请导出；锁定仅防止循环清理。");
        notice.setTextSize(13); notice.setPadding(0, dp(10), 0, dp(10));
        content.addView(notice);
        targetPicker = new Spinner(this);
        content.addView(targetPicker, new LinearLayout.LayoutParams(-1, dp(48)));
        summary = new TextView(this); summary.setTextSize(12);
        summary.setPadding(0, dp(6), 0, dp(6)); content.addView(summary);
        Button refresh = new Button(this); refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { reloadTargets(); } });
        content.addView(refresh);
        ListView list = new ListView(this);
        rows = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(rows);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < clips.size()) actions(selectedTarget, clips.get(position));
            }
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
        targetPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < targets.size()) {
                    selectedTarget = targets.get(position).id;
                    reloadClips();
                }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        reloadTargets();
    }
    private void reloadTargets() {
        final int token = ++generation;
        summary.setText("正在读取存储…");
        disk.execute(new Runnable() {
            public void run() {
                final List<RecordingStore.Target> available = RecordingStore.targets(getApplicationContext());
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed || token != generation) return;
                        targets = available;
                        List<String> labels = new ArrayList<String>();
                        int selected = 0;
                        for (int i = 0; i < targets.size(); i++) {
                            RecordingStore.Target target = targets.get(i);
                            labels.add(target.label + " · " + target.directory.getPath());
                            if (target.id.equals(selectedTarget)) selected = i;
                        }
                        targetPicker.setAdapter(new ArrayAdapter<String>(RecordingsActivity.this,
                                android.R.layout.simple_spinner_dropdown_item, labels));
                        if (!targets.isEmpty()) targetPicker.setSelection(selected);
                        else { rows.clear(); clips.clear(); summary.setText("没有可用存储"); }
                    }
                });
            }
        });
    }
    private void reloadClips() {
        if (selectedTarget.length() == 0) return;
        final String target = selectedTarget;
        final int token = ++generation;
        summary.setText("正在读取录像…");
        disk.execute(new Runnable() {
            public void run() {
                try {
                    RecordingStore store = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA);
                    final List<RecordingStore.Clip> found = store.list();
                    Collections.reverse(found);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || token != generation) return;
                            clips.clear(); clips.addAll(found); rows.clear();
                            long total = 0;
                            DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
                            for (RecordingStore.Clip clip : found) {
                                total += clip.bytes;
                                rows.add((clip.locked ? "🔒 " : "") + date.format(new java.util.Date(clip.startTimeMs))
                                        + "\n" + duration(clip.durationMs) + " · " + size(clip.bytes)
                                        + (clip.withAudio ? " · 有声" : " · 无声")
                                        + (clip.reasons.length() == 0 ? "" : "\n" + clip.reasons));
                            }
                            summary.setText(found.size() + " 段 · " + size(total)
                                    + "\n只显示已完整结束的片段，正在录像的一段稍后出现。");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || token != generation) return;
                            rows.clear(); clips.clear(); summary.setText(message(e));
                        }
                    });
                }
            }
        });
    }
    private void actions(final String target, final RecordingStore.Clip clip) {
        new AlertDialog.Builder(this).setTitle("录像")
                .setItems(new String[] {"用其他应用打开", "分享", "导出", clip.locked ? "解除锁定" : "锁定", "删除"},
                        new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) handoff(target, clip.id, false);
                            else if (which == 1) handoff(target, clip.id, true);
                            else if (which == 2) export(target, clip);
                            else if (which == 3) mutate(target, clip.id, !clip.locked, false);
                            else new AlertDialog.Builder(RecordingsActivity.this).setTitle("删除这段录像？")
                                    .setMessage(clip.locked ? "这段录像已锁定。删除后无法恢复。" : "删除后无法恢复。")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface confirm, int value) {
                                            mutate(target, clip.id, false, true);
                                        }
                                    })
                                    .show();
                        }}).show();
    }
    private void mutate(final String target, final String id, final boolean locked, final boolean delete) {
        disk.execute(new Runnable() {
            public void run() {
                try {
                    RecordingStore store = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA);
                    if (delete) store.delete(id); else store.setLocked(id, locked);
                    runOnUiThread(new Runnable() { public void run() { if (!destroyed) reloadClips(); } });
                } catch (Exception e) { showError(e); }
            }
        });
    }
    private void handoff(String target, String id, boolean share) {
        try {
            Uri uri = RecordingFileProvider.uriFor(target, id);
            Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
            if (share) {
                intent.setType("video/mp4");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(getContentResolver(), "recording", uri));
            Intent chooser = Intent.createChooser(intent, share ? "分享录像" : "用其他应用打开");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.setClipData(intent.getClipData());
            startActivity(chooser);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "没有可以打开视频的应用，请先导出", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "没有可以打开视频的应用，请先导出", Toast.LENGTH_LONG).show();
        }
    }
    private void export(String target, RecordingStore.Clip clip) {
        exportTarget = target; exportId = clip.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, "Afterglow-" + clip.id + ".mp4");
        try { startActivityForResult(intent, EXPORT); }
        catch (RuntimeException e) { showError(new IOException("系统未提供文件导出工具", e)); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null
                || exportTarget == null || exportId == null) return;
        final String target = exportTarget, id = exportId;
        final Uri destination = data.getData();
        exportTarget = exportId = null;
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        disk.execute(new Runnable() {
            public void run() {
                try {
                    File source = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA)
                            .registeredFile(id);
                    try (FileInputStream input = new FileInputStream(source);
                         OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) throw new IOException("无法打开导出位置");
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        output.flush();
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!destroyed) Toast.makeText(RecordingsActivity.this, "已导出", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) { showError(e); }
            }
        });
    }
    private void showError(final Exception error) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (!destroyed) Toast.makeText(RecordingsActivity.this, message(error), Toast.LENGTH_LONG).show();
            }
        });
    }
    private static String message(Exception e) { return e.getMessage() == null ? "存储操作失败" : e.getMessage(); }
    private static String size(long bytes) { return String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0); }
    private static String duration(long millis) {
        long seconds = millis / 1000L;
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("target", selectedTarget); state.putString("exportTarget", exportTarget); state.putString("exportId", exportId);
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++;
        disk.shutdown();
        super.onDestroy();
    }
}
