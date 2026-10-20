"""模拟 Afterglow 设备：在 PC 上模拟一台监控手机，用来验证局域网发现与查看。

  GET /status        -> 与真机一致的 JSON（/status 是指纹识别的依据）
  GET /video         -> multipart/x-mixed-replace MJPEG 流（8fps）
  GET /snapshot.jpg  -> 单帧
  GET /              -> 最简单的 <img src=/video> 播放页

用法：
  python tools/fake_device.py [端口]

帧画面来自同目录的 frame.jpg，可以换成任意一张 640x480 的图；
App 侧的 MjpegClient 只认 SOI/EOI，对帧内容没有要求。
"""
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
FRAME_PATH = os.path.join(HERE, "frame.jpg")
if not os.path.isfile(FRAME_PATH):
    sys.exit("缺少 %s，随便放一张 640x480 的 JPG 进去即可" % FRAME_PATH)
FRAME = open(FRAME_PATH, "rb").read()
BOUNDARY = "--oldphoneboundary"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/status":
            body = ('{"running":true,"http_clients":1,"frames":0,"has_frame":true,'
                    '"camera":"前置","orientation":270,"width":640,"height":480}').encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path in ("/", "/index.html"):
            body = b"<html><body style='background:#111'><img src='/video'></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/snapshot.jpg":
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(FRAME)))
            self.end_headers()
            self.wfile.write(FRAME)
        elif path == "/video":
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=" + BOUNDARY)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                while True:
                    self.wfile.write((BOUNDARY + "\r\n").encode())
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(("Content-Length: %d\r\n\r\n" % len(FRAME)).encode())
                    self.wfile.write(FRAME)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                    time.sleep(0.125)
            except (BrokenPipeError, ConnectionResetError):
                return
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("fake cam on :%d" % port)
    srv.serve_forever()
