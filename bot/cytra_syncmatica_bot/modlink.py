"""The bot's end of Cytra Link (0.3.0+): one encrypted TCP connection the bot
opens to the Minecraft server. Mirrors mod/src/main/java/net/cytra/link in the
cytra-bridge repository and bot/modlink.py there, trimmed to what this bot
needs: hello/welcome, ping, generic requests, `ext` requests to the
cytra-syncmatica extension, and `ev` events dispatched to the cogs.

Wire format:
1. bot -> mod: a Minecraft handshake packet whose address is
   `cytra-link:<64 hex chars of client nonce>`;
2. mod -> bot: 32 raw bytes of server nonce;
3. both: frames of `u32 length || AES-256-GCM(json)`, one key per direction,
   key = HMAC-SHA256(SHA256(secret), "cytra-link c2s|s2c" || client_nonce ||
   server_nonce), nonce = 4 zero bytes || u64 big-endian message counter.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import inspect
import json
import logging
import os
import struct
import time
from typing import AsyncIterator, Callable

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

log = logging.getLogger(__name__)

PROTO = 1
PREFIX = "cytra-link:"
MAX_FRAME = 8 * 1024 * 1024
MAX_REQUEST = 64 * 1024
EXTENSION = "cytra-syncmatica"
_MAX_BACKOFF = 60.0
_PING_SECONDS = 15.0
_AUTH_WARN_INTERVAL = 300.0


class LinkError(Exception):
    """The mod refused a request (ok:false)."""


class LinkUnavailable(LinkError):
    """The link is down, the server is not running, or a request timed out."""


class LinkAuthError(LinkError):
    """Wrong secret (or blocked by allow_ips)."""


class ExtensionMissing(LinkError):
    """The server has Cytra Link but not Cytra-Syncmatica (or Cytra Link older than 0.3.0)."""


def derive_key(secret: str, label: str, client_nonce: bytes, server_nonce: bytes) -> bytes:
    master = hashlib.sha256(secret.encode("utf-8")).digest()
    return hmac.new(master, f"cytra-link {label}".encode("ascii") + client_nonce + server_nonce, hashlib.sha256).digest()


class Direction:
    def __init__(self, key: bytes) -> None:
        self._aead = AESGCM(key)
        self._counter = 0

    def _nonce(self) -> bytes:
        nonce = b"\0\0\0\0" + struct.pack(">Q", self._counter)
        self._counter += 1
        return nonce

    def seal(self, plain: bytes) -> bytes:
        return self._aead.encrypt(self._nonce(), plain, None)

    def open(self, sealed: bytes) -> bytes:
        return self._aead.decrypt(self._nonce(), sealed, None)


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def preamble(client_nonce: bytes, port: int) -> bytes:
    address = (PREFIX + client_nonce.hex()).encode("ascii")
    body = _varint(0) + _varint(774) + _varint(len(address)) + address + struct.pack(">H", port & 0xFFFF) + _varint(1)
    return _varint(len(body)) + body


class ModLink:
    """`start()` keeps the link up with backoff; `ext()` sends one request to the
    Cytra-Syncmatica extension. `on_event(cb)` callbacks get `(ns, type, payload, ts)`
    for every `ev` frame; `on_state_change(cb)` gets `True`/`False`."""

    def __init__(self, host: str, port: int, secret: str, timeout: float = 8.0):
        self.host, self.port, self.secret, self.timeout = host, port, secret, timeout
        self.max_backoff = _MAX_BACKOFF
        self.ping_seconds = _PING_SECONDS
        self.mod_version: str | None = None
        self.mc_version: str | None = None
        self.extensions: list[str] = []
        self._connected = asyncio.Event()
        self._closing = False
        self._supervisor: asyncio.Task | None = None
        self._reader_task: asyncio.Task | None = None
        self._state_callbacks: list[Callable] = []
        self._event_callbacks: list[Callable] = []
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._writer: asyncio.StreamWriter | None = None
        self._tx: Direction | None = None
        self._rx: Direction | None = None
        self._write_lock = asyncio.Lock()
        self._boot: str | None = None
        self._seq: int | None = None
        self._last_auth_warn: float | None = None
        self.log_lines_seen = 0

    # -- public ------------------------------------------------------------------

    @property
    def is_connected(self) -> bool:
        return self._connected.is_set()

    def has_extension(self, ns: str = EXTENSION) -> bool:
        return ns in self.extensions

    def on_state_change(self, callback: Callable) -> None:
        self._state_callbacks.append(callback)

    def on_event(self, callback: Callable) -> None:
        self._event_callbacks.append(callback)

    async def start(self) -> None:
        self._closing = False
        self._supervisor = asyncio.create_task(self._connection_loop(), name="modlink-supervisor")

    async def connect_once(self) -> None:
        """One attempt, no retry loop (doctor / tests)."""
        reader = await self._connect_once()
        self._connected.set()
        self._reader_task = asyncio.create_task(self._run(reader), name="modlink-reader")

    async def stop(self) -> None:
        self._closing = True
        for task in (self._reader_task, self._supervisor):
            if task is not None:
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass
        self._reader_task = self._supervisor = None
        await self._teardown(notify=False)

    async def request(self, kind: str, **fields) -> dict:
        if not self._connected.is_set():
            raise LinkUnavailable("server link is not connected")
        self._next_id = self._next_id % 0x7FFFFFFF + 1
        req_id = self._next_id
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        try:
            await self._send({"t": kind, "id": req_id, **fields})
            res = await asyncio.wait_for(fut, self.timeout)
        except asyncio.TimeoutError:
            raise LinkUnavailable(f"server link request timed out: {kind}") from None
        except (ConnectionError, OSError) as exc:
            raise LinkUnavailable(f"server link failed: {exc}") from exc
        finally:
            self._pending.pop(req_id, None)
        if not res.get("ok"):
            err = res.get("err") or "request failed"
            if err == "server is not running":
                raise LinkUnavailable(err)
            if err.startswith("no extension "):
                raise ExtensionMissing(err)
            raise LinkError(err)
        return res

    async def ext(self, op: str, payload: dict | None = None, ns: str = EXTENSION, v: int = 1, timeout: float | None = None) -> dict:
        """One request to an extension; returns the `out` object. Raises ExtensionMissing when the
        server does not have it, LinkError with the mod's message otherwise."""
        if self.is_connected and not self.has_extension(ns):
            raise ExtensionMissing(f"no extension {ns}")
        old = self.timeout
        if timeout is not None:
            self.timeout = timeout
        try:
            res = await self.request("ext", ns=ns, v=v, op=op, payload=payload or {})
        finally:
            self.timeout = old
        out = res.get("out")
        return out if isinstance(out, dict) else {}

    # -- internals --------------------------------------------------------------------

    async def _send(self, obj: dict) -> None:
        async with self._write_lock:
            if self._writer is None or self._tx is None:
                raise LinkUnavailable("server link is not connected")
            raw = json.dumps(obj, separators=(",", ":")).encode("utf-8")
            if len(raw) > MAX_REQUEST:
                raise LinkError(f"request too large ({len(raw)} bytes; the mod accepts {MAX_REQUEST})")
            sealed = self._tx.seal(raw)
            self._writer.write(struct.pack(">I", len(sealed)) + sealed)
            await self._writer.drain()

    async def _connection_loop(self) -> None:
        backoff = 1.0
        while not self._closing:
            try:
                reader = await self._connect_once()
                backoff = 1.0
                self._connected.set()
                log.info("server link connected to %s:%s (Cytra Link %s, Minecraft %s, extensions %s)",
                         self.host, self.port, self.mod_version, self.mc_version, self.extensions)
                await self._notify_state(True)
                await self._run(reader)
            except asyncio.CancelledError:
                raise
            except LinkAuthError as exc:
                now = time.monotonic()
                if self._last_auth_warn is None or now - self._last_auth_warn >= _AUTH_WARN_INTERVAL:
                    self._last_auth_warn = now
                    log.error("%s (retrying every %.0fs)", exc, self.max_backoff)
                backoff = self.max_backoff
            except Exception as exc:
                log.warning("server link lost/failed: %s (retry in %.0fs)", exc, backoff)
            await self._teardown(notify=True)
            if self._closing:
                break
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, self.max_backoff)

    async def _connect_once(self) -> asyncio.StreamReader:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(self.host, self.port), self.timeout)
        self._writer = writer
        client_nonce = os.urandom(32)
        writer.write(preamble(client_nonce, self.port))
        await writer.drain()
        try:
            server_nonce = await asyncio.wait_for(reader.readexactly(32), self.timeout)
        except asyncio.IncompleteReadError:
            raise ConnectionError("the server closed the connection at once: is the Cytra Link mod installed, "
                                  "and is server.port right (the game port unless the mod has its own)?") from None
        except asyncio.TimeoutError:
            raise ConnectionError(f"no answer from {self.host}:{self.port} within {self.timeout:.0f}s: a Minecraft "
                                  "server is there but it did not recognise the link (is Cytra Link installed?)") from None
        self._tx = Direction(derive_key(self.secret, "c2s", client_nonce, server_nonce))
        self._rx = Direction(derive_key(self.secret, "s2c", client_nonce, server_nonce))
        await self._send({"t": "hello", "proto": PROTO, "boot": self._boot, "since": self._seq})
        try:
            welcome = await asyncio.wait_for(self._read_frame(reader), self.timeout)
        except (asyncio.IncompleteReadError, ConnectionError):
            raise LinkAuthError("server link rejected: wrong secret (server.secret must match "
                                "config/cytra-link.properties on the server), or blocked by allow_ips") from None
        if welcome.get("t") != "welcome":
            raise ConnectionError(f"unexpected first message from the mod: {welcome.get('t')!r}")
        if welcome.get("proto") != PROTO:
            raise ConnectionError(f"mod speaks link protocol {welcome.get('proto')}, this bot speaks {PROTO}")
        self.mod_version, self.mc_version = welcome.get("mod"), welcome.get("mc")
        ext = welcome.get("ext")
        self.extensions = [str(x) for x in ext] if isinstance(ext, list) else []
        if welcome.get("boot") != self._boot:
            self._boot, self._seq = welcome.get("boot"), welcome.get("seq")
        return reader

    async def _read_frame(self, reader: asyncio.StreamReader) -> dict:
        (length,) = struct.unpack(">I", await reader.readexactly(4))
        if length > MAX_FRAME:
            raise ConnectionError(f"oversize frame from the mod ({length} bytes)")
        sealed = await reader.readexactly(length)
        try:
            return json.loads(self._rx.open(sealed))
        except InvalidTag:
            raise ConnectionError("a frame from the mod failed to decrypt") from None

    async def _run(self, reader: asyncio.StreamReader) -> None:
        pinger = asyncio.create_task(self._ping_loop(), name="modlink-ping")
        try:
            while True:
                try:
                    msg = await self._read_frame(reader)
                except asyncio.IncompleteReadError:
                    return
                await self._dispatch(msg)
        finally:
            pinger.cancel()
            try:
                await pinger
            except (asyncio.CancelledError, Exception):
                pass

    async def _dispatch(self, msg: dict) -> None:
        kind = msg.get("t")
        if kind == "log":
            seq = msg.get("seq")
            if isinstance(seq, int):
                self._seq = seq
            self.log_lines_seen += 1
        elif kind == "res":
            fut = self._pending.get(msg.get("id"))
            if fut is not None and not fut.done():
                fut.set_result(msg)
        elif kind == "ev":
            for cb in self._event_callbacks:
                try:
                    result = cb(msg.get("ns"), msg.get("type"), msg.get("payload") or {}, msg.get("ts"))
                    if inspect.isawaitable(result):
                        await result
                except Exception:
                    log.exception("event callback failed for %s/%s", msg.get("ns"), msg.get("type"))

    async def _ping_loop(self) -> None:
        while True:
            await asyncio.sleep(self.ping_seconds)
            try:
                await self.request("ping")
            except LinkUnavailable:
                log.warning("server link: no answer to ping, reconnecting")
                if self._writer is not None:
                    self._writer.close()
                return
            except LinkError:
                pass

    async def _teardown(self, notify: bool) -> None:
        was_connected = self._connected.is_set()
        self._connected.clear()
        writer, self._writer, self._tx = self._writer, None, None
        self.extensions = []
        if writer is not None:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(LinkUnavailable("server link closed"))
        self._pending.clear()
        if notify and was_connected:
            log.warning("server link lost")
            await self._notify_state(False)

    async def _notify_state(self, connected: bool) -> None:
        for cb in self._state_callbacks:
            try:
                result = cb(connected)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                log.exception("server link state-change callback failed")
