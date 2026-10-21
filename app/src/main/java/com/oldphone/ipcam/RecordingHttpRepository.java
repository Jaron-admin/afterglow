package com.oldphone.ipcam;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Each request rechecks the selected storage; unavailable SD cards never stop live video. */
final class RecordingHttpRepository implements RecordingHttpApi.Repository {
    private final Context context;
    private final String target;
    RecordingHttpRepository(Context context, String target) {
        this.context = context.getApplicationContext(); this.target = target;
    }
    private RecordingStore store() throws IOException {
        return RecordingStore.open(context, target, RecordingStore.DEFAULT_QUOTA);
    }
    @Override public List<RecordingHttpApi.Clip> list() throws IOException {
        List<RecordingHttpApi.Clip> result = new ArrayList<RecordingHttpApi.Clip>();
        for (RecordingStore.Clip clip : store().list()) {
            result.add(new RecordingHttpApi.Clip(clip.id, clip.startTimeMs, clip.durationMs,
                    clip.bytes, clip.locked, clip.withAudio, clip.reasons));
        }
        return result;
    }
    @Override public File file(String id) throws IOException { return store().registeredFile(id); }
}
