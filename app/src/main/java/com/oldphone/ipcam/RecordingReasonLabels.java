package com.oldphone.ipcam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Short reason chips. The stored sentence can still contain a duration. */
public final class RecordingReasonLabels {
    public static final class Label {
        public final String code;
        public final String text;
        Label(String code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    private RecordingReasonLabels() {}

    public static List<Label> labels(String triggers, String reasons) {
        String source = triggers != null && triggers.trim().length() > 0
                ? triggers : RecordingReasons.codes(reasons, false);
        List<Label> labels = new ArrayList<Label>();
        if (has(source, "motion")) labels.add(new Label("motion", "画面变动"));
        if (has(source, "sound")) labels.add(new Label("sound", "声音触发"));
        if (has(source, "schedule")) labels.add(new Label("schedule", "定时录制"));
        if (has(source, "light")) labels.add(new Label("light", "光感触发"));
        return Collections.unmodifiableList(labels);
    }

    private static boolean has(String codes, String code) {
        int from = 0;
        while (from < codes.length()) {
            int comma = codes.indexOf(',', from);
            if (comma < 0) comma = codes.length();
            if (code.equals(codes.substring(from, comma).trim())) return true;
            from = comma + 1;
        }
        return false;
    }
}
