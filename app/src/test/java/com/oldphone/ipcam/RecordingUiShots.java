package com.oldphone.ipcam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Properties;

/**
 * Renders the real activities. The emulator on this host never reached adb,
 * so these images come from Robolectric plus Roborazzi, not a device framebuffer.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, qualifiers = "w360dp-h800dp-port-xxhdpi")
public class RecordingUiShots {
    static {
        System.setProperty("roborazzi.test.record", "true");
    }

    @Test public void light() throws Exception { capture("light"); }

    @Test
    @Config(sdk = 33, qualifiers = "w360dp-h800dp-port-night-xxhdpi")
    public void dark() throws Exception { capture("dark"); }

    private void capture(String theme) throws Exception {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.class, "MODEL", "旧手机 Pixel 3");
        home(theme, "home-idle", false, false, false);
        home(theme, "home-streaming", true, false, false);
        home(theme, "home-recording", false, true, false);
        home(theme, "home-stream-rec", true, true, false);
        home(theme, "home-paused", true, false, true);
        home(theme, "home-res", true, false, false);

        prepare();
        screenNumbers();
        Intent settings = new Intent();
        ActivityControllerHold<RecordingSettingsActivity> plain = launch(RecordingSettingsActivity.class, settings);
        try {
            settle(6);
            check(contains(root(plain.activity), "开始录制"), "settings are missing 开始录制");
            check(contains(root(plain.activity), "满足任一"), "settings are missing 满足任一");
            check(contains(root(plain.activity), "画面变动"), "settings are missing motion");
            shot(plain.activity, theme, "settings", true);
        } finally { plain.close(); }

        prepare();
        screenNumbers();
        ScreenSnapshot.brightness = 42;
        Intent light = new Intent();
        light.putExtra(RecordingSettingsActivity.EXTRA_PAGE_SHOT, "settings-light");
        ActivityControllerHold<RecordingSettingsActivity> lightPage = launch(RecordingSettingsActivity.class, light);
        try {
            settle(6);
            check(contains(root(lightPage.activity), "光感控制录制"), "light panel is missing");
            check(contains(root(lightPage.activity), "画面亮度"), "frame method is missing");
            shot(lightPage.activity, theme, "settings-light", true);
        } finally { lightPage.close(); }

        prepare();
        screenNumbers();
        ScreenSnapshot.brightness = 42;
        Intent recording = new Intent();
        recording.putExtra(RecordingSettingsActivity.EXTRA_PAGE_SHOT, "settings-recording");
        ActivityControllerHold<RecordingSettingsActivity> recordingPage = launch(RecordingSettingsActivity.class, recording);
        try {
            settle(6);
            check(contains(root(recordingPage.activity), "停止录制"), "recording card is not active");
            check(contains(root(recordingPage.activity), "全部满足"), "match-all is missing");
            check(contains(root(recordingPage.activity), "光线传感器"), "sensor method is missing");
            shot(recordingPage.activity, theme, "settings-recording", true);
        } finally { recordingPage.close(); }

        prepare();
        screenNumbers();
        ScreenSnapshot.usedSpace = "37.6 GB";
        ScreenSnapshot.freeSpace = "23.4 GB";
        ScreenSnapshot.usedPercent = 62;
        Intent advanced = new Intent();
        advanced.putExtra(RecordingSettingsActivity.EXTRA_PAGE_SHOT, "settings-advanced");
        ActivityControllerHold<RecordingSettingsActivity> advancedPage = launch(RecordingSettingsActivity.class, advanced);
        try {
            settle(6);
            check(contains(root(advancedPage.activity), "存储空间"), "advanced storage is missing");
            check(contains(root(advancedPage.activity), "自动清理"), "advanced cleanup is missing");
            shot(advancedPage.activity, theme, "settings-advanced", true);
        } finally { advancedPage.close(); }

        prepare();
        clearRecordings();
        ActivityControllerHold<RecordingsActivity> empty = launch(RecordingsActivity.class);
        try {
            waitUntil(root(empty.activity), "还没有录像", 40);
            shot(empty.activity, theme, "list-empty", false);
        } finally { empty.close(); }

        prepare();
        seedRecordings();
        ActivityControllerHold<RecordingsActivity> list = launch(RecordingsActivity.class);
        try {
            waitUntil(root(list.activity), "今天", 40);
            waitUntil(root(list.activity), "画面变动", 20);
            waitUntil(root(list.activity), "光感触发", 20);
            shot(list.activity, theme, "list", true);
            ListView rows = (ListView) list.activity.findViewById(R.id.recordingList);
            check(rows.getOnItemLongClickListener() != null, "recordings list has no long-press");
            int clip = firstClip(rows);
            rows.getOnItemLongClickListener().onItemLongClick(rows, rows.getChildAt(clip), clip, clip);
            settle(4);
            check(contains(root(list.activity), "已选择 1"), "multi-select did not open");
            check(contains(root(list.activity), "导出"), "multi-select is missing export");
            shot(list.activity, theme, "list-select", false);
        } finally { list.close(); }

        prepare();
        screenNumbers();
        Prefs.setOutputSize(1920, 1080);
        Intent stream = new Intent();
        stream.putExtra(MainActivity.EXTRA_STREAM_PAGE, true);
        stream.putExtra(MainActivity.EXTRA_PAGE_SHOT, "stream");
        ActivityControllerHold<MainActivity> streamPage = launch(MainActivity.class, stream);
        try {
            settle(6);
            check(contains(root(streamPage.activity), "画面与推流"), "stream page has no title");
            check(contains(root(streamPage.activity), "推流分辨率"), "stream page is missing resolution");
            check(contains(root(streamPage.activity), "两路同时推"), "stream page is missing both addresses");
            check(contains(root(streamPage.activity), "推送音频到 HA"), "HA audio row is missing");
            shot(streamPage.activity, theme, "stream", true);
        } finally { streamPage.close(); }

        prepare();
        screenNumbers();
        CameraStreamService.running = true;
        CameraStreamService.streamSinceMs = System.currentTimeMillis() - (83 * 60 + 45) * 1000L;
        Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_BOTH);
        Prefs.setBool(Prefs.KEY_STREAM_LIGHT, true);
        Prefs.setOutputSize(1920, 1080);
        Intent push = new Intent();
        push.putExtra(MainActivity.EXTRA_STREAM_PAGE, true);
        push.putExtra(MainActivity.EXTRA_PAGE_SHOT, "stream-push");
        ActivityControllerHold<MainActivity> pushPage = launch(MainActivity.class, push);
        try {
            settle(6);
            check(contains(root(pushPage.activity), "光感控制推流"), "stream light switch is missing");
            check(contains(root(pushPage.activity), "去录制设置调整"), "light summary link is missing");
            shot(pushPage.activity, theme, "stream-push", true);
        } finally { pushPage.close(); }
    }

    private void home(String theme, String name, boolean streaming, boolean recording, boolean paused) throws Exception {
        prepare();
        screenNumbers();
        Prefs.setOutputSize(1920, 1080);
        Prefs.setBool(Prefs.KEY_MICROPHONE, true);
        Prefs.setBool(Prefs.KEY_MOTION, true);
        Prefs.setBool(Prefs.KEY_SOUND, true);
        Prefs.setInt(Prefs.KEY_MODE, streaming ? Prefs.MODE_BOTH : Prefs.MODE_LOCAL);
        if (streaming) {
            CameraStreamService.running = true;
            CameraStreamService.streamSinceMs = System.currentTimeMillis() - (1 * 3600 + 23 * 60 + 45) * 1000L;
        }
        if (recording) {
            CameraStreamService.recordingNow = true;
            CameraStreamService.recordingSinceMs = System.currentTimeMillis() - (12 * 60 + 34) * 1000L;
        }
        if (paused) {
            Prefs.setBool(Prefs.KEY_STREAM_LIGHT, true);
            Prefs.setStr(Prefs.KEY_LIGHT_METHOD, LightPolicy.SENSOR);
            ScreenSnapshot.brightness = 12;
            CameraStreamService.lightPaused = true;
        }
        Intent intent = new Intent();
        if ("home-res".equals(name)) intent.putExtra(MainActivity.EXTRA_PAGE_SHOT, "home-res");
        ActivityControllerHold<MainActivity> page = launch(MainActivity.class, intent);
        try {
            settle(6);
            check(contains(root(page.activity), "开始推流") || contains(root(page.activity), "停止推流"),
                    name + " is missing the stream button");
            check(contains(root(page.activity), "录制声音"), name + " is missing 录制声音");
            if ("home-res".equals(name)) check(contains(root(page.activity), "推流分辨率"), "resolution menu is closed");
            if (paused) check(contains(root(page.activity), "亮度不足"), "paused state is missing");
            shot(page.activity, theme, name, "home-streaming".equals(name));
        } finally { page.close(); }
    }

    private static void screenNumbers() {
        ScreenSnapshot.freeSpace = "23.4 GB";
        ScreenSnapshot.todayClips = 4;
        ScreenSnapshot.totalClips = 9;
        ScreenSnapshot.clock = "2026-10-22 10:06:21";
        ScreenSnapshot.freezeMotion = true;
        ScreenSnapshot.composeChrome = true;
        ScreenSnapshot.brightness = 42;
        if (ScreenSnapshot.preview == null) {
            java.io.InputStream input = RecordingUiShots.class.getResourceAsStream("/preview-living.png");
            check(input != null, "preview placeholder is missing");
            ScreenSnapshot.preview = BitmapFactory.decodeStream(input);
            check(ScreenSnapshot.preview != null, "preview placeholder did not decode");
        }
    }

    private static void prepare() {
        Prefs.init(RuntimeEnvironment.getApplication());
        Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_LOCAL);
        Prefs.setBool(Prefs.KEY_MICROPHONE, true);
        Prefs.setInt(Prefs.KEY_RECORDING, 2);
        Prefs.setBool(Prefs.KEY_MOTION, true);
        Prefs.setBool(Prefs.KEY_SOUND, true);
        Prefs.setBool(Prefs.KEY_MATCH_ALL, false);
        Prefs.setBool(Prefs.KEY_RECORD_LIGHT, false);
        Prefs.setBool(Prefs.KEY_STREAM_LIGHT, false);
        Prefs.setStr(Prefs.KEY_LIGHT_METHOD, LightPolicy.FRAME);
        Prefs.setInt(Prefs.KEY_LIGHT_THRESHOLD, 30);
        Prefs.setOutputSize(1920, 1080);
        CameraStreamService.running = false;
        CameraStreamService.recordingNow = false;
        CameraStreamService.recordingSinceMs = 0;
        CameraStreamService.streamSinceMs = 0;
        CameraStreamService.lightPaused = false;
        CameraStreamService.frameBrightness = 42;
        CameraStreamService.sensorBrightness = -1;
        ScreenSnapshot.clear();
    }

    private static void seedToday() throws Exception {
        clearRecordings();
        File root = recordingsRoot();
        long now = System.currentTimeMillis();
        clip(root, "1729000000011-aaaabbbb-1111-2222-3333-444455556661",
                now - 180_000L, 20_000L, "motion", "画面变动", false, false);
        clip(root, "1729000000012-bbbbcccc-1111-2222-3333-444455556662",
                now - 120_000L, 20_000L, "sound", "声音触发", false, false);
        clip(root, "1729000000013-ccccdddd-1111-2222-3333-444455556663",
                now - 60_000L, 20_000L, "motion,sound", "画面变动", false, false);
    }

    private static void clearRecordings() {
        File root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "recordings");
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) check(file.delete(), "could not clear " + file.getName());
    }

    private static File recordingsRoot() {
        File root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "recordings");
        check(root.isDirectory() || root.mkdirs(), "could not create recordings");
        return root;
    }

    private static void seedRecordings() throws Exception {
        clearRecordings();
        File root = recordingsRoot();
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 20);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long morning = calendar.getTimeInMillis();
        if (morning > System.currentTimeMillis()) morning = System.currentTimeMillis() - 60_000L;
        calendar.add(Calendar.DATE, -1);
        calendar.set(Calendar.HOUR_OF_DAY, 21);
        calendar.set(Calendar.MINUTE, 40);
        clip(root, "1729000000001-aaaabbbb-1111-2222-3333-444455556666",
                morning, 185_000L, "motion", "画面变动 · 12 秒", true, false);
        clip(root, "1729000000002-bbbbcccc-1111-2222-3333-444455556666",
                morning + 3_600_000L, 45_000L, "sound", "声响 · 0.4 秒", false, false);
        clip(root, "1729000000003-ccccdddd-1111-2222-3333-444455556666",
                calendar.getTimeInMillis(), 300_000L, "schedule,sound", "定时", true, true);
        clip(root, "1729000000004-ddddeeee-1111-2222-3333-444455556667",
                morning + 4_000_000L, 86_000L, "light", "光感触发", false, false);
    }

    private static void clip(File root, String id, long start, long duration, String triggers,
                             String events, boolean thumbnail, boolean locked) throws Exception {
        File video = new File(root, id + ".mp4");
        FileOutputStream videoOut = new FileOutputStream(video);
        try {
            byte[] chunk = new byte[64 * 1024];
            chunk[4] = 'f'; chunk[5] = 't'; chunk[6] = 'y'; chunk[7] = 'p';
            for (int i = 0; i < 32; i++) videoOut.write(chunk);
        } finally { videoOut.close(); }
        if (thumbnail) writeJpeg(new File(root, id + ".jpg"));
        Properties props = new Properties();
        props.setProperty("state", "complete");
        props.setProperty("start", Long.toString(start));
        props.setProperty("duration", Long.toString(duration));
        props.setProperty("triggers", triggers);
        props.setProperty("events", events);
        props.setProperty("audio", "true");
        props.setProperty("locked", Boolean.toString(locked));
        props.setProperty("thumbnail", Boolean.toString(thumbnail));
        FileOutputStream meta = new FileOutputStream(new File(root, id + ".meta"));
        try { props.store(meta, "shot"); }
        finally { meta.close(); }
    }

    private static void writeJpeg(File dest) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFC2410C);
        int[] band = new int[320];
        for (int i = 0; i < band.length; i++) band[i] = 0xFF0F766E;
        for (int y = 48; y < 132; y++) bitmap.setPixels(band, 0, 320, 0, y, 320, 1);
        FileOutputStream output = new FileOutputStream(dest);
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "thumbnail jpeg"); }
        finally { output.close(); bitmap.recycle(); }
    }

    private static View root(Activity activity) {
        return activity.getWindow().getDecorView();
    }

    private static <T extends Activity> ActivityControllerHold<T> launch(Class<T> type) {
        return launch(type, null);
    }

    private static <T extends Activity> ActivityControllerHold<T> launch(Class<T> type, Intent intent) {
        org.robolectric.android.controller.ActivityController<T> controller = intent == null
                ? Robolectric.buildActivity(type)
                : Robolectric.buildActivity(type, intent);
        return new ActivityControllerHold<T>(controller.setup());
    }

    private static void settle(int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            ShadowLooper.shadowMainLooper().idle();
            Thread.sleep(30);
        }
    }

    private static void waitUntil(View view, String text, int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            settle(1);
            if (contains(view, text)) return;
        }
        check(false, "timed out waiting for " + text);
    }

    private static int firstClip(ListView rows) {
        for (int i = 0; i < rows.getAdapter().getCount(); i++) {
            if (rows.getAdapter().isEnabled(i)) return i;
        }
        throw new AssertionError("recordings list has no clip row");
    }

    private static CompoundButton labeled(View view, String label) {
        if (view instanceof CompoundButton && label.contentEquals(((CompoundButton) view).getText()))
            return (CompoundButton) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CompoundButton found = labeled(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean contains(View view, String text) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.toString().contains(text)) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (contains(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private static void shot(Activity activity, String theme, String name, boolean full) {
        viewport(activity, file(theme, name));
        if (full) {
            if (activity instanceof RecordingsActivity) listFull((RecordingsActivity) activity, file(theme, name + "-full"));
            else scrollContentOf(activity, file(theme, name + "-full"));
        }
    }

    private static void viewport(Activity activity, File file) {
        View decor = activity.getWindow().getDecorView();
        float density = activity.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(decor.getWidth() / density);
        int heightDp = Math.round(decor.getHeight() / density);
        check(widthDp == 360 && heightDp == 800, file.getName() + " viewport is " + widthDp + "x" + heightDp);
        draw(decor, decor.getWidth(), decor.getHeight(), file);
    }

    private static void listFull(RecordingsActivity activity, File file) {
        ListView rows = (ListView) activity.findViewById(R.id.recordingList);
        View decor = activity.getWindow().getDecorView();
        int[] origin = new int[2];
        rows.getLocationInWindow(origin);
        int headerHeight = Math.max(0, origin[1]);
        int width = decor.getWidth();
        int rowWidth = rows.getWidth() > 0 ? rows.getWidth() : width;
        int body = 0;
        java.util.ArrayList<View> drawn = new java.util.ArrayList<View>();
        java.util.ArrayList<Integer> heights = new java.util.ArrayList<Integer>();
        for (int i = 0; i < rows.getAdapter().getCount(); i++) {
            View row = rows.getAdapter().getView(i, null, rows);
            row.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = Math.max(1, row.getMeasuredHeight());
            row.layout(0, 0, rowWidth, height);
            drawn.add(row);
            heights.add(height);
            body += height;
        }
        int height = Math.max(decor.getHeight(), headerHeight + body);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.clipRect(0, 0, width, headerHeight);
        decor.draw(canvas);
        canvas.restore();
        int top = headerHeight;
        for (int i = 0; i < drawn.size(); i++) {
            canvas.save();
            canvas.translate(origin[0], top);
            drawn.get(i).draw(canvas);
            canvas.restore();
            top += heights.get(i);
        }
        write(bitmap, file);
    }

    private static void window(Activity activity, File file) {
        View decor = activity.getWindow().getDecorView();
        draw(decor, decor.getWidth(), decor.getHeight(), file);
    }

    private static void scrollContentOf(Activity activity, File file) {
        ScrollView scroll = findScroll(root(activity));
        check(scroll != null, "screen has no scroll view");
        View target = scroll.getChildAt(0);
        View decor = activity.getWindow().getDecorView();
        int[] origin = new int[2];
        scroll.getLocationInWindow(origin);
        int width = scroll.getWidth();
        if (width <= 0) width = decor.getWidth();
        target.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredWidth = target.getMeasuredWidth();
        int measuredHeight = target.getMeasuredHeight();
        target.layout(0, 0, measuredWidth, measuredHeight);
        int header = Math.max(0, origin[1]);
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), header + measuredHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.clipRect(0, 0, decor.getWidth(), header);
        decor.draw(canvas);
        canvas.restore();
        canvas.save();
        canvas.translate(origin[0], header);
        target.draw(canvas);
        canvas.restore();
        write(bitmap, file);
    }

    private static ScrollView findScroll(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void scrollContent(Activity activity, File file) {
        ScrollView scroll = (ScrollView) activity.findViewById(R.id.svMain);
        View target = scroll.getChildAt(0);
        int width = scroll.getWidth();
        if (width <= 0) width = activity.getWindow().getDecorView().getWidth();
        target.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredWidth = target.getMeasuredWidth();
        int measuredHeight = target.getMeasuredHeight();
        target.layout(0, 0, measuredWidth, measuredHeight);
        draw(target, measuredWidth, measuredHeight, file);
    }

    private static void content(Activity activity, File file) {
        View parent = activity.findViewById(android.R.id.content);
        View child = ((ViewGroup) parent).getChildAt(0);
        View target = child;
        int width = child.getWidth();
        int height = child.getHeight();
        if (child instanceof ScrollView && ((ScrollView) child).getChildCount() > 0) {
            target = ((ScrollView) child).getChildAt(0);
            if (width <= 0) width = activity.getWindow().getDecorView().getWidth();
            target.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            width = target.getMeasuredWidth();
            height = target.getMeasuredHeight();
            target.layout(0, 0, width, height);
        }
        draw(target, width, height, file);
    }

    private static void draw(View view, int width, int height, File file) {
        check(width > 0 && height > 0, "view has no size: " + file.getName());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            throw new AssertionError("could not create " + file.getParent());
        }
        write(bitmap, file);
    }

    private static void write(Bitmap bitmap, File file) {
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            throw new AssertionError("could not create " + file.getParent());
        }
        RoborazziKt.captureRoboImage(bitmap, file, options());
        check(file.isFile() && file.length() > 2048, "screenshot was not written: " + file);
    }

    private static RoborazziOptions options() {
        return new RoborazziOptions(
                new RoborazziOptions.CaptureType.Screenshot(),
                new RoborazziOptions.ReportOptions(),
                new RoborazziOptions.CompareOptions(),
                new RoborazziOptions.RecordOptions());
    }

    private static File file(String theme, String name) {
        return new File("build/outputs/roborazzi/" + name + "-" + theme + ".png");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ActivityControllerHold<T extends Activity> {
        final T activity;
        private final org.robolectric.android.controller.ActivityController<T> controller;
        ActivityControllerHold(org.robolectric.android.controller.ActivityController<T> controller) {
            this.controller = controller;
            this.activity = controller.get();
        }
        void close() {
            try { controller.pause().destroy(); }
            catch (RuntimeException ignored) { }
        }
    }
}
