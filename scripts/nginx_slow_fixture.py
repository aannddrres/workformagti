"""Synthetic upstream for the shipping-nginx timeout test; never part of the app."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/slow":
            time.sleep(65)
        body = b"synthetic delayed response"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # expected on the old nginx's 60s timeout

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    # The fixture and shipping nginx share an isolated Docker network.
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
