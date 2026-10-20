package com.oldphone.ipcam;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class PrefsOutputDefaultsTest {
    public static void main(String[] args) throws Exception {
        // Treating an absent legacy index as explicit index zero would retain
        // the old 4:3 default; overriding stored values would lose user choices.
        MemoryPreferences memory = new MemoryPreferences();
        Field field = Prefs.class.getDeclaredField("sp");
        field.setAccessible(true);
        field.set(null, memory.preferences);
        size(1280, 720, "empty preferences use 16:9");
        memory.values.put(Prefs.KEY_FPS, 3);
        size(1280, 720, "unrelated old settings are not an explicit size");
        memory.values.put(Prefs.KEY_RES, 0);
        size(640, 480, "explicit legacy zero stays 4:3");
        memory.values.put(Prefs.KEY_RES, 2);
        size(320, 240, "legacy indices keep their original meaning");
        Prefs.setOutputSize(900, 1600);
        Prefs.setOutputLayout(2);
        size(900, 1600, "saved custom portrait overrides old index");
        if (Prefs.outputLayout() != 2) throw new AssertionError("custom layout must remain selected");
        memory.values.clear();
        RecordingOptions recording = Prefs.recordingOptions();
        if (Prefs.recordingMode() != 0 || Prefs.microphoneEnabled()
                || recording.withAudio || recording.segmentSeconds != 300)
            throw new AssertionError("upgrade must opt in to recording/microphone; default segments are five minutes");
        memory.values.put(Prefs.KEY_MICROPHONE, true);
        memory.values.put(Prefs.KEY_RECORD_AUDIO, false);
        if (Prefs.recordingOptions().withAudio)
            throw new AssertionError("RTSP microphone may stay on while recorded audio is disabled");
        memory.values.clear();
        Prefs.setOutputSize(640, 480);
        size(640, 480, "saved 1.2 output remains unchanged");
        field.set(null, null);
        size(1280, 720, "uninitialized preferences use the same default");
        System.out.println("PASS PrefsOutputDefaultsTest (new default, explicit legacy migration, saved/custom preference preservation)");
    }
    private static void size(int width, int height, String message) {
        if (Prefs.outputWidth() != width || Prefs.outputHeight() != height)
            throw new AssertionError(message + ": expected " + width + "x" + height
                    + ", got " + Prefs.outputWidth() + "x" + Prefs.outputHeight());
    }

    /** In-memory Android preference boundary; all default/migration behavior is production Prefs. */
    private static final class MemoryPreferences implements InvocationHandler {
        final Map<String,Object> values = new HashMap<String,Object>();
        final Map<String,Object> pending = new HashMap<String,Object>();
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, this);
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, this);

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("contains".equals(name)) return values.containsKey(args[0]);
            if ("getInt".equals(name) || "getBoolean".equals(name) || "getString".equals(name))
                return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
            if ("edit".equals(name)) return editor;
            if ("putInt".equals(name) || "putBoolean".equals(name) || "putString".equals(name)) {
                pending.put((String) args[0], args[1]);
                return editor;
            }
            if ("apply".equals(name) || "commit".equals(name)) {
                values.putAll(pending);
                pending.clear();
                return "commit".equals(name) ? Boolean.TRUE : null;
            }
            throw new UnsupportedOperationException("Unexpected preference call: " + name);
        }
    }
}
