import socket, sys, time

HOST, PORT = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1", 8554
URL = f"rtsp://{HOST}:{PORT}/live"
transport = sys.argv[2] if len(sys.argv) > 2 else "udp"

s = socket.create_connection((HOST, PORT), timeout=8)
s.settimeout(6)


def send(req):
    print(">>> " + req.splitlines()[0])
    s.sendall(req.encode())
    data = b""
    while True:
        try:
            chunk = s.recv(4096)
        except socket.timeout:
            break
        if not chunk:
            break
        data += chunk
        if b"\r\n\r\n" in data:
            head, rest = data.split(b"\r\n\r\n", 1)
            cl = 0
            for line in head.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    cl = int(line.split(b":")[1])
            if len(rest) >= cl:
                break
    print(data.decode("utf-8", "replace"))
    return data


send(f"OPTIONS {URL} RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: probe\r\n\r\n")
sdp = send(f"DESCRIBE {URL} RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n")

if transport == "tcp":
    t = "RTP/AVP/TCP;unicast;interleaved=0-1"
else:
    t = "RTP/AVP;unicast;client_port=50000-50001"
r = send(f"SETUP {URL}/trackID=0 RTSP/1.0\r\nCSeq: 3\r\nTransport: {t}\r\n\r\n")

sess = ""
for line in r.decode("utf-8", "replace").split("\r\n"):
    if line.lower().startswith("session:"):
        sess = line.split(":", 1)[1].strip().split(";")[0]

send(f"PLAY {URL} RTSP/1.0\r\nCSeq: 4\r\nSession: {sess}\r\nRange: npt=0.000-\r\n\r\n")

if transport != "tcp":
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    u.bind(("0.0.0.0", 50000))
    u.settimeout(4)
    n, b = 0, 0
    t0 = time.time()
    while time.time() - t0 < 4:
        try:
            d, _ = u.recvfrom(2048)
            n += 1
            b += len(d)
        except socket.timeout:
            break
    print(f"UDP RTP 包: {n} 个, {b} 字节")
else:
    s.settimeout(4)
    n, b = 0, 0
    t0 = time.time()
    try:
        while time.time() - t0 < 4:
            d = s.recv(65536)
            if not d:
                break
            b += len(d)
            n += 1
    except socket.timeout:
        pass
    print(f"TCP interleaved 读取: {n} 次, {b} 字节")

s.close()
