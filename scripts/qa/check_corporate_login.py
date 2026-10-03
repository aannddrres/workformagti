"""Check 4 (Release It!) -- the company's login service misbehaves.

    python scripts/qa/check_corporate_login.py [--port 8094]

Starts fake_idp.py on :18900 and a backend on --port with corporate login
switched on and pointed at it, then signs in under each of the IdP's
failure modes while ten colleagues keep working. What must hold:

  * an outage is "service unavailable" (503), never "wrong password" -- an
    outage must not count against anyone's sign-in limit;
  * no sign-in waits much past the client's limits (5 s connect, 10 s read);
  * a 40 MB answer does not take the backend down;
  * the people already signed in notice nothing;
  * a directory role taken away ends the person's access at their next
    sign-in, and the session they already had (PO decision 2026-09-23).
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_lib import Portal, Report  # noqa: E402

HERE = Path(__file__).resolve().parent
IDP = "http://127.0.0.1:18900"


def mode(m: str) -> None:
    httpx.get(f"{IDP}/mode", params={"set": m}, timeout=5)


def person(email: str, roles: str, dept: str = "ტექნიკური") -> None:
    httpx.get(f"{IDP}/user", params={"email": email, "roles": roles, "dept": dept}, timeout=5)


class Colleagues(threading.Thread):
    def __init__(self, port):
        super().__init__(daemon=True)
        self.s = [Portal(port, client_ip=f"10.150.0.{i}").login(f"test_operator_w{i:03d}@magti.ge")
                  for i in range(1, 11)]
        self.worst, self.bad, self.stop = 0.0, 0, threading.Event()

    def run(self):
        while not self.stop.is_set():
            for s in self.s:
                t0 = time.time()
                try:
                    code = s.get("/api/compliance/my-readings", timeout=60).status_code
                except httpx.HTTPError:
                    code = -1
                self.worst = max(self.worst, time.time() - t0)
                self.bad += code != 200
            time.sleep(0.5)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8094)
    a = ap.parse_args()
    rep = Report("check_corporate_login")
    idp = subprocess.Popen([sys.executable, str(HERE / "fake_idp.py"), "--port", "18900"])
    env = dict(os.environ, CORPORATE_AUTH_ENABLED="true", OAUTH_SERVICE_URI=f"{IDP}/",
               OAUTH_CLIENT_ID="qa", OAUTH_SECRET="cWE6cWE=")
    subprocess.run(["bash", str(HERE / "qa-backend.sh"), "start", str(a.port)], check=True, env=env)
    try:
        time.sleep(1)
        who = f"qa.corp.{int(time.time())}@magticom.ge"
        person(who, "INFOPORTAL_OPERATOR")

        def sign_in(password="right", ip="10.151.0.1", timeout=180):
            t0 = time.time()
            try:
                r = Portal(a.port, timeout=timeout, client_ip=ip).http.post(
                    "/api/auth/login", json={"email": who, "password": password})
                return r.status_code, time.time() - t0, r
            except httpx.HTTPError as e:
                return type(e).__name__, time.time() - t0, None

        mode("ok")
        code, _, r = sign_in()
        rep.check(code == 200, "a directory sign-in works", str(code))
        first_token = r.json()["access_token"] if code == 200 else None
        code, _, _ = sign_in("wrong", ip="10.151.0.2")
        rep.check(code == 401, "a wrong password is 'wrong password'", str(code))

        colleagues = Colleagues(a.port)
        colleagues.start()
        for m, limit in (("hang", 15), ("trickle", 15), ("huge", 30), ("html503", 5), ("garbage", 5),
                         ("noident", 5), ("client401", 5)):
            mode(m)
            code, took, r = sign_in(ip=f"10.152.0.{len(m)}")
            body = r.text[:90] if r is not None else ""
            rep.check(code == 503 and took < limit,
                      f"IdP '{m}': 503 unavailable within {limit} s, not 'wrong password'",
                      f"{code} after {took:.1f} s {body}")
        mode("ok")
        colleagues.stop.set()
        colleagues.join()
        rep.check(colleagues.bad == 0 and colleagues.worst < 2,
                  "colleagues already signed in notice nothing", f"failures {colleagues.bad}, slowest {colleagues.worst:.2f} s")
        alive = Portal(a.port).health()
        rep.check(alive == 200, "the backend is still up after the 40 MB answer", str(alive))

        # Outages counted nothing against the person: still free to sign in.
        code, _, _ = sign_in(ip="10.151.0.1")
        rep.check(code == 200, "after the outages, the person signs in normally (nothing was counted against them)", str(code))

        # Role taken away in the directory.
        person(who, "")
        code, _, _ = sign_in(ip="10.151.0.3")
        rep.check(code in (401, 403), "no InfoPortal role in the directory: no entry", str(code))
        if first_token:
            gone = httpx.get(f"http://127.0.0.1:{a.port}/api/users/me",
                             headers={"Authorization": f"Bearer {first_token}"}, timeout=10).status_code
            rep.check(gone == 401, "...and the session they already had ends too", str(gone))
    finally:
        subprocess.run(["bash", str(HERE / "qa-backend.sh"), "stop", str(a.port)])
        idp.terminate()
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
