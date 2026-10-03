"""A stand-in for the company's OAuth2 token endpoint, with faults on command.

The portal's corporate login (security/CorporateAuthClient.java) makes one
form POST to ``<OAUTH_SERVICE_URI>/oauth/token`` with the ``ldap_auth`` grant.
This server answers that POST the way the real one does -- and, on command,
the ways a real one fails.

    python scripts/qa/fake_idp.py --port 18900

Start the backend with
    CORPORATE_AUTH_ENABLED=true OAUTH_SERVICE_URI=http://127.0.0.1:18900/
    OAUTH_CLIENT_ID=qa OAUTH_SECRET=cWE6cWE= scripts/qa/qa-backend.sh start

Modes (GET /mode?set=...):
    ok        200 with an identity; any password but "wrong" is accepted
    hang      accept the request and never answer
    trickle   answer one byte a second
    huge      a 200 of 40 MB
    html503   the load balancer's HTML error page
    garbage   200 with a body that is not JSON
    noident   200 JSON without a login or email
    client401 the portal's own client credential refused
Per person (GET /user?email=..&roles=INFOPORTAL_OPERATOR&dept=..&name=..):
    the directory's view of them; roles empty means no InfoPortal role.
GET /stats counts calls per mode.
"""

from __future__ import annotations

import argparse
import base64
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MODE = {"set": "ok"}
USERS: dict[str, dict] = {}
STATS: dict[str, int] = {}
LOCK = threading.Lock()


def jwt(claims: dict) -> str:
    def b64(obj) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()
    return f"{b64({'alg': 'none'})}.{b64(claims)}.sig"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # quiet
        pass

    def _send(self, status: int, body: bytes, ctype="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/mode":
            MODE["set"] = q.get("set", "ok")
            return self._send(200, json.dumps(MODE).encode())
        if url.path == "/user":
            email = q["email"].lower()
            USERS[email] = {
                "roles": [r for r in q.get("roles", "").split(",") if r],
                "dept": q.get("dept", "ტექნიკური"),
                "name": q.get("name", email.split("@")[0]),
            }
            return self._send(200, json.dumps(USERS[email], ensure_ascii=False).encode())
        if url.path == "/stats":
            return self._send(200, json.dumps({"mode": MODE["set"], **STATS}).encode())
        self._send(404, b"{}")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        form = {k: v[0] for k, v in parse_qs(self.rfile.read(length).decode()).items()}
        mode = MODE["set"]
        with LOCK:
            STATS[mode] = STATS.get(mode, 0) + 1
        if urlparse(self.path).path.rstrip("/") != "/oauth/token":
            return self._send(404, b"{}")
        if mode == "hang":
            time.sleep(3600)
            return
        if mode == "html503":
            return self._send(503, b"<html><body><h1>503 Service Unavailable</h1></body></html>", "text/html")
        if mode == "client401":
            return self._send(401, b'{"error":"unauthorized","error_description":"Bad client credentials"}')
        if mode == "garbage":
            return self._send(200, b"<<not json>>", "text/plain")
        if mode == "huge":
            return self._send(200, b'{"pad":"' + b"x" * (40 * 1024 * 1024) + b'"}')
        if form.get("password") == "wrong":
            return self._send(400, b'{"error":"invalid_grant","error_description":"Bad credentials"}')
        email = form.get("username", "").lower()
        if mode == "noident":
            return self._send(200, b'{"access_token":"opaque","token_type":"bearer"}')
        person = USERS.get(email, {"roles": ["INFOPORTAL_OPERATOR"], "dept": "ტექნიკური",
                                   "name": email.split("@")[0]})
        claims = {"user_name": email.split("@")[0], "email": email, "userId": email,
                  "authorities": person["roles"], "department": person["dept"],
                  "full_name": person["name"]}
        body = json.dumps({"access_token": jwt(claims), "token_type": "bearer",
                           "expires_in": 3600, "authorities": person["roles"]},
                          ensure_ascii=False).encode()
        if mode == "trickle":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            for b in body:
                self.wfile.write(bytes([b]))
                self.wfile.flush()
                time.sleep(1)
            return
        self._send(200, body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18900)
    a = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    srv.daemon_threads = True
    print(f"fake IdP on http://127.0.0.1:{a.port}/oauth/token", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
