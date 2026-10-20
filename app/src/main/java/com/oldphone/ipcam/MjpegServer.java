package com.oldphone.ipcam;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MJPEG over HTTP 服务端。
 *
 * 端点：
 *   GET /              内置预览页（手机/电脑浏览器直接看）
 *   GET /video         multipart/x-mixed-replace 动态流
 *   GET /snapshot.jpg  单张快照（HA 的 still_image_url 用）
 *   GET /status        运行状态
 *
 * 相比 RTSP 的好处是零依赖、任何浏览器都能看；代价是码率高、延迟略大。
 */
public final class MjpegServer {

    private static final String TAG = "MjpegServer";
    private static final String BOUNDARY = "afterglowframe";

    private final int port;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<Client>();

    private volatile byte[] latestJpeg;
    private final Object frameLock = new Object();
    private volatile long frameSeq;
    private volatile String streamId;

    private volatile String authHeader;

    public MjpegServer(int port) {
        this.port = port;
    }

    public void setBasicAuth(String user, String pass) {
        if (user == null || user.trim().length() == 0) {
            throw new IllegalArgumentException("Authentication username must not be blank");
        }
        String token = user + ":" + (pass == null ? "" : pass);
        authHeader = "Basic " + Base64.encodeToString(token.getBytes(), Base64.NO_WRAP);
    }

