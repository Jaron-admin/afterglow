package com.oldphone.ipcam;

/** Stable trigger codes stored beside the human-readable event lines. */
public final class RecordingReasons {
    private RecordingReasons() {}

    public static String codes(String reasons, boolean scheduledWindow) {
        StringBuilder codes = new StringBuilder();
        String text = reasons == null ? "" : reasons;
        if (text.contains("画面变动") || token(text, "motion")) add(codes, "motion");
        if (text.contains("声响") || text.contains("声音触发") || token(text, "sound")) add(codes, "sound");
        if (scheduledWindow || text.contains("定时") || token(text, "schedule")) add(codes, "schedule");
        return codes.toString();
    }

    private static void add(StringBuilder codes, String code) {
        if (codes.length() > 0) codes.append(',');
        codes.append(code);
    }

    private static boolean token(String text, String word) {
        int from = 0;
        while (from <= text.length() - word.length()) {
            int at = text.indexOf(word, from);
            if (at < 0) return false;
            int end = at + word.length();
            if ((at == 0 || !isWord(text.charAt(at - 1))) && (end == text.length() || !isWord(text.charAt(end))))
                return true;
            from = at + 1;
        }
        return false;
    }

    private static boolean isWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
