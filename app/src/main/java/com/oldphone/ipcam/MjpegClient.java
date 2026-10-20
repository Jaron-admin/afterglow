package com.oldphone.ipcam;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 MJPEG 客户端：连上对方的 /video，按 JPEG 的 SOI(FFD8) / EOI(FFD9)
 * 从字节流里切帧。
 *
 * 为什么不用 WebView 直接放 <img src="/video">：老机型 WebView 对
 * multipart/x-mixed-replace 的支持时好时坏，而且没法控制缓冲；
 * 自己切帧能保证「收到一帧就显示一帧」，延迟稳定。
 *
 * 也不依赖 Content-Length：直接找 SOI/EOI 更抗对端实现差异
 * （baseline JPEG 的熵编码段里 0xFF 一定跟 0x00 转义，不会误判 EOI）。
 */
public final class MjpegClient {

    private static final String TAG = "MjpegClient";

    public interface Listener {
        void onFrame(byte[] jpeg);

        void onError(String message);
    }

    private final String host;
    private final int port;
    private final String path;

    private volatile boolean running;
    private Thread thread;
    private volatile Socket socket;

    /** 2MB 环形缓冲够放好几帧，超了就丢旧数据 */
    private final byte[] buf = new byte[1 << 21];
    private int len;

    public MjpegClient(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path == null || path.length() == 0 ? "/video" : path;
    }

    public synchronized void start(final Listener listener) {
        if (running) {
            return;
        }
        running = true;
        len = 0;
        final Socket connection = new Socket();
        socket = connection;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(listener, connection);
            }
        }, "mjpeg-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        Socket s;
        Thread t;
        synchronized (this) {
            running = false;
            s = socket;
            socket = null;
            t = thread;
            thread = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop(Listener listener, Socket s) {
        String failure = null;
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(8000);

            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: */*\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            InputStream in = s.getInputStream();
            // 跳过 HTTP 响应头
            if (!skipHeaders(in)) {
                failure = "连接被拒绝或响应异常";
                return;
            }

            byte[] tmp = new byte[8192];
            while (running && socket == s) {
                int n = in.read(tmp);
                if (n < 0) {
                    if (running) {
                        failure = "对端关闭了连接";
                    }
                    return;
                }
                if (!running || socket != s) {
                    return;
                }
                append(tmp, n);
                if (!extractFrames(listener)) {
                    return;
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "loop: " + t);
                failure = "读取失败：" + t.getMessage();
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            boolean report = false;
            synchronized (this) {
                if (socket == s) {
                    report = running && failure != null;
                    socket = null;
                    running = false;
                    thread = null;
                }
            }
            // Let the listener immediately reconnect after all old resources and
            // state have been released, rather than racing worker cleanup.
            if (report) {
                notifyError(listener, failure);
            }
        }
    }

    private void notifyError(final Listener listener, final String msg) {
        if (listener != null) {
            listener.onError(msg);
        }
    }

    /** 读掉响应头（到空行为止），返回是否是 200 */
    private boolean skipHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev3 = -1;
        int prev2 = -1;
        int prev1 = -1;
        boolean complete = false;
        while (sb.length() < 4096) {
            int c = in.read();
            if (c < 0) {
                return false;
            }
            sb.append((char) c);
            if (prev1 == '\r' && prev2 == '\n' && prev3 == '\r' && c == '\n') {
                complete = true;
                break;
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = c;
        }
        String head = sb.toString();
        return complete && (head.startsWith("HTTP/1.1 200 ") || head.startsWith("HTTP/1.0 200 "));
    }

    private void append(byte[] tmp, int n) {
        if (len + n > buf.length) {
            // 缓冲要爆了：丢掉已消费的前半段
            int keep = Math.min(len, buf.length / 2);
            System.arraycopy(buf, len - keep, buf, 0, keep);
            len = keep;
            if (len + n > buf.length) {
                // 极端情况（单帧大于 1MB），直接清空
                len = 0;
            }
        }
        System.arraycopy(tmp, 0, buf, len, n);
        len += n;
    }

    /** 切出所有完整帧，返回 false 表示要退出循环 */
    private boolean extractFrames(Listener listener) {
        while (true) {
            int soi = indexOf(0xFF, 0xD8, 0);
            if (soi < 0) {
                // 没有起始标记，攒够一定量就丢弃（一般是响应头残留）
                if (len > 4096) {
                    len = 0;
                }
                return true;
            }
            int eoi = indexOf(0xFF, 0xD9, soi + 2);
            if (eoi < 0) {
                // 帧还没收全，把 SOI 之前的数据丢掉，等后续
                if (soi > 0) {
                    System.arraycopy(buf, soi, buf, 0, len - soi);
                    len -= soi;
                }
                return true;
            }
            byte[] jpeg = new byte[eoi + 2 - soi];
            System.arraycopy(buf, soi, jpeg, 0, jpeg.length);
            if (listener != null && running) {
                listener.onFrame(jpeg);
            }
            int rest = len - (eoi + 2);
            if (rest > 0) {
                System.arraycopy(buf, eoi + 2, buf, 0, rest);
            }
            len = rest;
            if (!running) {
                return false;
            }
        }
    }

    private int indexOf(int b1, int b2, int from) {
        for (int i = from; i + 1 < len; i++) {
            if ((buf[i] & 0xFF) == b1 && (buf[i + 1] & 0xFF) == b2) {
                return i;
            }
        }
        return -1;
    }
}
