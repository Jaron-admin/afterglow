package com.oldphone.ipcam;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A memory preference boundary; identity generation and durability are production Prefs. */
public final class DeviceIdentityTest {
    public static void main(String[] args) throws Exception {
        MemoryPreferences memory = new MemoryPreferences();
        Field field = Prefs.class.getDeclaredField("sp");
        field.setAccessible(true);
        field.set(null, memory.preferences);
        String first = deviceId();
        check(UUID.fromString(first).toString().equals(first), "identity must be a canonical UUID");
        check(memory.commits == 1 && memory.applies == 0,
                "identity must be durably committed before it is advertised");
        check(first.equals(deviceId()) && memory.commits == 1, "repeated reads retain identity");
        MemoryPreferences restarted = new MemoryPreferences();
        restarted.values.putAll(memory.values);
        field.set(null, restarted.preferences);
        check(first.equals(deviceId()), "process restart must reuse saved identity");
        check(restarted.commits == 0, "reading saved identity must not rewrite preferences");
        String name = (String) method("deviceName").invoke(null);
        check(name.equals("Afterglow-" + first.substring(0, 8)), "friendly name follows persisted identity");
        MemoryPreferences otherDevice = new MemoryPreferences();
        field.set(null, otherDevice.preferences);
        check(!first.equals(deviceId()), "different installations must not share a fixed identity");
        MemoryPreferences failedDisk = new MemoryPreferences();
        failedDisk.commitSucceeds = false;
        field.set(null, failedDisk.preferences);
        expectFailure("failed durable write must not return an unstable identity");
        expectFailure("a failed commit's in-memory value must not be advertised on the next call");
        field.set(null, null);
        expectFailure("uninitialized preferences must not create an ephemeral identity");
        System.out.println("PASS DeviceIdentityTest (restart, separate installations, durable write failure)");
    }

    private static Method method(String name) {
        try { return Prefs.class.getMethod(name); }
        catch (NoSuchMethodException e) { throw new AssertionError("Missing persistent device identity API: " + name); }
    }
    private static String deviceId() throws Exception {
        try { return (String) method("deviceId").invoke(null); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException) throw (IllegalStateException) e.getCause();
            throw e;
        }
    }
    private static void expectFailure(String message) throws Exception {
        try { deviceId(); throw new AssertionError(message); }
        catch (IllegalStateException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class MemoryPreferences implements InvocationHandler {
        final Map<String, Object> values = new HashMap<String, Object>();
        final Map<String, Object> pending = new HashMap<String, Object>();
        boolean commitSucceeds = true;
        int commits, applies;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, this);
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, this);
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getString".equals(name)) return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
            if ("edit".equals(name)) return editor;
            if ("putString".equals(name)) { pending.put((String) args[0], args[1]); return editor; }
            if ("remove".equals(name)) { pending.put((String) args[0], null); return editor; }
            if ("apply".equals(name) || "commit".equals(name)) {
                // Android updates its memory map even when the subsequent disk write fails.
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
                pending.clear();
                if ("commit".equals(name)) { commits++; return commitSucceeds; }
                applies++;
                return null;
            }
            throw new UnsupportedOperationException(name);
        }
    }
}
