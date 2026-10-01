"""In-process stand-in for the Cytra Link mod (mod/ in this repo), speaking the
real wire format: Minecraft-handshake preamble, server nonce, AES-GCM frames,
hello/welcome, log push with seq + replay, the request types, and (Cytra Link
0.3.0) extension requests (`ext`) plus pushed extension events (`ev`).

Extensions: put a handler in `self.extensions[namespace]`; it is called as
`handler(op, version, payload)` and may return a dict (-> `ok:true, out`) or
raise `ExtensionError(msg)` (-> `ok:false, err`). The namespaces are listed in
the welcome's `ext` field, so a test can also leave the dict empty to stand in
for a server without the mod. `event(ns, type, payload)` pushes an `ev` frame
to every connected bot."""

from __future__ import annotations

import asyncio
import json
import os
import struct
import time

from cytra_syncmatica_bot.modlink import PREFIX, PROTO, Direction, derive_key


async def _read_varint(reader: asyncio.StreamReader) -> int:
    value = 0
    for i in range(5):
        b = (await reader.readexactly(1))[0]
        value |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            return value
    raise ValueError("varint too long")


def _parse_varint(buf: bytes, pos: int) -> tuple[int, int]:
    value = 0
    for i in range(5):
        b = buf[pos]
        pos += 1
        value |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            return value, pos
    raise ValueError("varint too long")


class ExtensionError(Exception):
    """Raised by a fake extension handler to answer `ok:false, err:<message>`."""


class FakeModServer:
    def __init__(self, secret: str = "s3cret") -> None:
        self.secret = secret
        self.boot = os.urandom(4).hex()
        self.clock = None  # fake 'now' for the ts field; None = real time
        self.seq = 0
        self.ring: list[tuple[int, str]] = []
        self.commands: list[str] = []
        self.responses: dict[str, str] = {"list": "There are 1 of a max of 20 players online: Steve"}
        self.files: dict[str, str] = {"server.properties": "enable-rcon=false\n"}
        self.stats: dict[str, str] = {}
        self.hang_commands = False
        self.answer_pings = True
        self.extensions: dict = {}  # namespace -> handler(op, version, payload)
        self.ext_requests: list[dict] = []
        self.sessions: list[tuple[asyncio.StreamWriter, Direction]] = []
        self.hellos: list[dict] = []
        self._server: asyncio.base_events.Server | None = None
        self.port = 0

    async def start(self) -> int:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]
        return self.port

    async def stop(self) -> None:
        await self.drop_all()
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()

    async def drop_all(self) -> None:
        for writer, _tx in list(self.sessions):
            writer.transport.abort()
        self.sessions.clear()

    async def event(self, ns: str, type_: str, payload: dict | None = None, v: int = 1) -> None:
        """Push an extension event to every connected bot, as LinkEvents.publish does."""
        for writer, tx in list(self.sessions):
            await self._send(writer, tx, {"t": "ev", "ns": ns, "v": v, "type": type_,
                                          "payload": payload or {},
                                          "ts": int((self.clock or time.time()) * 1000)})

    async def log(self, line: str) -> None:
        self.seq += 1
        self.ring.append((self.seq, line))
        for writer, tx in list(self.sessions):
            await self._send(writer, tx, {"t": "log", "seq": self.seq, "line": line,
                                          "ts": int((self.clock or time.time()) * 1000)})

    @staticmethod
    async def _send(writer, tx: Direction, obj: dict) -> None:
        sealed = tx.seal(json.dumps(obj).encode())
        writer.write(struct.pack(">I", len(sealed)) + sealed)
        await writer.drain()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            length = await _read_varint(reader)
            frame = await reader.readexactly(length)
            pid, pos = _parse_varint(frame, 0)
            _proto, pos = _parse_varint(frame, pos)
            slen, pos = _parse_varint(frame, pos)
            address = frame[pos:pos + slen].decode()
            if pid != 0 or not address.startswith(PREFIX):
                writer.close()
                return
            client_nonce = bytes.fromhex(address[len(PREFIX):])
            server_nonce = os.urandom(32)
            writer.write(server_nonce)
            await writer.drain()
            rx = Direction(derive_key(self.secret, "c2s", client_nonce, server_nonce))
            tx = Direction(derive_key(self.secret, "s2c", client_nonce, server_nonce))
            hello = True
            while True:
                (n,) = struct.unpack(">I", await reader.readexactly(4))
                try:
                    msg = json.loads(rx.open(await reader.readexactly(n)))
                except Exception:
                    writer.close()  # wrong secret
                    return
                if hello:
                    hello = False
                    self.hellos.append(msg)
                    since = msg.get("since")
                    same = msg.get("boot") == self.boot
                    await self._send(writer, tx, {"t": "welcome", "proto": PROTO, "boot": self.boot,
                                                  "seq": self.seq, "gap": False, "mod": "test", "mc": "1.21.11",
                                                  "ext": list(self.extensions)})
                    for seq, line in self.ring:
                        if same and since is not None and seq > since:
                            await self._send(writer, tx, {"t": "log", "seq": seq, "line": line})
                    self.sessions.append((writer, tx))
                    continue
                asyncio.create_task(self._answer(writer, tx, msg))
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            self.sessions = [(w, t) for w, t in self.sessions if w is not writer]
            writer.close()

    async def _answer(self, writer, tx, msg: dict) -> None:
        kind, rid = msg.get("t"), msg.get("id")
        res: dict = {"t": "res", "id": rid, "ok": True}
        if kind == "ping":
            if not self.answer_pings:
                return
        elif kind == "cmd":
            self.commands.append(msg["cmd"])
            if self.hang_commands:
                return
            res["out"] = self.responses.get(msg["cmd"], "")
        elif kind == "file":
            if msg["name"] not in ("server.properties", "usercache.json"):
                res = {"t": "res", "id": rid, "ok": False, "err": "not an allowed file"}
            elif msg["name"] in self.files:
                res["out"] = self.files[msg["name"]]
        elif kind == "stats":
            if msg["uuid"] in self.stats:
                res["out"] = self.stats[msg["uuid"]]
        elif kind == "stats_index":
            res["out"] = {u: 1790769600.0 for u in self.stats}
        elif kind == "ext":
            self.ext_requests.append(msg)
            handler = self.extensions.get(msg.get("ns"))
            if handler is None:
                res = {"t": "res", "id": rid, "ok": False, "err": f"no extension {msg.get('ns')}"}
            else:
                try:
                    out = handler(msg.get("op"), msg.get("v", 1), msg.get("payload") or {})
                    if asyncio.iscoroutine(out):
                        out = await out
                    res["out"] = out if out is not None else {}
                except ExtensionError as exc:
                    res = {"t": "res", "id": rid, "ok": False, "err": str(exc)}
        else:
            res = {"t": "res", "id": rid, "ok": False, "err": f"unknown request {kind}"}
        try:
            await self._send(writer, tx, res)
        except ConnectionError:
            pass
