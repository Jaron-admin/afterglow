package com.oldphone.ipcam;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Old installs never wrote sound_detection. The missing key must stay off. */
public class SoundTriggerMigrationTest {
    @Test public void missingSoundKeyStaysOffWhenTheMicrophoneIsOn() throws Exception {
        Memory memory = new Memory();
        Field field = Prefs.class.getDeclaredField("sp");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, memory.preferences);
        try {
            memory.values.put(Prefs.KEY_MICROPHONE, true);
            if (Prefs.soundSelected() || Prefs.soundEnabled())
                throw new AssertionError("upgrade without sound_detection must not start selecting sound");
            memory.values.put(Prefs.KEY_SOUND, true);
            if (!Prefs.soundSelected() || !Prefs.soundEnabled())
                throw new AssertionError("an explicit sound_detection value is kept");
            memory.values.put(Prefs.KEY_SOUND, false);
            if (Prefs.soundSelected() || Prefs.soundEnabled())
                throw new AssertionError("a saved off switch stays off");
        } finally {
            field.set(null, previous);
        }
    }

    private static final class Memory implements InvocationHandler {
        final Map<String, Object> values = new HashMap<String, Object>();
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, this);

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("contains".equals(name)) return values.containsKey(args[0]);
            if ("getBoolean".equals(name) || "getInt".equals(name) || "getString".equals(name))
                return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
            throw new UnsupportedOperationException(name);
        }
    }
}
