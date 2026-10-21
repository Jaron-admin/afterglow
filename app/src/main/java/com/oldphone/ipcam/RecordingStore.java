package com.oldphone.ipcam;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Only indexed files inside the selected app-private recording directory are managed. */
public final class RecordingStore {
    public static final long DEFAULT_QUOTA = 2L * 1024 * 1024 * 1024;
    public static final long RESERVE_BYTES = 256L * 1024 * 1024;
    private static final Object FILE_LOCK = new Object();
    public static final class Target {
        public final String id, label;
        public final File directory;
        public final boolean external;
        Target(File directory, String label, boolean external) throws IOException {
            this.directory = directory.getCanonicalFile(); this.id = this.directory.getPath();
            this.label = label; this.external = external;
        }
        @Override public String toString() { return label; }
    }
    public static final class Clip {
        public final String id, reasons, triggers;
        public final File file;
        public final long startTimeMs, durationMs, bytes;
        public final boolean locked, withAudio, hasThumbnail;
        Clip(String id, File file, Properties props) throws IOException {
            this.id = id; this.file = file; this.bytes = file.length();
            try {
                startTimeMs = Long.parseLong(props.getProperty("start", "0"));
                durationMs = Long.parseLong(props.getProperty("duration", "0"));
            } catch (NumberFormatException e) { throw new IOException("录像索引损坏", e); }
            locked = Boolean.parseBoolean(props.getProperty("locked"));
            withAudio = Boolean.parseBoolean(props.getProperty("audio"));
            reasons = props.getProperty("events", "");
            triggers = props.getProperty("triggers", "");
            File thumb = new File(file.getParentFile(), id + ".jpg");
            hasThumbnail = Boolean.parseBoolean(props.getProperty("thumbnail")) && thumb.isFile() && thumb.length() > 0;
        }
    }
    static final class Pending {
        final String id;
        final File file;
        final Properties properties;
        Pending(String id, File file, Properties properties) {
            this.id = id; this.file = file; this.properties = properties;
        }
    }
    private final File root;
    private final long quota, reserve;
    private final DiskAccess disk;
    private final boolean loop;
    private final int retentionDays;

