#!/usr/bin/env python3
"""Dev-only forwarder for emulator voice tests (spec §10.3, §7.5).

The emulator can't reach Tailscale hosts, but it reaches this Mac at 10.0.2.2: set the keyboard's server
URL to http://10.0.2.2:9100/ and run this. Fault injection, read per request from build/voice-proxy.mode
(set with `tools/dev.sh proxy <mode>`):
  pass (default) | drop (close without answering) | 503 | slow (wait 30 s, past the client's read timeout)
Logs one line per request: mode, method, path, status, time. Never audio or text.
"""
import argparse
import http.server
import os
import socket
import time
import urllib.error
import urllib.request

MODE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "voice-proxy.mode")


def mode():
    try:
        with open(MODE_FILE) as f:
            return f.read().strip() or "pass"
    except OSError:
        return "pass"


class Proxy(http.server.BaseHTTPRequestHandler):
    upstream = ""

    def forward(self):
        m, t0, status = mode(), time.time(), "-"
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            if m == "drop":
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            if m == "503":
                status = 503
                self.send_error(503)
                return
            if m == "slow":
                time.sleep(30)
            req = urllib.request.Request(self.upstream + self.path, data=body if self.command == "POST" else None, method=self.command)
            if self.headers.get("Content-Type"):
                req.add_header("Content-Type", self.headers["Content-Type"])
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    status, data = r.status, r.read()
            except urllib.error.HTTPError as e:
                status, data = e.code, e.read()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            print(f"{m:5} {self.command} {self.path} {status} {int((time.time() - t0) * 1000)} ms", flush=True)

    do_GET = do_POST = do_DELETE = forward

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9100)
    ap.add_argument("--upstream", required=True, help="e.g. http://mybox:9000")
    a = ap.parse_args()
    Proxy.upstream = a.upstream.rstrip("/")
    http.server.ThreadingHTTPServer(("127.0.0.1", a.port), Proxy).serve_forever()
