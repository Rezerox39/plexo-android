#!/usr/bin/env python3
"""
Range-capable, throttled file server for testing Plexo on a phone.

It advertises Accept-Ranges and serves 206 responses slowly enough that you can
watch the per-link speed split in the app. Run it on a laptop that is reachable
from the phone (same Wi-Fi, or over USB tethering), then paste the printed URL
into Plexo.

    python3 tools/testserver.py --size 800      # 800 MB random file
    python3 tools/testserver.py --size 800 --rate 4   # cap each connection at 4 MB/s

Two links each throttled to 4 MB/s should show ~8 MB/s total in Plexo. One link
alone shows ~4 MB/s, which is the proof that chunks really are split.
"""
import argparse
import os
import re
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")


class Handler(BaseHTTPRequestHandler):
    file_path = ""
    file_size = 0
    rate = 0
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[server] %s\n" % (fmt % args))

    def _send_headers(self, status, length, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()

    def do_HEAD(self):
        self._send_headers(200, self.file_size)

    def do_GET(self):
        start, end = 0, self.file_size - 1
        status = 200
        match = RANGE_RE.match(self.headers.get("Range", ""))
        if match:
            first, last = match.groups()
            if first:
                start = int(first)
                end = int(last) if last else self.file_size - 1
            elif last:
                start = max(0, self.file_size - int(last))
            end = min(end, self.file_size - 1)
            if start > end or start >= self.file_size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % self.file_size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206

        length = end - start + 1
        extra = {"Content-Range": "bytes %d-%d/%d" % (start, end, self.file_size)} if status == 206 else None
        self._send_headers(status, length, extra)

        block = 64 * 1024
        sent = 0
        started = time.monotonic()
        with open(self.file_path, "rb") as handle:
            handle.seek(start)
            while sent < length:
                if self.rate:
                    # Pace this connection so a single link cannot use the whole pipe.
                    due = started + (sent + block) / (self.rate * 1024 * 1024)
                    delay = due - time.monotonic()
                    if delay > 0:
                        time.sleep(delay)
                chunk = handle.read(min(block, length - sent))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                sent += len(chunk)


def local_addresses(port):
    addresses = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            addresses.append(sock.getsockname()[0])
    except OSError:
        pass
    return ["http://%s:%d/sample.bin" % (addr, port) for addr in addresses]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=400, help="file size in MB (default 400)")
    parser.add_argument("--rate", type=float, default=0, help="per-connection cap in MB/s (0 = unlimited)")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--file", default="", help="serve an existing file instead")
    args = parser.parse_args()

    if args.file:
        path = os.path.abspath(args.file)
        size = os.path.getsize(path)
    else:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample.bin")
        size = args.size * 1024 * 1024
        if not os.path.exists(path) or os.path.getsize(path) != size:
            sys.stderr.write("[server] writing %d MB sample to %s\n" % (args.size, path))
            with open(path, "wb") as handle:
                remaining = size
                payload = os.urandom(1024 * 1024)
                while remaining > 0:
                    take = min(remaining, len(payload))
                    handle.write(payload[:take])
                    remaining -= take

    Handler.file_path = path
    Handler.file_size = size
    Handler.rate = args.rate

    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    server.daemon_threads = True
    sys.stderr.write("[server] serving %d MB at %s\n" % (size // (1024 * 1024), path))
    if args.rate:
        sys.stderr.write("[server] per-connection cap: %.1f MB/s\n" % args.rate)
    for url in local_addresses(args.port):
        sys.stderr.write("[server] %s\n" % url)
    sys.stderr.write("[server] paste one of those URLs into Plexo\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
