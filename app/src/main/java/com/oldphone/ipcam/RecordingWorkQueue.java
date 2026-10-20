package com.oldphone.ipcam;

import java.io.IOException;
import java.util.ArrayDeque;

/** Bounded producer admission. No capture thread ever waits for disk I/O. */
final class RecordingWorkQueue {
    interface Work { void run() throws IOException; }
    private static final class Item {
        final Work work; final int bytes;
        Item(Work work, int bytes) { this.work = work; this.bytes = bytes; }
    }
    private final long byteLimit;
    private final int itemLimit;
    private final ArrayDeque<Item> items = new ArrayDeque<Item>();
    private long bytes;
    private boolean closed;
    RecordingWorkQueue(long byteLimit, int itemLimit) {
        this.byteLimit = byteLimit; this.itemLimit = itemLimit;
    }
    synchronized boolean offer(Work work, int size) {
        if (closed || size < 0 || bytes + size > byteLimit || items.size() >= itemLimit) return false;
        items.addLast(new Item(work, size));
        bytes += size;
        notifyAll();
        return true;
    }
    synchronized Work take() throws InterruptedException {
        while (items.isEmpty() && !closed) wait();
        if (items.isEmpty()) return null;
        Item item = items.removeFirst();
        bytes -= item.bytes;
        return item.work;
    }
    synchronized void close() { closed = true; notifyAll(); }
    synchronized void abort() { closed = true; items.clear(); bytes = 0; notifyAll(); }
}
