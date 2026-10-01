"""Shared fixtures: a config, an in-memory database, a fake mod with the
cytra-syncmatica extension pre-loaded with one schematic, and a fake interaction."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import discord

from cytra_syncmatica_bot.config import Config, DiscordConfig, LinkingConfig, LoggingConfig, ServerConfig, SyncmaticaConfig
from cytra_syncmatica_bot.db import Database
from fake_mod_server import ExtensionError, FakeModServer

SCHEMATIC_ID = "8f2a6c1e-1b2c-4d3e-9f00-112233445566"
OP_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5"


def make_cfg(**over) -> Config:
    cfg = Config(
        discord=DiscordConfig(token="t", guild_id=123, feed_channel_id=555, staff_role_ids=[9]),
        server=ServerConfig(name="CMP", host="127.0.0.1", port=0, secret="s3cret", timeout=2.0),
        syncmatica=SyncmaticaConfig(page_size=3),
        linking=LinkingConfig(enabled=True, shared_db_path=""),
        logging=LoggingConfig(level="INFO", file=None),
        database=":memory:",
        base_dir=Path("."),
    )
    for k, v in over.items():
        setattr(cfg, k, v)
    return cfg


class FakeSyncmaticaExtension:
    """Stands in for the mod's bridge: a schematic with five items, permission rules by UUID."""

    def __init__(self):
        self.items = {
            "minecraft:stone": [100, 0], "minecraft:oak_planks": [20, 0], "minecraft:oak_door": [2, 0],
            "minecraft:stone_slab": [6, 0], "minecraft:glass": [10, 0],
        }
        self.known = {OP_UUID: "OpPlayer", "66666666-7777-8888-9999-aaaaaaaaaaaa": "PlainPlayer"}
        self.ops = {OP_UUID}
        self.codes = {"K7P2XQ": (OP_UUID, "OpPlayer")}
        self.actions: list[dict] = []
        self.hide_coordinates = False

    def summary(self):
        req = sum(r for r, _ in self.items.values())
        got = sum(min(g, r) for r, g in self.items.values())
        return {"available": True, "items": len(self.items), "required": req, "gathered": got, "remaining": req - got,
                "percent": round(100.0 * got / req, 1) if req else 100.0, "complete": got >= req}

    def entry(self, item):
        req, got = self.items[item]
        return {"item": item, "required": req, "gathered": got, "remaining": max(0, req - got), "complete": got >= req,
                "stack_size": 64, "remaining_text": str(max(0, req - got)), "editor": None, "edited_at": 0}

    def schematic(self):
        s = {"id": SCHEMATIC_ID, "name": "Iron farm", "file_name": "iron_farm", "owner": {"uuid": OP_UUID, "name": "OpPlayer"},
             "last_modified_by": None, "created_at": 1, "modified_at": 2, "dimension": "minecraft:overworld", "rotation": "NONE",
             "mirror": "NONE", "coordinates_hidden": self.hide_coordinates, "size": {"x": 6, "y": 4, "z": 6}, "block_count": 138,
             "unique_blocks": 5, "materials": self.summary()}
        if not self.hide_coordinates:
            s["origin"] = {"x": 120, "y": 64, "z": -340}
            s["centre"] = {"x": 123, "y": 66, "z": -337}
        return s

    def __call__(self, op, v, payload):
        if op == "ping":
            return {"mod": "1.0.0+1.21.11", "protocol": 1, "schematics": 1, "materials_enabled": True,
                    "coordinates_hidden": self.hide_coordinates, "queued_events": 0, "ops": ["ping"]}
        if op == "list_schematics":
            return {"schematics": [self.schematic()]}
        if op in ("get_schematic", "get_materials", "get_where", "material_action"):
            name = payload.get("schematic"); sid = payload.get("schematic_id")
            if sid not in (None, SCHEMATIC_ID) or (name not in (None, "Iron farm", "iron_farm")):
                raise ExtensionError(f"unknown schematic {name or sid}")
        if op == "get_schematic":
            return {"schematic": self.schematic()}
        if op == "get_where":
            w = {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "dimension": "minecraft:overworld",
                 "coordinates_hidden": self.hide_coordinates}
            if not self.hide_coordinates:
                w.update({"origin": {"x": 120, "y": 64, "z": -340}, "centre": {"x": 123, "y": 66, "z": -337}, "size": {"x": 6, "y": 4, "z": 6}})
            return w
        if op == "get_materials":
            entries = [self.entry(i) for i in self.items]
            if payload.get("missing_only"):
                entries = [e for e in entries if not e["complete"]]
            entries.sort(key=lambda e: -e["remaining"])
            off, lim = int(payload.get("offset", 0)), int(payload.get("limit", 50))
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "summary": self.summary(), "total": len(entries),
                    "offset": off, "limit": lim, "items": entries[off:off + lim]}
        if op == "material_action":
            self.actions.append(payload)
            uuid, item, action = payload.get("mc_uuid"), payload.get("item"), payload.get("action")
            if uuid not in self.known:
                raise ExtensionError(f"unknown player {uuid}: never joined this server")
            if action == "reset" and uuid not in self.ops:
                raise ExtensionError(f"player {self.known[uuid]} is not permitted to reset (needs cytra-syncmatica.materials.reset)")
            if item not in self.items:
                raise ExtensionError(f"unknown item {item} in Iron farm")
            req, got = self.items[item]
            amount = int(payload.get("amount", 1))
            new = {"add": got + amount, "set": amount, "done": req, "reset": 0}[action]
            self.items[item][1] = max(0, min(req, new))
            e = self.entry(item); e["editor"] = {"uuid": uuid, "name": self.known[uuid]}
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "changed": new != got, "item": e, "summary": self.summary()}
        if op == "link_claim":
            claim = self.codes.pop(str(payload.get("code", "")).upper(), None)
            if claim is None:
                raise ExtensionError("unknown or expired code")
            return {"mc_uuid": claim[0], "mc_name": claim[1]}
        raise ExtensionError(f"unknown op {op}")