    public int getPort() {
        return port;
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public int clientCount() {
        return clients.size();
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        final ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            listener.close();
            throw e;
        }
        serverSocket = listener;
        streamId = Long.toHexString(System.nanoTime());
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(listener);
            }
        }, "mjpeg-accept");
        acceptThread.start();
        Log.i(TAG, "HTTP listening on " + port);
    }

    private void acceptLoop(ServerSocket listener) {
        while (running && serverSocket == listener) {
            try {
                Socket s = listener.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(8000);
                Client c = new Client(s);
                synchronized (this) {
                    if (!running || serverSocket != listener) {
                        s.close();
                        return;
                    }
                    clients.add(c);
                }
                new Thread(c, "mjpeg-client").start();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept: " + e);
                }
            }
        }
    }

    /** 投递一张新 JPEG */
    public void pushJpeg(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        synchronized (frameLock) {
            if (!running) {
                return;
            }
            latestJpeg = jpeg;
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    public synchronized void stop() {
        running = false;
        synchronized (frameLock) {
            latestJpeg = null;
            frameSeq = 0;
            frameLock.notifyAll();
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
        Log.i(TAG, "HTTP stopped");
    }

    // ------------------------------------------------------------------

    private final class Client implements Runnable {

        private final Socket socket;
        private volatile boolean closed;

        Client(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                String requestLine = readLine(in);
                if (requestLine == null) {
                    return;
                }
                String[] parts = requestLine.split(" ");
                String path = parts.length >= 2 ? parts[1] : "/";

                String auth = null;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int c = line.indexOf(':');
                    if (c > 0 && "authorization".equalsIgnoreCase(line.substring(0, c).trim())) {
                        auth = line.substring(c + 1).trim();
                    }
                }

                if (authHeader != null && !authHeader.equals(auth)) {
                    byte[] body = "Unauthorized".getBytes("UTF-8");
                    writeHead(out, "401 Unauthorized",
                            "WWW-Authenticate: Basic realm=\"Afterglow\"\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: " + body.length + "\r\n");
                    out.write(body);
                    out.flush();
                    return;
                }

                int q = path.indexOf('?');
                if (q >= 0) {
                    path = path.substring(0, q);
                }

                if ("/".equals(path) || "/index.html".equals(path)) {
                    serveIndex(out);
                } else if (path.startsWith("/video") || path.startsWith("/stream")) {
                    serveStream(out);
                } else if (path.startsWith("/snapshot")) {
                    serveSnapshot(out);
                } else if (path.startsWith("/status")) {
                    serveStatus(out);
                } else {
                    byte[] body = "Not Found".getBytes("UTF-8");
                    writeHead(out, "404 Not Found",
                            "Content-Type: text/plain\r\nContent-Length: " + body.length + "\r\n");
                    out.write(body);
                    out.flush();
                }
            } catch (IOException e) {
                // 客户端断开是常态，忽略
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        private void serveIndex(OutputStream out) throws IOException {
            String host = NetUtils.localIp();
            String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>余光 · Afterglow</title>"
                    + "<style>body{margin:0;background:#0b1019;color:#edf2f7;font-family:system-ui,sans-serif;"
                    + "display:flex;flex-direction:column;align-items:center;justify-content:center;"
                    + "min-height:100vh}img{max-width:100vw;max-height:82vh;background:#000}"
                    + "p{margin:10px;font-size:13px;color:#9eaabd}a{color:#ffca78}</style></head><body>"
                    + "<p>余光 · Afterglow</p><img id=\"stream\" data-session=\"" + streamId
                    + "\" alt=\"实时画面\" style=\"visibility:hidden\">"
                    + "<p id=\"message\" role=\"status\">正在连接…</p>"
                    + "<p>MJPEG 实时流 &nbsp;|&nbsp; <a href=\"/snapshot.jpg\">快照</a>"
                    + " &nbsp;|&nbsp; <a href=\"/status\">状态</a></p>"
                    + "<p>RTSP: rtsp://" + host + ":8554/live</p>"
                    + "<script>(function(){"
                    + "var image=document.getElementById('stream'),message=document.getElementById('message');"
                    + "var retryTimer,pollTimer,request,stopped=false,session=image.getAttribute('data-session'),frames=null,stalls=0;"
                    + "function disconnect(){image.onload=image.onerror=null;image.style.visibility='hidden';image.removeAttribute('src');}"
                    + "function retry(){if(stopped)return;disconnect();message.textContent='连接中断，正在重连…';"
                    + "clearTimeout(retryTimer);retryTimer=setTimeout(connect,1200);}"
                    + "function connect(){if(stopped)return;image.onload=function(){"
                    + "image.style.visibility='visible';message.textContent='实时画面';};"
                    + "image.onerror=retry;image.src='/video?t='+Date.now();}"
                    + "function later(){if(!stopped)pollTimer=setTimeout(check,2000);}"
                    + "function check(){if(stopped)return;request=new XMLHttpRequest();"
                    + "request.open('GET','/status?t='+Date.now(),true);request.timeout=4000;"
                    + "request.onload=function(){if(stopped)return;try{"
                    + "if(request.status!==200)throw new Error();var status=JSON.parse(request.responseText);"
                    + "var changed=session!==null&&status.stream_id!==session;"
                    + "stalls=frames===status.frames?stalls+1:0;session=status.stream_id;frames=status.frames;"
                    + "if(changed||!status.has_frame||stalls>=3){stalls=0;retry();}"
                    + "}catch(e){retry();}later();};"
                    + "request.onerror=request.ontimeout=function(){if(stopped)return;retry();later();};request.send();}"
                    + "function stop(){stopped=true;clearTimeout(retryTimer);clearTimeout(pollTimer);"
                    + "if(request)request.abort();disconnect();}"
                    + "window.addEventListener('pagehide',stop);"
                    + "window.addEventListener('pageshow',function(){if(stopped){stopped=false;session=null;frames=null;stalls=0;connect();later();}});"
                    + "connect();later();})();</script>"
                    + "</body></html>";
            byte[] body = html.getBytes("UTF-8");
            writeHead(out, "200 OK",
                    "Content-Type: text/html; charset=utf-8\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Cache-Control: no-store\r\n");
            out.write(body);
            out.flush();
        }

        private void serveSnapshot(OutputStream out) throws IOException {
            byte[] jpeg = latestJpeg;
            if (jpeg == null) {
                byte[] body = "No frame yet".getBytes("UTF-8");
                writeHead(out, "503 Service Unavailable",
                        "Content-Type: text/plain\r\nContent-Length: " + body.length + "\r\n");
                out.write(body);
                out.flush();
                return;
            }
            writeHead(out, "200 OK",
                    "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + jpeg.length + "\r\n"
                            + "Cache-Control: no-store, no-cache, must-revalidate\r\n");
            out.write(jpeg);
            out.flush();
        }

        private void serveStatus(OutputStream out) throws IOException {
            String json = String.format(Locale.US,
                    "{\"running\":true,\"http_clients\":%d,\"frames\":%d,\"has_frame\":%b,\"stream_id\":\"%s\","
                            + "\"camera\":\"%s\",\"orientation\":%d,\"width\":%d,\"height\":%d}",
                    clients.size(), frameSeq, latestJpeg != null, streamId,
                    CameraStreamService.cameraLabel,
                    CameraStreamService.sensorOrientation,
                    CameraStreamService.videoWidth, CameraStreamService.videoHeight);
            byte[] body = json.getBytes("UTF-8");
            writeHead(out, "200 OK",
                    "Content-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\n");
            out.write(body);
            out.flush();
        }

        private void serveStream(OutputStream out) throws IOException, InterruptedException {
            writeHead(out, "200 OK",
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                            + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                            + "Pragma: no-cache\r\n"
                            + "Connection: close\r\n");

            long lastSent = -1;
            while (running && !closed) {
                byte[] jpeg;
                long seq;
                synchronized (frameLock) {
                    if (latestJpeg == null || frameSeq == lastSent) {
                        frameLock.wait(2000);
                        if (!running || closed || frameSeq == lastSent) {
                            continue;
                        }
                    }
                    seq = frameSeq;
                    jpeg = latestJpeg;
                }
                if (jpeg == null) {
                    continue;
                }
                lastSent = seq;
                String header = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + jpeg.length + "\r\n\r\n";
                out.write(header.getBytes("UTF-8"));
                out.write(jpeg);
                out.write("\r\n".getBytes("UTF-8"));
                out.flush();
            }
        }

        private void writeHead(OutputStream out, String status, String headers) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append("\r\n");
            sb.append(headers);
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int l = sb.length();
                if (l > 0 && sb.charAt(l - 1) == '\r') {
                    sb.setLength(l - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 8192) {
                return null;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
