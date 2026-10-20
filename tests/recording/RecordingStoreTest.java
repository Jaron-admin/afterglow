package com.oldphone.ipcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class RecordingStoreTest {
    static final class DiskBoundary implements RecordingStore.DiskAccess {
        boolean readOnly;
        File occupyingClip;
        int probes;
        public void requireReadable(File root) { }
        public void requireWritable(File root) throws IOException {
            if (readOnly) throw new IOException("storage read-only");
        }
        public long usableSpace(File root) {
            return occupyingClip != null && occupyingClip.exists() ? 0 : 1000;
        }
        public void probe(File file) throws IOException {
            probes++;
            requireWritable(file.getParentFile());
            if (usableSpace(file.getParentFile()) == 0) throw new IOException("no space left on device");
        }
    }
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
    static RecordingStore.Clip complete(RecordingStore store, long time, int size) throws Exception {
        RecordingStore.Pending p = store.begin(time, false);
        try (FileOutputStream out = new FileOutputStream(p.file)) { out.write(new byte[size]); }
        return store.complete(p, 1000, "motion");
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory(new File("build").toPath(), "afterglow-store-test").toFile();
        RecordingStore store = new RecordingStore(root, 100, 0);
        RecordingStore.Clip locked = complete(store, 1, 30);
        store.setLocked(locked.id, true);
        RecordingStore.Clip old = complete(store, 2, 30);
        RecordingStore.Clip newest = complete(store, 3, 30);
        File foreign = new File(root, "holiday.mp4");
        try (FileOutputStream out = new FileOutputStream(foreign)) { out.write(new byte[15]); }
        RecordingStore.Pending pending = store.begin(4, false);
        try (FileOutputStream out = new FileOutputStream(pending.file)) { out.write(new byte[10]); }
        store.ensureSpace(30);
        List<RecordingStore.Clip> clips = store.list();
        check(clips.size() == 2, "only oldest complete unlocked clip evicted");
        check(store.registeredFile(locked.id).exists(), "locked recording survives cleanup");
        check(store.registeredFile(newest.id).exists(), "newer recording survives cleanup");
        check(foreign.exists() && pending.file.exists(), "foreign and incomplete recordings survive cleanup");
        check(!new File(root, old.id + ".mp4").exists(), "oldest unlocked recording removed");
        try {
            store.registeredFile("../holiday");
            throw new AssertionError("path traversal accepted");
        } catch (IOException expected) { }
        store.setLocked(newest.id, true);
        try {
            store.ensureSpace(90);
            throw new AssertionError("locked quota exhaustion accepted");
        } catch (IOException expected) { }
        store.delete(locked.id);
        check(store.list().size() == 1, "explicit deletion may remove a locked clip");
        File unavailable = new File(root, "not-directory");
        try (FileOutputStream out = new FileOutputStream(unavailable)) { out.write(1); }
        try {
            new RecordingStore(unavailable, 100, 0);
            throw new AssertionError("failed storage accepted");
        } catch (IOException expected) { }
        File stopRoot = Files.createTempDirectory(new File("build").toPath(), "afterglow-stop-test").toFile();
        RecordingOptions stopOptions = new RecordingOptions(1, 300, 5, 15, 1, 100, 1, 80, false, false);
        RecordingStore stopStore = new RecordingStore(stopRoot, stopOptions);
        RecordingStore.Clip oldStopped = complete(stopStore, 1, 80);
        try {
            stopStore.ensureSpace(30);
            throw new AssertionError("stop policy did not pause at quota");
        } catch (IOException expected) { }
        check(stopStore.registeredFile(oldStopped.id).exists(), "stop policy must not delete even expired clips");
        File daysRoot = Files.createTempDirectory(new File("build").toPath(), "afterglow-days-test").toFile();
        RecordingOptions daysOptions = new RecordingOptions(1, 300, 5, 15, 1, 100, 1, 80, true, false);
        RecordingStore daysStore = new RecordingStore(daysRoot, daysOptions);
        RecordingStore.Clip expired = complete(daysStore, 1, 20);
        RecordingStore.Clip protectedOld = complete(daysStore, 2, 20);
        daysStore.setLocked(protectedOld.id, true);
        daysStore.ensureSpace(1);
        check(daysStore.list().size() == 1 && daysStore.registeredFile(protectedOld.id).exists(),
                "retention expires only unlocked owned recordings");
        File fullRoot = Files.createTempDirectory(new File("build").toPath(), "afterglow-full-test").toFile();
        RecordingStore seed = new RecordingStore(fullRoot, 1000, 0);
        RecordingStore.Clip fullClip = complete(seed, 1, 80);
        RecordingOptions fullOptions = new RecordingOptions(1, 300, 5, 15, 0, 1000, 1, 800, true, false);
        DiskBoundary disk = new DiskBoundary(); disk.occupyingClip = fullClip.file;
        RecordingStore full = new RecordingStore(fullRoot, fullOptions, disk);
        check(disk.probes == 0 && full.list().size() == 1, "full disk must still open and list without a probe write");
        try (FileInputStream input = new FileInputStream(full.registeredFile(fullClip.id))) {
            check(input.read() == 0, "full disk source remains readable for playback/export");
        }
        full.prepareWriter(10);
        check(disk.probes == 1 && !fullClip.file.exists(),
                "loop writer must clean owned completed clips before native probe on a full disk");
        RecordingStore.Clip deletable = complete(full, 2, 80);
        disk.occupyingClip = deletable.file;
        full.delete(deletable.id);
        check(!deletable.file.exists(), "explicit delete must work while disk has zero free bytes");

        RecordingStore.Clip readOnlyClip = complete(full, 3, 80);
        disk.readOnly = true;
        RecordingStore readOnly = new RecordingStore(fullRoot, fullOptions, disk);
        check(readOnly.list().size() == 1, "read-only mounted volume must remain browseable");
        try (FileInputStream input = new FileInputStream(readOnly.registeredFile(readOnlyClip.id))) {
            check(input.read() == 0, "read-only mounted volume remains playable/exportable");
        }
        try {
            readOnly.delete(readOnlyClip.id);
            throw new AssertionError("read-only delete did not report an error");
        } catch (IOException expected) { check(expected.getMessage().contains("read-only"), "explicit read-only error"); }
        try {
            readOnly.prepareWriter(10);
            throw new AssertionError("read-only writer did not report an error");
        } catch (IOException expected) { }
        check(readOnlyClip.file.exists(), "read-only failures do not remove recordings");
        System.out.println("RecordingStoreTest PASS: quota, lock, partial/foreign protection, canonical source paths, unavailable target");
    }
}
