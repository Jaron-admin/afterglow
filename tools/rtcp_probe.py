#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""最小 RTSP 客户端：OPTIONS -> DESCRIBE -> SETUP(interleaved) -> PLAY
然后打印收到的每个交错通道包，用来验证 RTCP SR 是否真的在发。"""

import socket
import struct
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8554


def readline(f):
    return f.readline().decode("ascii", "replace").strip()


def readheaders(f):
    cseq = None
    print("  --> " + readline(f))
    while True:
        line = readline(f)
        if not line:
            break
        print("      " + line)
        if line.lower().startswith("cseq:"):
            cseq = int(line.split(":", 1)[1])
    return cseq


def request(s, cseq, method, url, headers=""):
    msg = "%s %s RTSP/1.0\r\nCSeq: %d\r\n%s\r\n" % (method, url, cseq, headers)
    s.sendall(msg.encode())
    f = s.makefile("rb")
    return readheaders(f)


s = socket.create_connection((HOST, PORT), 5)
s.settimeout(3)
url = "rtsp://%s:%d/live" % (HOST, PORT)

request(s, 1, "OPTIONS", url, "User-Agent: probe\r\n")
request(s, 2, "DESCRIBE", url, "Accept: application/sdp\r\n")
request(s, 3, "SETUP", url + "/trackID=0",
        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
request(s, 4, "PLAY", url, "Session: (from SETUP)\r\n")

print("\n-- 等 8 秒，打印交错通道包 --")
s.settimeout(1)
end = time.time() + 8
rtp = rtcp = 0
while time.time() < end:
    try:
        head = s.recv(4)
        if not head:
            print("  socket 关闭")
            break
        if head[0] != 0x24:
            print("  非 $ 开头: %r" % head)
            continue
        ch = head[1]
        n = struct.unpack("!H", head[2:4])[0]
        body = b""
        while len(body) < n:
            body += s.recv(n - len(body))
        if ch == 0:
            rtp += 1
            pt = body[1] & 0x7F
            seq = struct.unpack("!H", body[2:4])[0]
            if rtp <= 3:
                print("  RTP  ch0 len=%d pt=%d seq=%d" % (n, pt, seq))
        else:
            rtcp += 1
            pt = body[1]
            ssrc = struct.unpack("!I", body[4:8])[0]
            ntp = struct.unpack("!Q", body[8:16])[0]
            print("  RTCP ch1 len=%d pt=%d ssrc=%08x ntp=%d" % (n, pt, ssrc, ntp))
    except socket.timeout:
        pass

print("\nRTP=%d  RTCP=%d" % (rtp, rtcp))
