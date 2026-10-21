package com.oldphone.ipcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only grant for one registered recording. Other apps never see a file path. */
public final class RecordingFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.oldphone.ipcam.recordings";

    public static Uri uriFor(String targetId, String clipId) {
        RecordingUris.parse(java.util.Arrays.asList(RecordingUris.encodeTarget(targetId), clipId));
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(RecordingUris.encodeTarget(targetId)).appendPath(clipId).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.indexOf('w') >= 0) throw new FileNotFoundException("录像只读");
        try {
            RecordingUris.Parsed parsed = RecordingUris.parse(uri.getPathSegments());
            File file = RecordingStore.open(getContext(), parsed.targetId, RecordingStore.DEFAULT_QUOTA)
                    .registeredFile(parsed.clipId);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | RuntimeException unavailable) {
            FileNotFoundException missing = new FileNotFoundException("录像不完整或已不可用");
            missing.initCause(unavailable);
            throw missing;
        }
    }

    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
