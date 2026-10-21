package com.oldphone.ipcam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
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
@Config(sdk = 33, qualifiers = "w360dp-h760dp-port-xxhdpi")
public class RecordingUiShots {
    static {
        System.setProperty("roborazzi.test.record", "true");
    }

    @Test public void light() throws Exception { capture("light"); }

    @Test
    @Config(sdk = 33, qualifiers = "w360dp-h760dp-port-night-xxhdpi")
    public void dark() throws Exception { capture("dark"); }

    private void capture(String theme) throws Exception {
        prepare();
        Prefs.setInt(Prefs.KEY_RECORDING, 2);
        Prefs.setBool(Prefs.KEY_MOTION, true);
        Prefs.setBool(Prefs.KEY_SOUND, true);
        Prefs.setBool(Prefs.KEY_MICROPHONE, true);
        seedToday();
        ActivityControllerHold<MainActivity> main = launch(MainActivity.class);
        try {
            settle(8);
            check(contains(root(main.activity), "录制声音"), "main screen is missing 录制声音");
            check(contains(root(main.activity), "开始"), "main screen is missing the start button");
            check(contains(root(main.activity), "录制设置"), "main screen is missing the settings card");
            check(contains(root(main.activity), "录像列表"), "main screen is missing the library card");
            check(contains(root(main.activity), "画面与推流"), "main screen is missing the stream card");
            check(contains(root(main.activity), "事件录像 · 画面+声音"), "recording card has no status");
            waitUntil(root(main.activity), "今天 3 段", 40);
            check(main.activity.findViewById(R.id.settingsPanel).getVisibility() == View.GONE,
                    "quality settings are still on the home screen");
            scrollContent(main.activity, file(theme, "main"));
        } finally { main.close(); }

        prepare();
        Intent streamIntent = new Intent();
        streamIntent.putExtra(MainActivity.EXTRA_STREAM_PAGE, true);
        ActivityControllerHold<MainActivity> stream = launch(MainActivity.class, streamIntent);
        try {
            settle(8);
            check(contains(root(stream.activity), "画面与推流"), "stream page has no title");
            check(contains(root(stream.activity), "画质与输出"), "stream page is missing quality");
            check(contains(root(stream.activity), "锁定当前方向"), "stream page is missing orientation lock");
            check(stream.activity.findViewById(R.id.cbOrientationLock) instanceof CompoundButton,
                    "orientation lock is not a switch");
            check(!(stream.activity.findViewById(R.id.cbOrientationLock) instanceof android.widget.CheckBox),
                    "orientation lock is still a checkbox");
            check(stream.activity.findViewById(R.id.previewPanel).getVisibility() == View.GONE,
                    "stream page still shows the preview");
            scrollContent(stream.activity, file(theme, "stream"));
        } finally { stream.close(); }

        prepare();
        Prefs.setInt(Prefs.KEY_RECORDING, 2);
        Prefs.setBool(Prefs.KEY_SOUND, true);
        Prefs.setBool(Prefs.KEY_MICROPHONE, false);
        ActivityControllerHold<RecordingSettingsActivity> settings = launch(RecordingSettingsActivity.class);
        try {
            settle(8);
            check(contains(root(settings.activity), "录制方式"), "settings are missing the mode card");
            check(contains(root(settings.activity), "画面变动"), "settings are missing motion");
            check(contains(root(settings.activity), "声音触发"), "settings are missing sound");
            check(!contains(root(settings.activity), "录像里保留声音"), "file-audio switch is still shown");
            CompoundButton sound = labeled(root(settings.activity), "声音触发");
            check(sound != null && !sound.isEnabled(), "sound trigger stays enabled while mic is off");
            check(contains(root(settings.activity), "请先打开主界面的「录制声音」"),
                    "disabled sound trigger has no explanation");
            content(settings.activity, file(theme, "settings"));
            Button advanced = button(root(settings.activity), "展开高级");
            check(advanced != null, "advanced section is missing its expand button");
            advanced.performClick();
            settle(4);
            check(contains(root(settings.activity), "分段"), "advanced section is missing segments");
            check(contains(root(settings.activity), "画质"), "advanced section is missing picture quality");
            check(contains(root(settings.activity), "定时"), "advanced section is missing the schedule");
            content(settings.activity, file(theme, "settings-advanced"));
        } finally { settings.close(); }

        prepare();
        clearRecordings();
        ActivityControllerHold<RecordingsActivity> empty = launch(RecordingsActivity.class);
        try {
            waitUntil(root(empty.activity), "还没有录像", 40);
            window(empty.activity, file(theme, "recordings-empty"));
        } finally { empty.close(); }

        prepare();
        seedRecordings();
        ActivityControllerHold<RecordingsActivity> list = launch(RecordingsActivity.class);
        try {
            waitUntil(root(list.activity), "今天", 40);
            waitUntil(root(list.activity), "画面变动", 20);
            waitUntil(root(list.activity), "昨天", 20);
            window(list.activity, file(theme, "recordings"));
            ListView rows = (ListView) list.activity.findViewById(R.id.recordingList);
            check(rows.getOnItemLongClickListener() != null, "recordings list has no long-press");
            int clip = firstClip(rows);
            rows.getOnItemLongClickListener().onItemLongClick(rows, rows.getChildAt(clip), clip, clip);
            settle(4);
            check(contains(root(list.activity), "已选 1"), "multi-select did not open");
            check(contains(root(list.activity), "导出"), "multi-select is missing export");
            window(list.activity, file(theme, "recordings-selected"));
        } finally { list.close(); }
    }

    private static void prepare() {
        Prefs.init(RuntimeEnvironment.getApplication());
        Prefs.setInt(Prefs.KEY_MODE, Prefs.MODE_LOCAL);
        Prefs.setBool(Prefs.KEY_MICROPHONE, true);
        Prefs.setInt(Prefs.KEY_RECORDING, 0);
        Prefs.setBool(Prefs.KEY_SOUND, false);
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

    private static void window(Activity activity, File file) {
        View decor = activity.getWindow().getDecorView();
        draw(decor, decor.getWidth(), decor.getHeight(), file);
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
