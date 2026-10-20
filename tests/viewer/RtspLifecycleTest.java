package com.oldphone.ipcam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RtspLifecycleTest {
    public static void main(String[] args) throws Exception {
        incompleteHandshakeTimesOut();
        restartClosesPendingAndEstablishedConnections();
        System.out.println("PASS: incomplete RTSP handshake expires; repeated restart closes pending/established clients and accepts fresh handshakes");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void incompleteHandshakeTimesOut() throws Exception {
        RtspServer server = new RtspServer(freePort());
        server.start();
        try (Socket pending = new Socket("127.0.0.1", server.getPort())) {
            pending.setSoTimeout(10000);
            pending.getOutputStream().write("OPTIONS rtsp://localhost/live RTSP/1.0\r\nCSeq:".getBytes(StandardCharsets.UTF_8));
            try {
                check(pending.getInputStream().read() == -1, "incomplete handshake must close without a response");
            } catch (SocketTimeoutException e) {
                throw new AssertionError("incomplete RTSP handshake must not retain its worker indefinitely", e);
            }
        } finally { server.stop(); }
    }

    private static void restartClosesPendingAndEstablishedConnections() throws Exception {
        RtspServer server = new RtspServer(freePort());
        for (int iteration = 0; iteration < 40; iteration++) {
            List<Socket> sockets = new ArrayList<Socket>();
            server.start();
            try {
                Socket established = new Socket("127.0.0.1", server.getPort());
                sockets.add(established);
                established.setSoTimeout(2000);
                established.getOutputStream().write("OPTIONS rtsp://localhost/live RTSP/1.0\r\nCSeq: 1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                check("RTSP/1.0 200 OK".equals(line(established.getInputStream())), "restarted server must answer new handshakes");
                while (!line(established.getInputStream()).isEmpty()) { }
                for (int i = 0; i < 4; i++) {
                    Socket pending = new Socket("127.0.0.1", server.getPort());
                    sockets.add(pending);
                    pending.setSoTimeout(2000);
                    pending.getOutputStream().write("OPTIONS rtsp://localhost/live RTSP/1.0\r\n".getBytes(StandardCharsets.UTF_8));
                }
                server.stop();
                for (Socket socket : sockets) {
                    try { check(socket.getInputStream().read() == -1, "stop must close all RTSP clients"); }
                    catch (SocketException closed) { }
                    catch (SocketTimeoutException e) { throw new AssertionError("stop leaked an RTSP connection", e); }
                }
                check(server.clientCount() == 0, "stopped server must not retain clients");
            } finally {
                server.stop();
                for (Socket socket : sockets) socket.close();
            }
        }
    }

    private static String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') if (next != '\r') bytes.write(next);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
