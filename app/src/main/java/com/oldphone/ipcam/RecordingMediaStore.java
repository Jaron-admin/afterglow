package com.oldphone.ipcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/** Android 10+ copy into Movies/Afterglow. Loaded only on those versions. */
final class RecordingMediaStore {
    private RecordingMediaStore() {}

    static Set<String> names(ContentResolver resolver) {
        Set<String> names = new HashSet<String>();
        if (Build.VERSION.SDK_INT < 29) return names;
        Cursor cursor = resolver.query(collection(),
                new String[] {MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? OR " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[] {RecordingExport.RELATIVE_PATH + "/", RecordingExport.RELATIVE_PATH}, null);
        if (cursor == null) return names;
        try {
            int column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String name = column < 0 ? null : cursor.getString(column);
                if (name != null) names.add(name);
            }
        } finally { cursor.close(); }
        return names;
    }

    static void write(ContentResolver resolver, File source, String name, Set<String> taken) throws IOException {
        String unique = RecordingExport.uniqueName(taken, name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, unique);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingExport.RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection(), values);
        if (uri == null) throw new IOException("无法写入 Movies/Afterglow");
        try {
            OutputStream output = resolver.openOutputStream(uri);
            if (output == null) throw new IOException("无法写入 Movies/Afterglow");
            try {
                FileInputStream input = new FileInputStream(source);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.flush();
                } finally { input.close(); }
            } finally { output.close(); }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            taken.add(unique);
        } catch (Exception error) {
            resolver.delete(uri, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error.getMessage() == null ? "导出失败" : error.getMessage(), error);
        }
    }

    private static Uri collection() {
        return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
}
