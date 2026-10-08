"""Tiny WebDAV stand-in for testing the app: PROPFIND / MKCOL / PUT with Basic auth."""
import base64
import os
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dav")
AUTH = "Basic " + base64.b64encode(b"test:test-pass").decode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def handle_expect_100(self):
        if self.command == "PUT" and self.headers.get("Authorization") == AUTH                 and not os.path.isdir(os.path.dirname(self.local())):
            self.reply(409)
            self.close_connection = True
            return False
        return super().handle_expect_100()
    def local(self):
        return os.path.join(ROOT, urllib.parse.unquote(self.path).lstrip("/"))

    def reply(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        if self.headers.get("Authorization") == AUTH:
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="dav"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def do_PROPFIND(self):
        if self.authed():
            self.reply(207 if os.path.isdir(self.local()) else 404, b"<multistatus/>")

    def do_DELETE(self):
        if self.authed():
            if os.path.isfile(self.local()):
                os.remove(self.local())
                print("DELETE", urllib.parse.unquote(self.path), flush=True)
                return self.reply(204)
            self.reply(404)

    def do_MKCOL(self):
        if self.authed():
            os.makedirs(self.local(), exist_ok=True)
            self.reply(201)

    def do_PUT(self):
        if not self.authed():
            return
        path = self.local()
        if not os.path.isdir(os.path.dirname(path)):
            return self.reply(409)
        with open(path, "wb") as f:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                while True:
                    n = int(self.rfile.readline().strip(), 16)
                    if n == 0:
                        self.rfile.readline()
                        break
                    f.write(self.rfile.read(n))
                    self.rfile.readline()
            else:
                f.write(self.rfile.read(int(self.headers["Content-Length"])))
        print("PUT", urllib.parse.unquote(self.path), os.path.getsize(path), "bytes", flush=True)
        self.reply(201)


os.makedirs(ROOT, exist_ok=True)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), Handler).serve_forever()
