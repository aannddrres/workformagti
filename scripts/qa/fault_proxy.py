"""A TCP relay that can slow, freeze or cut a connection on command.

Put it between the backend and Oracle (or any other TCP service) to see what
the portal does when the database is slow, hangs, or vanishes -- without
touching the database itself. This machine's Oracle 19.3 crashed on a mass
``ALTER SYSTEM KILL SESSION`` once; cutting the wire here is the safe way to
produce the same outage.

    python scripts/qa/fault_proxy.py --listen 15210 --target 127.0.0.1:1521 --control 15299

Then QA_DB_URL=jdbc:oracle:thin:@127.0.0.1:15210/orclpdb1 for qa-backend.sh,
and switch modes over HTTP:

    curl "127.0.0.1:15299/mode?set=pass"
    curl "127.0.0.1:15299/mode?set=delay&ms=3000"   # every chunk waits 3 s
    curl "127.0.0.1:15299/mode?set=freeze"          # bytes stop; sockets stay open
    curl "127.0.0.1:15299/mode?set=refuse"          # new connections are closed at once
    curl "127.0.0.1:15299/cut"                      # reset every open connection now
    curl "127.0.0.1:15299/stats"

"freeze" is the nasty one: the database neither answers nor hangs up, which is
what a dropped firewall state or a half-dead network path looks like.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from urllib.parse import parse_qs, urlparse

STATE = {"mode": "pass", "ms": 0}
STATS = {"accepted": 0, "refused": 0, "open": 0, "cut": 0, "bytes": 0}
CONNS: set[asyncio.StreamWriter] = set()


async def pump(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            while STATE["mode"] == "freeze":
                await asyncio.sleep(0.05)
            if STATE["mode"] == "delay" and STATE["ms"]:
                await asyncio.sleep(STATE["ms"] / 1000)
            writer.write(data)
            await writer.drain()
            STATS["bytes"] += len(data)
    except (ConnectionError, asyncio.CancelledError, OSError):
        pass
    finally:
        try:
            writer.close()
        except OSError:
            pass


async def handle(client_r, client_w, target):
    if STATE["mode"] == "refuse":
        STATS["refused"] += 1
        client_w.close()
        return
    host, port = target
    try:
        server_r, server_w = await asyncio.open_connection(host, port)
    except OSError:
        STATS["refused"] += 1
        client_w.close()
        return
    STATS["accepted"] += 1
    STATS["open"] += 1
    CONNS.update({client_w, server_w})
    try:
        await asyncio.gather(pump(client_r, server_w), pump(server_r, client_w))
    finally:
        STATS["open"] -= 1
        CONNS.discard(client_w)
        CONNS.discard(server_w)


def cut_all() -> int:
    n = 0
    for w in list(CONNS):
        sock = w.get_extra_info("socket")
        try:
            if sock is not None:
                # SO_LINGER 0 turns close() into a TCP reset, as a crash would.
                import socket
                import struct
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            w.close()
            n += 1
        except OSError:
            pass
    CONNS.clear()
    STATS["cut"] += n
    return n


async def control(reader, writer):
    line = (await reader.readline()).decode("latin-1")
    while (await reader.readline()) not in (b"\r\n", b"\n", b""):
        pass
    parts = line.split(" ")
    url = urlparse(parts[1] if len(parts) > 1 else "/")
    q = {k: v[0] for k, v in parse_qs(url.query).items()}
    if url.path == "/mode" and "set" in q:
        STATE["mode"] = q["set"]
        STATE["ms"] = int(q.get("ms", 0))
        body = {"ok": True, **STATE}
    elif url.path == "/cut":
        body = {"ok": True, "cut": cut_all()}
    else:
        body = {**STATE, **STATS}
    payload = json.dumps(body).encode()
    writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                 + str(len(payload)).encode() + b"\r\nConnection: close\r\n\r\n" + payload)
    await writer.drain()
    writer.close()


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=15210)
    ap.add_argument("--target", default="127.0.0.1:1521")
    ap.add_argument("--control", type=int, default=15299)
    a = ap.parse_args()
    th, tp = a.target.rsplit(":", 1)
    relay = await asyncio.start_server(lambda r, w: handle(r, w, (th, int(tp))), "127.0.0.1", a.listen)
    ctl = await asyncio.start_server(control, "127.0.0.1", a.control)
    print(f"fault proxy 127.0.0.1:{a.listen} -> {a.target}; control :{a.control}", flush=True)
    async with relay, ctl:
        await asyncio.gather(relay.serve_forever(), ctl.serve_forever())


if __name__ == "__main__":
    asyncio.run(main())
