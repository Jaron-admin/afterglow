package com.oldphone.ipcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
    private TextView summary, emptyTitle, emptyBody, selectionCount, titleView, notice;
    private TextView selectButton, selectAllButton;
    private ImageView backButton;
    private Bitmap placeholder;
    private long libraryBytes;
    private View filters;
    private String reasonFilter = "all";
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
        ShotChrome.beforeContent(this);
        if (state != null) {
            selectedTarget = state.getString("target", "");
            selecting = state.getBoolean("selecting");
            addAll(selected, state.getStringArray("selected"));
            addAll(pendingExport, state.getStringArray("export"));
        }
        setContentView(R.layout.activity_recordings);
        ShotChrome.afterContent(this);
        storage = (Spinner) findViewById(R.id.recordingStorage);
        titleView = (TextView) findViewById(R.id.recordingTitle);
        notice = (TextView) findViewById(R.id.recordingNotice);
        filters = findViewById(R.id.recordingFilters);
        buildFilters();
        summary = (TextView) findViewById(R.id.recordingSummary);
        backButton = (ImageView) findViewById(R.id.recordingBack);
        selectButton = (TextView) findViewById(R.id.recordingSelect);
        selectAllButton = (TextView) findViewById(R.id.recordingSelectAll);
        selectButton.setCompoundDrawables(Glyph.drawable(this, "select", 16,
                getResources().getColor(R.color.ink_orange), 2f), null, null, null);
        selectButton.setCompoundDrawablePadding(dp(4));
        backButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (selecting) clearSelection();
                else finish();
            }
        });
        selectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                selecting = true;
                showSelection();
            }
        });
        selectAllButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { toggleAll(); }
        });
        empty = findViewById(R.id.recordingEmpty);
        if (empty instanceof LinearLayout) {
            TextView go = new TextView(this);
            go.setText("去开始录制");
            go.setTextColor(getResources().getColor(R.color.on_primary));
            go.setBackgroundResource(R.drawable.bg_btn_primary);
            go.setGravity(android.view.Gravity.CENTER);
            go.setPadding(dp(16), dp(10), dp(16), dp(10));
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            goLp.topMargin = dp(16);
            go.setLayoutParams(goLp);
            go.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    StreamControls.setRecording(RecordingsActivity.this, true);
                    startActivity(new Intent(RecordingsActivity.this, RecordingSettingsActivity.class));
                }
            });
            ((LinearLayout) empty).addView(go);
        }
        emptyTitle = (TextView) findViewById(R.id.recordingEmptyTitle);
        emptyBody = (TextView) findViewById(R.id.recordingEmptyBody);
        list = (ListView) findViewById(R.id.recordingList);
        list.setVerticalScrollBarEnabled(false);
        if (!night()) {
            int shade = dp(12);
            list.setPadding(dp(16) - shade, list.getPaddingTop(), dp(16) - shade, list.getPaddingBottom());
        }
        selectionBar = findViewById(R.id.recordingSelection);
        selectionCount = (TextView) findViewById(R.id.recordingSelectionCount);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
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
        android.widget.Button export = (android.widget.Button) findViewById(R.id.recordingExport);
        export.setCompoundDrawables(Glyph.drawable(this, "export", 18,
                getResources().getColor(R.color.on_primary), 2f), null, null, null);
        android.widget.Button delete = (android.widget.Button) findViewById(R.id.recordingDelete);
        delete.setCompoundDrawables(Glyph.drawable(this, "trash", 18,
                getResources().getColor(R.color.danger), 2f), null, null, null);
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
        summary.setVisibility(View.GONE);
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
        summary.setVisibility(View.GONE);
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
                            summary.setVisibility(View.VISIBLE);
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
        List<RecordingDayGroups.Item> visible = new ArrayList<RecordingDayGroups.Item>();
        for (RecordingDayGroups.Item item : items) {
            RecordingStore.Clip clip = byId.get(item.id);
            if (clip != null && accepts(clip)) visible.add(item);
        }
        long now = ScreenSnapshot.freezeMotion && ScreenSnapshot.epochMs > 0
                ? ScreenSnapshot.epochMs : System.currentTimeMillis();
        for (RecordingDayGroups.Section section : RecordingDayGroups.group(
                visible, now, TimeZone.getDefault())) {
            rows.add(new Row(section.title, dateLine(section.day, section.title), section.items.size()));
            for (RecordingDayGroups.Item item : section.items) rows.add(new Row(byId.get(item.id)));
        }
        selected.retainAll(byId.keySet());
        if (selected.isEmpty()) selecting = false;
        libraryBytes = total;
        adapter.notifyDataSetChanged();
        summary.setVisibility(View.GONE);
        showEmpty(false);
        showSelection();
    }

    /** Date line beside 今天/昨天, or the weekday beside an older date. Does not change group titles. */
    private static String dateLine(String day, String title) {
        String[] parts = day == null ? new String[0] : day.split("-");
        if (parts.length != 3) return "";
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int date = Integer.parseInt(parts[2]);
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, date, 12, 0, 0);
        String week = "周" + "日一二三四五六".charAt(calendar.get(Calendar.DAY_OF_WEEK) - 1);
        if ("今天".equals(title) || "昨天".equals(title)) return month + "月" + date + "日 " + week;
        return week;
    }

    private void showEmpty(boolean noStorage) {
        boolean show = noStorage || rows.isEmpty();
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyTitle.setText(noStorage ? R.string.recordings_no_storage_title : R.string.recordings_empty_title);
        emptyBody.setText(noStorage ? R.string.recordings_no_storage_body : R.string.recordings_empty_body);
        if (noStorage) {
            summary.setVisibility(View.VISIBLE);
            summary.setText("没有可用存储");
        }
    }

    private void toggleAll() {
        if (allSelected()) {
            selected.clear();
            selecting = false;
        } else {
            selecting = true;
            for (Row row : rows) if (row.clip != null) selected.add(row.clip.id);
        }
        showSelection();
    }

    private boolean allSelected() {
        int visible = 0;
        for (Row row : rows) {
            if (row.clip == null) continue;
            visible++;
            if (!selected.contains(row.clip.id)) return false;
        }
        return visible > 0;
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
        selectionCount.setText("已选择 " + selected.size() + " 项");
        if (titleView != null) titleView.setText(selecting ? "已选择 " + selected.size() + " 项" : "录像列表");
        if (notice != null) {
            if (selecting) notice.setText("共 " + clips.size() + " · 点按勾选，可导出或删除");
            else if (clips.isEmpty()) notice.setText("");
            else notice.setText("共 " + clips.size() + " 段 · " + listSize(libraryBytes) + " · 长按可多选");
        }
        boolean showFilters = !selecting && !rows.isEmpty();
        if (filters != null) filters.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        if (selectButton != null) selectButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (selectAllButton != null) {
            selectAllButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
            selectAllButton.setText(allSelected() ? "取消全选" : "全选");
        }
        if (backButton != null) {
            backButton.setImageDrawable(Glyph.drawable(this, selecting ? "close" : "back", 22,
                    getResources().getColor(R.color.on_surface), 2f));
            backButton.setContentDescription(selecting ? "取消选择" : "返回");
        }
        adapter.notifyDataSetChanged();
    }

    private boolean accepts(RecordingStore.Clip clip) {
        if ("all".equals(reasonFilter)) return true;
        String triggers = clip.triggers == null ? "" : clip.triggers;
        if ("motion".equals(reasonFilter)) return hasCode(triggers, "motion") || (clip.reasons != null && clip.reasons.contains("画面"));
        if ("sound".equals(reasonFilter)) return hasCode(triggers, "sound") || (clip.reasons != null && clip.reasons.contains("声"));
        if ("timer".equals(reasonFilter)) return hasCode(triggers, "schedule") || (clip.reasons != null && clip.reasons.contains("定时"));
        if ("light".equals(reasonFilter)) return hasCode(triggers, "light") || (clip.reasons != null && clip.reasons.contains("光感"));
        return true;
    }

    private static boolean hasCode(String triggers, String code) {
        int from = 0;
        while (from < triggers.length()) {
            int comma = triggers.indexOf(',', from);
            if (comma < 0) comma = triggers.length();
            if (code.equals(triggers.substring(from, comma).trim())) return true;
            from = comma + 1;
        }
        return false;
    }

    private void buildFilters() {
        if (!(filters instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) filters;
        row.removeAllViews();
        addFilter(row, "all", "全部");
        addFilter(row, "motion", "画面");
        addFilter(row, "sound", "声音");
        addFilter(row, "timer", "定时");
        addFilter(row, "light", "光感");
    }

    private void addFilter(LinearLayout row, final String code, String label) {
        boolean on = code.equals(reasonFilter);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(17));
        int ink = R.color.on_surface_variant;
        if (!on) {
            background.setColor(0);
            background.setStroke(Math.max(1, dp(1)), getResources().getColor(R.color.outline));
        } else if ("motion".equals(code)) {
            background.setColor(getResources().getColor(R.color.tonal_orange));
            ink = R.color.ink_orange;
        } else if ("sound".equals(code)) {
            background.setColor(getResources().getColor(R.color.tonal_cyan));
            ink = R.color.ink_cyan;
        } else if ("timer".equals(code)) {
            background.setColor(getResources().getColor(R.color.tonal_purple));
            ink = R.color.ink_purple;
        } else if ("light".equals(code)) {
            background.setColor(getResources().getColor(R.color.tonal_amber));
            ink = R.color.ink_amber;
        } else {
            background.setColor(getResources().getColor(R.color.surface_3));
            ink = R.color.on_surface;
        }
        chip.setBackground(background);
        int dot = filterDot(code);
        if (dot != 0) {
            View mark = new View(this);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(getResources().getColor(dot));
            mark.setBackground(oval);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(4);
            chip.addView(mark, dotLp);
        }
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(13);
        name.setTextColor(getResources().getColor(ink));
        name.setSingleLine(true);
        chip.addView(name);
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                reasonFilter = code;
                buildFilters();
                rebuild();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lp.rightMargin = "light".equals(code) ? 0 : dp(6);
        row.addView(chip, lp);
    }

    private int filterDot(String code) {
        if ("motion".equals(code)) return R.color.primary;
        if ("sound".equals(code)) return R.color.secondary;
        if ("timer".equals(code)) return R.color.purple;
        if ("light".equals(code)) return R.color.amber;
        return 0;
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
                        else thumbs.put(clip.id, rounded(shown));
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

    /** Decimal megabytes for the list. {@link #size(long)} stays binary for other callers. */
    static String listSize(long bytes) {
        if (bytes < 1000) return bytes + " B";
        if (bytes < 1_000_000L) return String.format(Locale.US, "%.0f KB", bytes / 1000.0);
        if (bytes < 1_000_000_000L) {
            double mb = bytes / 1_000_000.0;
            if (Math.abs(mb - Math.rint(mb)) < 0.05) return String.format(Locale.US, "%.0f MB", mb);
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0);
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

    private CharSequence clockText(long millis, boolean locked) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        String hour = String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
        String seconds = String.format(Locale.US, ":%02d", calendar.get(Calendar.SECOND));
        SpannableStringBuilder text = new SpannableStringBuilder(hour).append(seconds);
        int from = hour.length();
        text.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.on_surface_variant)),
                from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new AbsoluteSizeSpan(13, true), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.NORMAL), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (locked) text.append("  已锁定");
        return text;
    }

    private static String badge(long millis) {
        long seconds = Math.max(0, millis / 1000L);
        if (seconds >= 3600) return String.format(Locale.US, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private boolean night() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * One card for a date group. Shadow and the rounded stroke belong to the
     * group, so a row does not close its own shadow on the edge it shares.
     * Light keeps the shadow in the row padding. Dark is the same shape with
     * a stroke and no shadow.
     */
    private final class GroupFace extends Drawable {
        private final boolean first;
        private final boolean last;
        private final boolean marked;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GroupFace(boolean first, boolean last, boolean marked) {
            this.first = first;
            this.last = last;
            this.marked = marked;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            boolean night = night();
            int shade = night ? 0 : dp(12);
            float radius = dp(20);
            float top = first ? shade : 0f;
            float bottom = last ? shade : 0f;
            RectF face = new RectF(bounds.left + shade, bounds.top + top,
                    bounds.right - shade, bounds.bottom - bottom);
            if (face.width() <= 0f || face.height() <= 0f) return;
            if (!night) {
                float extend = dp(48);
                RectF shape = new RectF(face);
                if (!first) shape.top -= extend;
                if (!last) shape.bottom += extend;
                float shadowRadius = (first || last) ? radius : 0f;
                Motion.lightCardShadow(canvas, shape, shadowRadius, getResources().getDisplayMetrics().density);
            }
            float stroke = Math.max(1f, dp(1));
            float inset = stroke / 2f;
            // Shared edges stay flush so the page cannot show between rows.
            RectF box = new RectF(face.left + inset, first ? face.top + inset : face.top,
                    face.right - inset, last ? face.bottom - inset : face.bottom);
            float topR = first ? radius : 0f;
            float botR = last ? radius : 0f;
            float[] radii = new float[] {topR, topR, topR, topR, botR, botR, botR, botR};
            path.reset();
            path.addRoundRect(box, radii, Path.Direction.CW);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getResources().getColor(marked ? R.color.selection_bg : R.color.surface));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setColor(getResources().getColor(R.color.outline));
            strokeOutline(canvas, box, radius);
        }

        /** Outer card edge only. The shared row boundary stays open for the hairline. */
        private void strokeOutline(Canvas canvas, RectF box, float radius) {
            if (first && last) {
                canvas.drawRoundRect(box, radius, radius, paint);
                return;
            }
            float l = box.left;
            float t = box.top;
            float r = box.right;
            float b = box.bottom;
            if (!first && !last) {
                canvas.drawLine(l, t, l, b, paint);
                canvas.drawLine(r, t, r, b, paint);
                return;
            }
            path.reset();
            if (first) {
                path.moveTo(r, b);
                path.lineTo(r, t + radius);
                path.arcTo(new RectF(r - 2f * radius, t, r, t + 2f * radius), 0f, -90f);
                path.lineTo(l + radius, t);
                path.arcTo(new RectF(l, t, l + 2f * radius, t + 2f * radius), 270f, -90f);
                path.lineTo(l, b);
            } else {
                path.moveTo(l, t);
                path.lineTo(l, b - radius);
                path.arcTo(new RectF(l, b - 2f * radius, l + 2f * radius, b), 180f, -90f);
                path.lineTo(r - radius, b);
                path.arcTo(new RectF(r - 2f * radius, b - 2f * radius, r, b), 90f, -90f);
                path.lineTo(r, t);
            }
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

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
        final String dateLine;
        final int count;
        final RecordingStore.Clip clip;
        Row(String header, String dateLine, int count) {
            this.header = header;
            this.dateLine = dateLine;
            this.count = count;
            this.clip = null;
        }
        Row(RecordingStore.Clip clip) {
            this.header = null;
            this.dateLine = null;
            this.count = 0;
            this.clip = clip;
        }
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
                View header = convert != null && convert.findViewById(R.id.recordingHeaderTitle) != null
                        ? convert
                        : getLayoutInflater().inflate(R.layout.item_recording_header, parent, false);
                ((TextView) header.findViewById(R.id.recordingHeaderTitle)).setText(row.header);
                ((TextView) header.findViewById(R.id.recordingHeaderDate)).setText(row.dateLine);
                ((TextView) header.findViewById(R.id.recordingHeaderCount)).setText(row.count + " 段");
                if (!night()) header.setPadding(dp(16), dp(8), dp(16), 0);
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
            TextView durationView = (TextView) view.findViewById(R.id.recordingDuration);
            time.setText(clockText(clip.startTimeMs, clip.locked));
            meta.setText(listSize(clip.bytes));
            if (durationView != null) durationView.setText(badge(clip.durationMs));
            bindTags(tags, clip);
            Bitmap bitmap = thumbs.get(clip.id);
            if (bitmap != null) thumb.setImageBitmap(bitmap);
            else {
                thumb.setImageBitmap(scenePlaceholder());
                requestThumb(clip);
            }
            lock.setVisibility(View.GONE);
            lock.setText(clip.locked ? "解锁" : "锁定");
            lock.setContentDescription(clip.locked ? "解除锁定" : "锁定这段录像，避免被自动清理");
            lock.setOnClickListener(new View.OnClickListener() {
                public void onClick(View button) { lock(clip); }
            });
            ImageView open = (ImageView) view.findViewById(R.id.recordingOpen);
            open.setImageDrawable(Glyph.drawable(RecordingsActivity.this, "open", 18,
                    getResources().getColor(R.color.on_surface_variant), 2f));
            open.setVisibility(selecting ? View.GONE : View.VISIBLE);
            mark.setVisibility(selecting ? View.VISIBLE : View.GONE);
            mark.setChecked(selected.contains(clip.id));
            boolean first = position == 0 || rows.get(position - 1).clip == null;
            boolean last = position == rows.size() - 1 || rows.get(position + 1).clip == null;
            boolean marked = selected.contains(clip.id);
            if (night()) {
                view.setPadding(0, 0, 0, 0);
            } else {
                int shade = dp(12);
                view.setPadding(shade, first ? shade : 0, shade, last ? shade : 0);
            }
            view.setBackground(new GroupFace(first, last, marked));
            View divider = view.findViewById(R.id.recordingDivider);
            if (divider != null) divider.setVisibility(last ? View.GONE : View.VISIBLE);
            StringBuilder spoken = new StringBuilder();
            spoken.append(clock(clip.startTimeMs)).append("，").append(duration(clip.durationMs));
            for (int i = 0; i < tags.getChildCount(); i++) {
                TextView label = tagLabel(tags.getChildAt(i));
                if (label != null) spoken.append("，").append(label.getText());
            }
            view.setContentDescription(spoken.toString());
            return view;
        }
    }

    private Bitmap scenePlaceholder() {
        if (placeholder == null) placeholder = rounded(SceneThumb.bitmap("living", dp(104), dp(64)));
        return placeholder;
    }

    private Bitmap rounded(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = Math.min(width, height) * 12f / 64f;
        canvas.drawRoundRect(new RectF(0, 0, width, height), radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, 0, 0, paint);
        if (source != placeholder) source.recycle();
        return out;
    }

    private void bindTags(LinearLayout tags, RecordingStore.Clip clip) {
        tags.removeAllViews();
        for (RecordingReasonLabels.Label label : RecordingReasonLabels.labels(clip.triggers, clip.reasons)) {
            int background = R.drawable.bg_tag_motion;
            int color = R.color.tag_motion_fg;
            String icon = "motion";
            if ("sound".equals(label.code)) {
                background = R.drawable.bg_tag_sound;
                color = R.color.tag_sound_fg;
                icon = "sound";
            } else if ("schedule".equals(label.code)) {
                background = R.drawable.bg_tag_schedule;
                color = R.color.tag_schedule_fg;
                icon = "clock";
            } else if ("light".equals(label.code)) {
                background = R.drawable.bg_tag_light;
                color = R.color.tag_light_fg;
                icon = "sun";
            }
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(8), dp(2), dp(8), dp(2));
            chip.setBackgroundResource(background);
            ImageView glyph = new ImageView(this);
            glyph.setImageDrawable(Glyph.drawable(this, icon, 12, getResources().getColor(color), 2.6f));
            chip.addView(glyph, new LinearLayout.LayoutParams(dp(12), dp(12)));
            TextView name = new TextView(this);
            name.setText(label.text);
            name.setTextSize(11);
            name.setTextColor(getResources().getColor(color));
            name.setPadding(dp(4), 0, 0, 0);
            chip.addView(name);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(6);
            tags.addView(chip, params);
        }
    }

    private static TextView tagLabel(View chip) {
        if (chip instanceof TextView) return (TextView) chip;
        if (chip instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) chip;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof TextView) return (TextView) group.getChildAt(i);
            }
        }
        return null;
    }
}