async def start_fake_server(with_extension: bool = True):
    server = FakeModServer()
    ext = FakeSyncmaticaExtension()
    if with_extension:
        server.extensions["cytra-syncmatica"] = ext
    await server.start()
    return server, ext


async def make_db() -> Database:
    db = Database(":memory:")
    await db.connect()
    return db


class FakeResponse:
    def __init__(self):
        self.deferred = False
        self.messages: list[dict] = []
        self.edits: list[dict] = []
        self.modals: list = []

    async def defer(self, ephemeral: bool = False, **kw) -> None:
        self.deferred = True

    async def send_message(self, content=None, **kwargs) -> None:
        self.messages.append({"content": content, **kwargs})

    async def edit_message(self, **kwargs) -> None:
        self.edits.append(kwargs)

    async def send_modal(self, modal) -> None:
        self.modals.append(modal)

    def is_done(self) -> bool:
        return self.deferred or bool(self.messages) or bool(self.edits)


class FakeMessage:
    def __init__(self, channel_id=555, message_id=777):
        self.edits: list[dict] = []
        self.id = message_id
        self.channel = MagicMock(); self.channel.id = channel_id

    async def edit(self, **kwargs) -> "FakeMessage":
        self.edits.append(kwargs)
        return self


class FakeFollowup:
    def __init__(self):
        self.sent: list[dict] = []

    async def send(self, content=None, **kwargs):
        self.sent.append({"content": content, **kwargs})
        return FakeMessage()


class FakeInteraction:
    def __init__(self, user, client, data=None):
        self.user = user
        self.client = client
        self.guild = None
        self.response = FakeResponse()
        self.followup = FakeFollowup()
        self.extras = {}
        self.data = data or {}
        self.command = MagicMock(); self.command.qualified_name = "fake"


def make_member(user_id: int, roles=()):
    m = MagicMock(spec=discord.Member)
    m.id = user_id
    m.mention = f"<@{user_id}>"
    m.roles = [MagicMock(id=r) for r in roles]
    m.guild_permissions = MagicMock(administrator=False)
    return m


class FakeChannel:
    """A Messageable that records sends and serves fetch_message from what it sent."""

    def __init__(self, channel_id=555):
        self.id = channel_id
        self.sent: list[dict] = []
        self.messages: dict[int, FakeMessage] = {}
        self._next = 1000

    async def send(self, content=None, **kwargs):
        self._next += 1
        msg = FakeMessage(self.id, self._next)
        self.sent.append({"content": content, **kwargs})
        self.messages[msg.id] = msg
        return msg

    async def fetch_message(self, message_id):
        if message_id not in self.messages:
            raise discord.NotFound(MagicMock(status=404, reason="Not Found"), {"message": "Unknown Message", "code": 10008})
        return self.messages[message_id]