    public static List<Target> targets(Context context) {
        List<Target> result = new ArrayList<Target>();
        Set<String> paths = new HashSet<String>();
        try {
            Target phone = new Target(new File(context.getFilesDir(), "recordings"), "手机存储", false);
            result.add(phone); paths.add(phone.id);
        } catch (IOException ignored) { }
        File[] dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        if (dirs != null) for (File dir : dirs) {
            if (dir == null) continue;
            try {
                String state = Environment.getStorageState(dir);
                if (!Environment.MEDIA_MOUNTED.equals(state)
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                String label = "外置存储";
                if (Build.VERSION.SDK_INT >= 21) label = Modern.removable(dir) ? "外置 SD 卡" : "共享手机存储";
                if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) label += "（只读）";
                Target target = new Target(new File(dir, "afterglow-recordings"), label, true);
                if (paths.add(target.id)) result.add(target);
            } catch (IOException | RuntimeException ignored) { }
        }
        return result;
    }
    @TargetApi(21)
    private static final class Modern {
        static boolean removable(File file) { return Environment.isExternalStorageRemovable(file); }
    }
    public static RecordingStore open(Context context, String targetId, long quota) throws IOException {
        return open(context, targetId, quota, RESERVE_BYTES, true, 0);
    }
    public static RecordingStore open(Context context, String targetId, RecordingOptions options) throws IOException {
        return open(context, targetId, options.quotaBytes, options.reserveBytes, options.loop, options.retentionDays);
    }
    private static RecordingStore open(Context context, String targetId, long quota, long reserve,
                                       boolean loop, int retentionDays) throws IOException {
        List<Target> targets = targets(context);
        for (Target target : targets) {
            if ((targetId == null || targetId.length() == 0) ? !target.external : target.id.equals(targetId))
                return new RecordingStore(target.directory, quota, reserve, target.external, loop, retentionDays);
        }
        throw new IOException("所选存储不可用，请重新连接后重试");
    }
    RecordingStore(File directory, long quota, long reserve) throws IOException {
        this(directory, quota, reserve, false, true, 0);
    }
    RecordingStore(File directory, RecordingOptions options) throws IOException {
        this(directory, options.quotaBytes, options.reserveBytes, false, options.loop, options.retentionDays);
    }
    RecordingStore(File directory, RecordingOptions options, DiskAccess disk) throws IOException {
        this(directory, options.quotaBytes, options.reserveBytes, options.loop, options.retentionDays, disk);
    }
    private RecordingStore(File directory, long quota, long reserve, boolean external, boolean loop,
                           int retentionDays) throws IOException {
        this(directory, quota, reserve, loop, retentionDays, new PlatformDisk(external));
    }
    private RecordingStore(File directory, long quota, long reserve, boolean loop,
                           int retentionDays, DiskAccess disk) throws IOException {
        this.root = directory.getCanonicalFile();
        this.quota = quota > 0 ? quota : DEFAULT_QUOTA;
        this.reserve = reserve;
        this.disk = disk;
        this.loop = loop; this.retentionDays = retentionDays;
        synchronized (FILE_LOCK) {
            disk.requireReadable(root);
            if (root.exists() && !root.isDirectory()) throw new IOException("录像目录不可读取");
        }
    }
    /** Writer-only admission: reclaim space before a probe that itself needs free space. */
    void prepareWriter(long additionalBytes) throws IOException {
        synchronized (FILE_LOCK) {
            ensureDirectory();
            ensureSpace(additionalBytes);
            File probe = managed("probe-" + UUID.randomUUID().toString(), ".tmp");
            try { disk.probe(probe); }
            finally { if (probe.exists() && !probe.delete()) throw new IOException("录像目录无法清理试写文件"); }
        }
    }
    private void ensureDirectory() throws IOException {
        disk.requireWritable(root);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("录像目录不可写");
    }
    public List<Clip> list() throws IOException {
        synchronized (FILE_LOCK) { return listLocked(); }
    }
    private List<Clip> listLocked() throws IOException {
        disk.requireReadable(root);
        if (!root.exists()) return new ArrayList<Clip>();
        File[] files = root.listFiles();
        if (files == null) throw new IOException("录像目录不可读取");
        List<Clip> clips = new ArrayList<Clip>();
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".meta")) continue;
            String id = name.substring(0, name.length() - 5);
            if (!validId(id)) continue;
            try {
                Properties props = read(id);
                File mp4 = managed(id, ".mp4");
                if ("complete".equals(props.getProperty("state")) && mp4.isFile() && mp4.length() > 0)
                    clips.add(new Clip(id, mp4, props));
            } catch (IOException ignored) { /* Corrupt/uncommitted files are never valid clips. */ }
        }
        Collections.sort(clips, new Comparator<Clip>() {
            public int compare(Clip a, Clip b) { return Long.compare(a.startTimeMs, b.startTimeMs); }
        });
        return clips;
    }
    public File registeredFile(String id) throws IOException {
        synchronized (FILE_LOCK) {
            disk.requireReadable(root);
            Properties props = read(id);
            File file = managed(id, ".mp4");
            if (!"complete".equals(props.getProperty("state")) || !file.isFile() || file.length() == 0)
                throw new IOException("录像不完整或已不可用");
            return file;
        }
    }
    public void setLocked(String id, boolean locked) throws IOException {
        synchronized (FILE_LOCK) {
            disk.requireWritable(root);
            registeredFile(id);
            Properties props = read(id);
            props.setProperty("locked", Boolean.toString(locked));
            write(id, props);
        }
    }
    public void delete(String id) throws IOException {
        synchronized (FILE_LOCK) {
            disk.requireWritable(root);
            File file = registeredFile(id);
            if (!file.delete()) throw new IOException("无法删除录像");
            File thumb = managed(id, ".jpg");
            if (thumb.exists() && !thumb.delete()) throw new IOException("无法删除录像缩略图");
            File metadata = managed(id, ".meta");
            if (metadata.exists() && !metadata.delete()) throw new IOException("无法清理录像索引");
        }
    }
    void ensureSpace(long additionalBytes) throws IOException {
        ensureSpaceForActive(null, 0, additionalBytes);
    }
    void ensureSpaceForActive(Pending pending, long estimatedBytes, long additionalBytes) throws IOException {
        synchronized (FILE_LOCK) {
            disk.requireWritable(root);
            List<Clip> clips = listLocked();
            if (loop && retentionDays > 0) {
                long cutoff = System.currentTimeMillis() - retentionDays * 86400000L;
                for (Clip clip : clips) if (!clip.locked && clip.startTimeMs < cutoff) delete(clip.id);
                clips = listLocked();
            }
            long used = registeredBytes();
            if (pending != null) {
                File active = managed(pending.id, ".partial");
                used += Math.max(0, estimatedBytes - active.length());
            }
            long free = disk.usableSpace(root);
            for (Clip clip : clips) {
                if (used + additionalBytes <= quota && free >= reserve + additionalBytes) return;
                if (!loop || clip.locked) continue;
                delete(clip.id);
                used -= clip.bytes;
                free = disk.usableSpace(root);
            }
            if (used + additionalBytes > quota) throw new IOException(loop
                    ? "录像额度已满，解锁旧片段或增加容量后重试" : "录像额度已满，按设置暂停录制");
            if (free < reserve + additionalBytes) throw new IOException("剩余空间低于预留值，录像已暂停");
        }
    }
    private long registeredBytes() throws IOException {
        long bytes = 0;
        if (!root.exists()) return 0;
        File[] files = root.listFiles();
        if (files == null) throw new IOException("录像目录不可读取");
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".meta")) continue;
            String id = name.substring(0, name.length() - 5);
            if (!validId(id)) continue;
            Properties props;
            try { props = read(id); } catch (IOException badIndex) { continue; }
            File owned = managed(id, "complete".equals(props.getProperty("state")) ? ".mp4" : ".partial");
            bytes += owned.length();
        }
        return bytes;
    }
    Pending begin(long startTimeMs, boolean withAudio) throws IOException {
        synchronized (FILE_LOCK) {
            ensureDirectory();
            String id = Long.toString(startTimeMs) + "-" + UUID.randomUUID().toString();
            Properties props = new Properties();
            props.setProperty("state", "partial"); props.setProperty("start", Long.toString(startTimeMs));
            props.setProperty("locked", "false"); props.setProperty("audio", Boolean.toString(withAudio));
            write(id, props);
            File partial = managed(id, ".partial");
            if (!partial.createNewFile()) throw new IOException("无法创建录像文件");
            return new Pending(id, partial, props);
        }
    }
    void writeThumbnail(Pending pending, byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > 512 * 1024) return;
        if ((jpeg[0] & 255) != 0xFF || (jpeg[1] & 255) != 0xD8) return;
        synchronized (FILE_LOCK) {
            disk.requireWritable(root);
            File temporary = managed(pending.id, ".jpg.tmp");
            File dest = managed(pending.id, ".jpg");
            FileOutputStream out = new FileOutputStream(temporary);
            try {
                out.write(jpeg);
                out.getFD().sync();
            } finally { out.close(); }
            if (!temporary.renameTo(dest)) {
                if (dest.exists() && !dest.delete()) throw new IOException("无法保存缩略图");
                if (!temporary.renameTo(dest)) throw new IOException("无法保存缩略图");
            }
            pending.properties.setProperty("thumbnail", "true");
        }
    }
    /** Cache a JPEG for a finished clip. Existing index fields stay as they are. */
    void attachThumbnail(String id, byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > 512 * 1024) return;
        if ((jpeg[0] & 255) != 0xFF || (jpeg[1] & 255) != 0xD8) return;
        synchronized (FILE_LOCK) {
            Properties props = read(id);
            if (!"complete".equals(props.getProperty("state"))) return;
            disk.requireWritable(root);
            File temporary = managed(id, ".jpg.tmp");
            File dest = managed(id, ".jpg");
            FileOutputStream out = new FileOutputStream(temporary);
            try {
                out.write(jpeg);
                out.getFD().sync();
            } finally { out.close(); }
            if (!temporary.renameTo(dest)) {
                if (dest.exists() && !dest.delete()) throw new IOException("无法保存缩略图");
                if (!temporary.renameTo(dest)) throw new IOException("无法保存缩略图");
            }
            props.setProperty("thumbnail", "true");
            write(id, props);
        }
    }
    File thumbnail(String id) throws IOException {
        synchronized (FILE_LOCK) {
            Clip clip = null;
            for (Clip candidate : listLocked()) if (candidate.id.equals(id)) clip = candidate;
            if (clip == null || !clip.hasThumbnail) throw new IOException("缩略图不存在");
            return managed(id, ".jpg");
        }
    }
    Clip complete(Pending pending, long durationMs, String reasons) throws IOException {
        synchronized (FILE_LOCK) {
            disk.requireWritable(root);
            File source = managed(pending.id, ".partial");
            if (!source.equals(pending.file.getCanonicalFile()) || !source.isFile() || source.length() == 0)
                throw new IOException("录像未正常完成");
            File output = managed(pending.id, ".mp4");
            if (output.exists() || !source.renameTo(output)) throw new IOException("无法完成录像文件");
            pending.properties.setProperty("duration", Long.toString(durationMs));
            pending.properties.setProperty("events", reasons);
            pending.properties.setProperty("state", "complete");
            try {
                write(pending.id, pending.properties);
            } catch (IOException error) {
                // The muxer finished, but index publication failed. Roll back the
                // file name where possible so it cannot appear as a valid clip.
                pending.properties.setProperty("state", "partial");
                if (output.exists()) output.renameTo(source);
                throw error;
            }
            return new Clip(pending.id, output, pending.properties);
        }
    }
    /** Called only for a known active segment. Crash leftovers are deliberately left untouched. */
    void discard(Pending pending) {
        synchronized (FILE_LOCK) {
            try {
                disk.requireWritable(root);
                Properties props = read(pending.id);
                if (!"partial".equals(props.getProperty("state"))) return;
                File source = managed(pending.id, ".partial");
                if (!source.equals(pending.file.getCanonicalFile())) return;
                File uncommitted = managed(pending.id, ".mp4");
                boolean removed = !source.exists() || source.delete();
                // A rename may have succeeded before complete-index publication
                // failed. This exact pending id is still registered as partial.
                removed &= !uncommitted.exists() || uncommitted.delete();
                File thumb = managed(pending.id, ".jpg");
                if (thumb.exists()) thumb.delete();
                if (removed) managed(pending.id, ".meta").delete();
            } catch (IOException ignored) { }
        }
    }
    /** Native storage boundary; tests substitute fullness/mount state, not file permissions. */
    interface DiskAccess {
        void requireReadable(File directory) throws IOException;
        void requireWritable(File directory) throws IOException;
        long usableSpace(File directory);
        void probe(File file) throws IOException;
    }
    private static final class PlatformDisk implements DiskAccess {
        private final boolean external;
        PlatformDisk(boolean external) { this.external = external; }
        public void requireReadable(File directory) throws IOException {
            if (!external) return;
            String state = Environment.getStorageState(directory);
            if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))
                throw new IOException("所选存储已断开，请重新连接后重试");
        }
        public void requireWritable(File directory) throws IOException {
            requireReadable(directory);
            if (external && Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getStorageState(directory)))
                throw new IOException("所选存储为只读，无法修改或录制");
        }
        public long usableSpace(File directory) { return directory.getUsableSpace(); }
        public void probe(File file) throws IOException {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(0); out.getFD().sync(); }
        }
    }
    private static boolean validId(String id) {
        return id != null && id.matches("[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private File managed(String id, String suffix) throws IOException {
        // Probe names are internal; public ids must additionally pass validId in read().
        File file = new File(root, id + suffix).getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("录像路径超出应用目录");
        return file;
    }
    private Properties read(String id) throws IOException {
        if (!validId(id)) throw new IOException("无效录像编号");
        Properties props = new Properties();
        File index = managed(id, ".meta");
        if (index.length() > 128 * 1024) throw new IOException("录像索引过大");
        try (FileInputStream in = new FileInputStream(index)) { props.load(in); }
        catch (IllegalArgumentException malformed) { throw new IOException("录像索引损坏", malformed); }
        return props;
    }
    private void write(String id, Properties props) throws IOException {
        disk.requireWritable(root);
        if (!validId(id)) throw new IOException("无效录像编号");
        File tmp = managed(id, ".meta.tmp");
        File dest = managed(id, ".meta");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            props.store(out, "Afterglow recording index"); out.getFD().sync();
        }
        // File.renameTo atomically replaces on Android's Linux filesystems.
        // Windows test hosts require an explicit removal of the old index.
        if (!tmp.renameTo(dest)) {
            if (dest.exists() && !dest.delete()) throw new IOException("无法更新录像索引");
            if (!tmp.renameTo(dest)) throw new IOException("无法写入录像索引");
        }
    }
}
