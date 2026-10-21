package com.oldphone.ipcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Date-grouped recordings. Playback stays in another app. */
public final class RecordingsActivity extends Activity {
    private static final int EXPORT_PERMISSION = 57;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final List<RecordingStore.Clip> clips = new ArrayList<RecordingStore.Clip>();
    private final List<Row> rows = new ArrayList<Row>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();
    private final ArrayList<String> pendingExport = new ArrayList<String>();
    private final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();
    private final Set<String> thumbBusy = new HashSet<String>();
    private final Set<String> thumbFailed = new HashSet<String>();
    private List<RecordingStore.Target> targets = new ArrayList<RecordingStore.Target>();
    private Spinner storage;
    private TextView summary, emptyTitle, emptyBody, selectionCount;
    private View empty, selectionBar;
    private ListView list;
    private RowAdapter adapter;
    private String selectedTarget = "";
    private int generation;
    private boolean destroyed, selecting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.init(this);
        ActivityOrientation.apply(this);
        if (state != null) {
            selectedTarget = state.getString("target", "");
            selecting = state.getBoolean("selecting");
            addAll(selected, state.getStringArray("selected"));
            addAll(pendingExport, state.getStringArray("export"));
        }
        setContentView(R.layout.activity_recordings);
        storage = (Spinner) findViewById(R.id.recordingStorage);
        summary = (TextView) findViewById(R.id.recordingSummary);
        empty = findViewById(R.id.recordingEmpty);
        emptyTitle = (TextView) findViewById(R.id.recordingEmptyTitle);
        emptyBody = (TextView) findViewById(R.id.recordingEmptyBody);
        list = (ListView) findViewById(R.id.recordingList);
        selectionBar = findViewById(R.id.recordingSelection);
        selectionCount = (TextView) findViewById(R.id.recordingSelectionCount);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                RecordingStore.Clip clip = rows.get(position).clip;
                if (clip == null) return;
                if (selecting) toggle(clip.id);
                else open(clip);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                RecordingStore.Clip clip = rows.get(position).clip;
                if (clip == null) return true;
                selecting = true;
                selected.add(clip.id);
                showSelection();
                return true;
            }
        });
        storage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < targets.size() && !targets.get(position).id.equals(selectedTarget)) {
                    selectedTarget = targets.get(position).id;
                    clearSelection();
                    reloadClips();
                }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        findViewById(R.id.recordingExport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { exportSelected(); }
        });
        findViewById(R.id.recordingShare).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { share(chosen()); }
        });
        findViewById(R.id.recordingDelete).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { confirmDelete(); }
        });
        findViewById(R.id.recordingCancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { clearSelection(); }
        });
        showSelection();
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
                        int index = 0;
                        for (int i = 0; i < targets.size(); i++) {
                            labels.add(targets.get(i).label);
                            if (targets.get(i).id.equals(selectedTarget)) index = i;
                        }
                        ArrayAdapter<String> adapter = new ArrayAdapter<String>(RecordingsActivity.this,
                                R.layout.item_spinner, labels);
                        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
                        storage.setAdapter(adapter);
                        if (targets.isEmpty()) showEmpty(true);
                        else {
                            selectedTarget = targets.get(index).id;
                            storage.setSelection(index);
                            reloadClips();
                        }
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
                    final List<RecordingStore.Clip> found = RecordingStore.open(
                            getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA).list();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || token != generation || !target.equals(selectedTarget)) return;
                            clips.clear();
                            clips.addAll(found);
                            rebuild();
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed || token != generation) return;
                            clips.clear();
                            rows.clear();
                            adapter.notifyDataSetChanged();
                            summary.setText(message(error));
                            emptyTitle.setText("暂时读不到录像");
                            emptyBody.setText(message(error));
                            empty.setVisibility(View.VISIBLE);
                            list.setVisibility(View.GONE);
                        }
                    });
                }
            }
        });
    }

    private void rebuild() {
        Map<String, RecordingStore.Clip> byId = new HashMap<String, RecordingStore.Clip>();
        List<RecordingDayGroups.Item> items = new ArrayList<RecordingDayGroups.Item>();
        long total = 0;
        for (RecordingStore.Clip clip : clips) {
            byId.put(clip.id, clip);
            items.add(new RecordingDayGroups.Item(clip.id, clip.startTimeMs));
            total += clip.bytes;
        }
        rows.clear();
        for (RecordingDayGroups.Section section : RecordingDayGroups.group(
                items, System.currentTimeMillis(), TimeZone.getDefault())) {
            rows.add(new Row(section.title));
            for (RecordingDayGroups.Item item : section.items) rows.add(new Row(byId.get(item.id)));
        }
        selected.retainAll(byId.keySet());
        if (selected.isEmpty()) selecting = false;
        adapter.notifyDataSetChanged();
        summary.setText(clips.isEmpty()
                ? "还没有完整结束的片段"
                : clips.size() + " 段 · " + size(total));
        showEmpty(false);
        showSelection();
    }

    private void showEmpty(boolean noStorage) {
        boolean show = noStorage || rows.isEmpty();
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyTitle.setText(noStorage ? R.string.recordings_no_storage_title : R.string.recordings_empty_title);
        emptyBody.setText(noStorage ? R.string.recordings_no_storage_body : R.string.recordings_empty_body);
        if (noStorage) summary.setText("没有可用存储");
    }

    private void toggle(String id) {
        if (!selected.add(id)) selected.remove(id);
        if (selected.isEmpty()) selecting = false;
        showSelection();
    }

    private void clearSelection() {
        selecting = false;
        selected.clear();
        showSelection();
    }

    private void showSelection() {
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionCount.setText("已选 " + selected.size());
        adapter.notifyDataSetChanged();
    }

    private List<RecordingStore.Clip> chosen() {
        List<RecordingStore.Clip> found = new ArrayList<RecordingStore.Clip>();
        for (String id : selected) {
            RecordingStore.Clip clip = clip(id);
            if (clip != null) found.add(clip);
        }
        return found;
    }

    private RecordingStore.Clip clip(String id) {
        for (RecordingStore.Clip clip : clips) if (clip.id.equals(id)) return clip;
        return null;
    }

    private void open(RecordingStore.Clip clip) {
        try {
            Uri uri = RecordingFileProvider.uriFor(selectedTarget, clip.id);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(getContentResolver(), "recording", uri));
            Intent chooser = Intent.createChooser(intent, "用其他应用打开");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.setClipData(intent.getClipData());
            startActivity(chooser);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "没有可以打开视频的应用，请先导出", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "没有可以打开视频的应用，请先导出", Toast.LENGTH_LONG).show();
        }
    }

    private void share(List<RecordingStore.Clip> items) {
        if (items.isEmpty()) return;
        try {
            ArrayList<Uri> uris = new ArrayList<Uri>();
            for (RecordingStore.Clip clip : items)
                uris.add(RecordingFileProvider.uriFor(selectedTarget, clip.id));
            Intent intent = new Intent(items.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            intent.setType("video/mp4");
            if (items.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData data = ClipData.newUri(getContentResolver(), "recording", uris.get(0));
            for (int i = 1; i < uris.size(); i++) data.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(data);
            Intent chooser = Intent.createChooser(intent, "分享录像");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.setClipData(data);
            startActivity(chooser);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "没有可以分享视频的应用，请先导出", Toast.LENGTH_LONG).show();
        } catch (RuntimeException invalid) {
            Toast.makeText(this, "没有可以分享视频的应用，请先导出", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        final List<RecordingStore.Clip> items = chosen();
        if (items.isEmpty()) return;
        int locked = 0;
        for (RecordingStore.Clip clip : items) if (clip.locked) locked++;
        String message = locked == 0 ? "删除后无法恢复。" : "其中 " + locked + " 段已锁定。删除后无法恢复。";
        new AlertDialog.Builder(this).setTitle(items.size() == 1 ? "删除这段录像？" : "删除选中的 " + items.size() + " 段？")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { deleteAll(items); }
                }).show();
    }

    private void deleteAll(final List<RecordingStore.Clip> items) {
        final String target = selectedTarget;
        disk.execute(new Runnable() {
            public void run() {
                try {
                    RecordingStore store = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA);
                    for (RecordingStore.Clip clip : items) store.delete(clip.id);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) return;
                            clearSelection();
                            reloadClips();
                        }
                    });
                } catch (Exception error) { showError(error); }
            }
        });
    }

    private void lock(final RecordingStore.Clip clip) {
        final String target = selectedTarget;
        final boolean next = !clip.locked;
        disk.execute(new Runnable() {
            public void run() {
                try {
                    RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA)
                            .setLocked(clip.id, next);
                    runOnUiThread(new Runnable() {
                        public void run() { if (!destroyed) reloadClips(); }
                    });
                } catch (Exception error) { showError(error); }
            }
        });
    }

    private void exportSelected() {
        pendingExport.clear();
        pendingExport.addAll(selected);
        if (pendingExport.isEmpty()) return;
        if (RecordingExport.needsLegacyWritePermission(Build.VERSION.SDK_INT) && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, EXPORT_PERMISSION);
            return;
        }
        startExport();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != EXPORT_PERMISSION) return;
        if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startExport();
        else Toast.makeText(this, "没有存储权限，无法导出到 Movies/Afterglow", Toast.LENGTH_LONG).show();
    }

    private void startExport() {
        final String target = selectedTarget;
        final List<RecordingStore.Clip> items = new ArrayList<RecordingStore.Clip>();
        for (String id : pendingExport) {
            RecordingStore.Clip clip = clip(id);
            if (clip != null) items.add(clip);
        }
        pendingExport.clear();
        if (items.isEmpty()) return;
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        disk.execute(new Runnable() {
            public void run() {
                int done = 0;
                String failure = null;
                try {
                    Set<String> taken = existingNames();
                    RecordingStore store = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA);
                    TimeZone zone = TimeZone.getDefault();
                    for (RecordingStore.Clip clip : items) {
                        try {
                            File source = store.registeredFile(clip.id);
                            String name = RecordingExport.displayName(clip.startTimeMs, clip.id, zone);
                            if (Build.VERSION.SDK_INT >= 29) RecordingMediaStore.write(getContentResolver(), source, name, taken);
                            else exportLegacy(source, name, taken);
                            done++;
                        } catch (Exception error) {
                            if (failure == null) failure = message(error);
                        }
                    }
                } catch (Exception error) {
                    failure = message(error);
                }
                final int exported = done;
                final int failed = items.size() - done;
                final String reason = failure;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        if (failed == 0) Toast.makeText(RecordingsActivity.this,
                                "已导出 " + exported + " 段到 Movies/Afterglow，相册和电脑都能看到",
                                Toast.LENGTH_LONG).show();
                        else Toast.makeText(RecordingsActivity.this,
                                "导出了 " + exported + " 段，" + failed + " 段失败"
                                        + (reason == null ? "" : "：" + reason),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private Set<String> existingNames() {
        Set<String> names = new HashSet<String>();
        if (Build.VERSION.SDK_INT >= 29) return RecordingMediaStore.names(getContentResolver());
        File[] files = legacyDir().listFiles();
        if (files != null) for (File file : files) names.add(file.getName());
        return names;
    }

    private void exportLegacy(File source, String name, Set<String> taken) throws IOException {
        File dir = legacyDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建 Movies/Afterglow");
        String unique = RecordingExport.uniqueName(taken, name);
        File dest = new File(dir, unique);
        FileOutputStream output = new FileOutputStream(dest);
        try {
            copy(source, output);
            output.getFD().sync();
        } catch (IOException error) {
            output.close();
            dest.delete();
            throw error;
        }
        output.close();
        taken.add(unique);
        MediaScannerConnection.scanFile(this, new String[] {dest.getAbsolutePath()},
                new String[] {"video/mp4"}, null);
    }

    private static File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                RecordingExport.FOLDER);
    }

    private static void copy(File source, OutputStream output) throws IOException {
        FileInputStream input = new FileInputStream(source);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        } finally { input.close(); }
    }

    private void requestThumb(final RecordingStore.Clip clip) {
        if (thumbs.containsKey(clip.id) || thumbFailed.contains(clip.id) || thumbBusy.contains(clip.id)) return;
        thumbBusy.add(clip.id);
        final String target = selectedTarget;
        final boolean cached = clip.hasThumbnail;
        disk.execute(new Runnable() {
            public void run() {
                Bitmap bitmap = null;
                try {
                    RecordingStore store = RecordingStore.open(getApplicationContext(), target, RecordingStore.DEFAULT_QUOTA);
                    if (cached) bitmap = decode(store.thumbnail(clip.id));
                    else {
                        byte[] jpeg = frame(store.registeredFile(clip.id));
                        if (jpeg != null) {
                            store.attachThumbnail(clip.id, jpeg);
                            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        }
                    }
                } catch (Exception ignored) { }
                final Bitmap shown = bitmap;
                runOnUiThread(new Runnable() {
                    public void run() {
                        thumbBusy.remove(clip.id);
                        if (destroyed) {
                            if (shown != null) shown.recycle();
                            return;
                        }
                        if (shown == null) thumbFailed.add(clip.id);
                        else thumbs.put(clip.id, shown);
                        if (target.equals(selectedTarget)) adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 480 && options.inSampleSize < 16)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private static byte[] frame(File file) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            Bitmap raw = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) return null;
            Bitmap frame = raw;
            if (raw.getWidth() > 320) {
                int height = Math.max(1, raw.getHeight() * 320 / raw.getWidth());
                frame = Bitmap.createScaledBitmap(raw, 320, height, true);
                if (frame != raw) raw.recycle();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean wrote = frame.compress(Bitmap.CompressFormat.JPEG, 80, output);
            frame.recycle();
            byte[] jpeg = output.toByteArray();
            if (!wrote || jpeg.length < 4 || jpeg.length > 512 * 1024) return null;
            return jpeg;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private void showError(final Exception error) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (!destroyed) Toast.makeText(RecordingsActivity.this, message(error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? "存储操作失败" : error.getMessage();
    }

    private static void addAll(java.util.Collection<String> into, String[] values) {
        if (values != null) for (String value : values) if (value != null) into.add(value);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024d * 1024 * 1024));
    }

    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000L);
        if (seconds >= 3600) return String.format(Locale.US, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String clock(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("target", selectedTarget);
        state.putBoolean("selecting", selecting);
        state.putStringArray("selected", selected.toArray(new String[selected.size()]));
        state.putStringArray("export", pendingExport.toArray(new String[pendingExport.size()]));
    }

    @Override protected void onDestroy() {
        destroyed = true;
        generation++;
        disk.shutdown();
        super.onDestroy();
    }

    private static final class Row {
        final String header;
        final RecordingStore.Clip clip;
        Row(String header) { this.header = header; this.clip = null; }
        Row(RecordingStore.Clip clip) { this.header = null; this.clip = clip; }
    }

    private final class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int position) { return rows.get(position).clip == null ? 0 : 1; }
        public boolean isEnabled(int position) { return rows.get(position).clip != null; }

        public View getView(int position, View convert, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.clip == null) {
                TextView header = convert instanceof TextView ? (TextView) convert
                        : (TextView) getLayoutInflater().inflate(R.layout.item_recording_header, parent, false);
                header.setText(row.header);
                return header;
            }
            View view = convert != null && convert.findViewById(R.id.recordingTime) != null ? convert
                    : getLayoutInflater().inflate(R.layout.item_recording_clip, parent, false);
            final RecordingStore.Clip clip = row.clip;
            TextView time = (TextView) view.findViewById(R.id.recordingTime);
            TextView meta = (TextView) view.findViewById(R.id.recordingMeta);
            LinearLayout tags = (LinearLayout) view.findViewById(R.id.recordingTags);
            ImageView thumb = (ImageView) view.findViewById(R.id.recordingThumb);
            TextView lock = (TextView) view.findViewById(R.id.recordingLock);
            CheckBox mark = (CheckBox) view.findViewById(R.id.recordingMark);
            time.setText(clock(clip.startTimeMs) + (clip.locked ? "  已锁定" : ""));
            meta.setText(duration(clip.durationMs) + " · " + size(clip.bytes)
                    + (clip.withAudio ? " · 有声" : ""));
            bindTags(tags, clip);
            Bitmap bitmap = thumbs.get(clip.id);
            if (bitmap != null) thumb.setImageBitmap(bitmap);
            else {
                thumb.setImageDrawable(null);
                requestThumb(clip);
            }
            lock.setText(clip.locked ? "解锁" : "锁定");
            lock.setContentDescription(clip.locked ? "解除锁定" : "锁定这段录像，避免被自动清理");
            lock.setOnClickListener(new View.OnClickListener() {
                public void onClick(View button) { lock(clip); }
            });
            mark.setVisibility(selecting ? View.VISIBLE : View.GONE);
            mark.setChecked(selected.contains(clip.id));
            view.setBackgroundColor(selected.contains(clip.id)
                    ? getResources().getColor(R.color.selection_bg) : 0);
            StringBuilder spoken = new StringBuilder();
            spoken.append(clock(clip.startTimeMs)).append("，").append(duration(clip.durationMs));
            for (int i = 0; i < tags.getChildCount(); i++) {
                spoken.append("，").append(((TextView) tags.getChildAt(i)).getText());
            }
            view.setContentDescription(spoken.toString());
            return view;
        }
    }

    private void bindTags(LinearLayout tags, RecordingStore.Clip clip) {
        tags.removeAllViews();
        for (RecordingReasonLabels.Label label : RecordingReasonLabels.labels(clip.triggers, clip.reasons)) {
            TextView chip = new TextView(this);
            chip.setText(label.text);
            chip.setTextSize(11);
            chip.setPadding(dp(8), dp(2), dp(8), dp(2));
            int background = R.drawable.bg_tag_motion;
            int color = R.color.tag_motion_fg;
            if ("sound".equals(label.code)) {
                background = R.drawable.bg_tag_sound;
                color = R.color.tag_sound_fg;
            } else if ("schedule".equals(label.code)) {
                background = R.drawable.bg_tag_schedule;
                color = R.color.tag_schedule_fg;
            }
            chip.setBackgroundResource(background);
            chip.setTextColor(getResources().getColor(color));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(6);
            tags.addView(chip, params);
        }
    }
}
