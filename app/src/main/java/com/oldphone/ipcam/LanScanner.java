package com.oldphone.ipcam;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：找出同网段里其它正在推流的「旧手机监控」实例。
 *
 * 判定方式刻意做成「协议指纹」而不是 UDP 广播：
 *   - 先打 HTTP 8080 的 /status，返回体里必须同时出现 "has_frame" 与 "running"；
 *   - 打不通再试 RTSP 8554 的 OPTIONS，看 Server 头是不是 "OldPhoneCam"。
 * 这样不需要组播权限，也不怕路由器禁广播，代价是要逐个 IP 试。
 *
 * 扫描范围固定为「本机 IP 所在的 /24」。家用网络基本都是 /24，
 * 扫 /16 是 65534 个地址，手机上不现实。
 */
public final class LanScanner {

    private static final String TAG = "LanScanner";

    private static final int HTTP_PORT = 8080;
    private static final int RTSP_PORT = 8554;
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int READ_TIMEOUT_MS = 800;
    private static final int THREADS = 32;

    /** 扫描结果 */
    public static final class Device {
        public String ip;
        /** 只开了 RTSP、没有 HTTP 的情况 */
        public boolean rtspOnly;
        public boolean running;
        public String camera = "";
        public int width;
        public int height;

        public String detail() {
            if (rtspOnly) {
                return "仅 RTSP · rtsp://" + ip + ":" + RTSP_PORT + "/live";
            }
            StringBuilder sb = new StringBuilder();
            if (camera != null && camera.length() > 0) {
                sb.append(camera);
            }
            if (width > 0 && height > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(width).append('x').append(height);
            }
            if (sb.length() == 0) {
                sb.append("正在推流");
            }
            return sb.toString();
        }
    }

    public interface Callback {
        /** 每发现一台就回调一次（主线程） */
        void onFound(Device device);

        /** 扫完（主线程），scanned 是尝试过的地址数 */
        void onFinished(int scanned);
    }

    private LanScanner() {
    }

    public static void scan(final Callback callback) {
        final String self = NetUtils.localIp();
        final String prefix = subnetPrefix(self);
        final Handler ui = new Handler(Looper.getMainLooper());
        final AtomicInteger remaining = new AtomicInteger(254);
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        Log.i(TAG, "scan " + prefix + ".0/24, self=" + self);

        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + "." + i;
            if (ip.equals(self)) {
                finishOne(ui, callback, remaining);
                continue;
            }
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Device d = probeHttp(ip);
                        if (d == null) {
                            d = probeRtsp(ip);
                        }
                        if (d != null) {
                            final Device found = d;
                            Log.i(TAG, "found " + found.ip + " rtspOnly=" + found.rtspOnly);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onFound(found);
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        finishOne(ui, callback, remaining);
                    }
                }
            });
        }
        pool.shutdown();
    }

    private static void finishOne(Handler ui, final Callback cb, AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.onFinished(254);
                }
            });
        }
    }

    /** 把 "192.168.1.50" 取成 "192.168.1" */
    private static String subnetPrefix(String ip) {
        if (ip == null) {
            return "127.0.0";
        }
        int idx = ip.lastIndexOf('.');
        return idx > 0 ? ip.substring(0, idx) : ip;
    }

    // ------------------------------------------------------------------

    private static Device probeHttp(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, HTTP_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(("GET /status HTTP/1.1\r\nHost: " + ip + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            String resp = readAll(s.getInputStream(), 4096);
            // 必须同时有这两个键，避免把别的 8080 服务误判成自己
            if (resp == null || !resp.contains("has_frame") || !resp.contains("running")) {
                return null;
            }
            Device d = new Device();
            d.ip = ip;
            d.running = resp.contains("\"running\":true");
            d.camera = jsonString(resp, "camera");
            d.width = jsonInt(resp, "width");
            d.height = jsonInt(resp, "height");
            return d;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(s);
        }
    }

    private static Device probeRtsp(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, RTSP_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(("OPTIONS rtsp://" + ip + ":" + RTSP_PORT + "/ RTSP/1.0\r\n"
                    + "CSeq: 1\r\nUser-Agent: OldPhoneCamScanner\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            String resp = readAll(s.getInputStream(), 1024);
            if (resp == null || !resp.contains("OldPhoneCam")) {
                return null;
            }
            Device d = new Device();
            d.ip = ip;
            d.rtspOnly = true;
            d.running = true;
            return d;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(s);
        }
    }

    private static String readAll(InputStream in, int limit) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
            byte[] tmp = new byte[512];
            while (buf.size() < limit) {
                int n = in.read(tmp);
                if (n <= 0) {
                    break;
                }
                buf.write(tmp, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static String jsonString(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) {
            return "";
        }
        int start = i + k.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    private static int jsonInt(String json, String key) {
        String k = "\"" + key + "\":";
        int i = json.indexOf(k);
        if (i < 0) {
            return 0;
        }
        int start = i + k.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** 供界面显示：当前网段 */
    public static String currentSubnet() {
        return subnetPrefix(NetUtils.localIp()) + ".0/24";
    }
}
