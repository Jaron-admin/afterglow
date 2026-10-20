package com.oldphone.ipcam;

public final class RecordingWorkQueueTest {
    public static void main(String[] args) throws Exception {
        RecordingWorkQueue queue = new RecordingWorkQueue(100, 3);
        RecordingWorkQueue.Work work = new RecordingWorkQueue.Work() {
            public void run() { }
        };
        if (!queue.offer(work, 60) || queue.offer(work, 41))
            throw new AssertionError("byte limit not enforced");
        if (queue.take() != work || !queue.offer(work, 60))
            throw new AssertionError("taking work must release queue budget");
        queue.close();
        if (queue.offer(work, 1) || queue.take() != work || queue.take() != null)
            throw new AssertionError("normal close must drain accepted work");
        RecordingWorkQueue failed = new RecordingWorkQueue(100, 1);
        failed.offer(work, 1);
        if (failed.offer(work, 1)) throw new AssertionError("item limit not enforced");
        failed.abort();
        if (failed.take() != null || failed.offer(work, 1))
            throw new AssertionError("overflow failure must discard dependencies and reject future work");
        System.out.println("RecordingWorkQueueTest PASS: byte/item limits, graceful drain, failure cancellation");
    }
}
