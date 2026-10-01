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
        server=ServerConfig(name="Survival", host="127.0.0.1", port=0, secret="s3cret", timeout=2.0),
        syncmatica=SyncmaticaConfig(page_size=3),
        linking=LinkingConfig(enabled=True),
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
        self.preview_png_b64 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAADklEQVR4nGMoAAMGCAUAJM4FQe9XqBgAAAAASUVORK5CYII="
        self.layers = [{"y": 64 + i, "expected": 36, "placed": 36 if i < 2 else 0} for i in range(4)]
        self.projects: dict[str, dict] = {}  # id -> {"id", "name", "members": [schematic ids]}
        self.project_actions: list[dict] = []

    GROUPS = {"minecraft:stone": "Stone", "minecraft:oak_planks": "Wood", "minecraft:oak_door": "Wood",
              "minecraft:stone_slab": "Stone", "minecraft:glass": "Glass"}

    def summary(self):
        req = sum(r for r, _ in self.items.values())
        got = sum(min(g, r) for r, g in self.items.values())
        return {"available": True, "items": len(self.items), "required": req, "gathered": got, "remaining": req - got,
                "percent": round(100.0 * got / req, 1) if req else 100.0, "complete": got >= req,
                "groups": len(set(self.GROUPS.values()))}

    def entry(self, item):
        req, got = self.items[item]
        return {"item": item, "required": req, "gathered": got, "remaining": max(0, req - got), "complete": got >= req,
                "group": self.GROUPS[item], "stack_size": 64, "remaining_text": str(max(0, req - got)), "editor": None,
                "edited_at": 0}

    def groups(self):
        out = []
        for name in ("Stone", "Wood", "Glass"):
            members = [i for i, g in self.GROUPS.items() if g == name]
            req = sum(self.items[i][0] for i in members)
            got = sum(min(self.items[i][1], self.items[i][0]) for i in members)
            out.append({"name": name, "items": len(members), "required": req, "gathered": got, "remaining": req - got,
                        "percent": round(100.0 * got / req, 1) if req else 100.0, "complete": got >= req})
        return out

    def schematic(self):
        s = {"id": SCHEMATIC_ID, "name": "Iron farm", "file_name": "iron_farm", "owner": {"uuid": OP_UUID, "name": "OpPlayer"},
             "last_modified_by": None, "created_at": 1, "modified_at": 2, "dimension": "minecraft:overworld", "rotation": "NONE",
             "mirror": "NONE", "coordinates_hidden": self.hide_coordinates, "size": {"x": 6, "y": 4, "z": 6}, "block_count": 138,
             "unique_blocks": 5, "materials": self.summary(),
             "preview": {"available": True, "width": 2, "height": 2, "blocks_x": 6, "blocks_z": 6, "scale": 1, "step": 1, "bytes": 70, "generated_at": 5}}
        if not self.hide_coordinates:
            s["origin"] = {"x": 120, "y": 64, "z": -340}
            s["centre"] = {"x": 123, "y": 66, "z": -337}
        return s

    def project(self, p):
        members = [{"id": SCHEMATIC_ID, "name": "Iron farm", "dimension": "minecraft:overworld", "materials": self.summary()}
                   for sid in p["members"] if sid == SCHEMATIC_ID]
        summary = self.summary() if members else {"available": True, "items": 0, "required": 0, "gathered": 0, "remaining": 0,
                                                   "percent": 100.0, "complete": True, "groups": 0}
        summary = dict(summary, lists_missing=0)
        return {"id": p["id"], "name": p["name"], "created_by": "OpPlayer", "created_at": 3, "members": members, "materials": summary}

    def find_project(self, payload):
        pid, name = payload.get("project_id"), payload.get("project")
        for p in self.projects.values():
            if p["id"] == pid or (name and p["name"].lower() == name.lower()):
                return p
        raise ExtensionError(f"unknown project {name or pid}")

    def __call__(self, op, v, payload):
        if op in ("get_materials", "get_groups", "get_shopping_list", "get_where", "material_action") and (payload.get("project") or payload.get("project_id")):
            p = self.find_project(payload)
            if not p["members"]:
                raise ExtensionError(f"no material list yet for project {p['name']}")
            if op == "get_where":
                return {"project_id": p["id"], "project": p["name"], "coordinates_hidden": self.hide_coordinates,
                        "schematics": [self("get_where", v, {"schematic_id": SCHEMATIC_ID})]}
            if op == "material_action":
                self.actions.append(payload)
            out = self(op, v, dict(payload, schematic_id=SCHEMATIC_ID, project=None, project_id=None, _inner=True))
            out.pop("schematic_id", None); out.pop("schematic", None)
            out.update({"project_id": p["id"], "project": p["name"]})
            for it in out.get("items") or []:
                it["parts"] = [{"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "required": it["required"], "gathered": it["gathered"], "remaining": it["remaining"]}]
            return out
        if op == "get_preview":
            name = payload.get("schematic"); sid = payload.get("schematic_id")
            if sid not in (None, SCHEMATIC_ID) or (name not in (None, "Iron farm", "iron_farm")):
                raise ExtensionError(f"unknown schematic {name or sid}")
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "format": "png", "png_base64": self.preview_png_b64,
                    "preview": {"available": True, "width": 2, "height": 2, "blocks_x": 6, "blocks_z": 6, "scale": 1, "step": 1, "bytes": 70, "generated_at": 5}}
        if op == "get_layers":
            name = payload.get("schematic"); sid = payload.get("schematic_id")
            if sid not in (None, SCHEMATIC_ID) or (name not in (None, "Iron farm", "iron_farm")):
                raise ExtensionError(f"unknown schematic {name or sid}")
            layers = [dict(l, percent=100.0 * l["placed"] / l["expected"], complete=l["placed"] >= l["expected"]) for l in self.layers]
            placed = sum(l["placed"] for l in layers); req = sum(l["expected"] for l in layers)
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "scanned": True,
                    "build": {"scanned": True, "required": req, "placed": placed, "percent": round(100.0 * placed / req, 1), "complete": placed >= req,
                              "layers_total": len(layers), "layers_complete": sum(1 for l in layers if l["complete"])},
                    "layers": layers, "layers_total": len(layers), "layers_complete": sum(1 for l in layers if l["complete"])}
        if op == "list_projects":
            return {"projects": [self.project(p) for p in self.projects.values()]}
        if op == "get_project":
            p = self.find_project(payload)
            return {"project": self.project(p), "top_remaining": [self.entry(i) for i in self.items if self.items[i][1] < self.items[i][0]][:5]}
        if op == "project_action":
            self.project_actions.append(payload)
            uuid, action = payload.get("mc_uuid"), payload.get("action")
            if uuid not in self.known:
                raise ExtensionError(f"unknown player {uuid}: never joined this server")
            if uuid not in self.ops:
                raise ExtensionError(f"player {self.known[uuid]} is not permitted to manage projects (needs cytra-syncmatica.project.manage)")
            changed = True
            if action == "create":
                name = str(payload.get("name", "")).strip()
                if not name or any(p["name"].lower() == name.lower() for p in self.projects.values()):
                    raise ExtensionError("a project with that name already exists" if name else "project name is empty")
                pid = f"p{len(self.projects) + 1}"
                p = self.projects[pid] = {"id": pid, "name": name, "members": []}
            else:
                p = self.find_project(payload)
                if action == "delete":
                    del self.projects[p["id"]]
                elif action in ("add", "remove"):
                    if payload.get("schematic") not in ("Iron farm", "iron_farm") and payload.get("schematic_id") != SCHEMATIC_ID:
                        raise ExtensionError(f"unknown schematic {payload.get('schematic')}")
                    if action == "add":
                        changed = SCHEMATIC_ID not in p["members"]
                        if changed: p["members"].append(SCHEMATIC_ID)
                    else:
                        changed = SCHEMATIC_ID in p["members"]
                        if changed: p["members"].remove(SCHEMATIC_ID)
            return {"action": action, "changed": changed, "project": self.project(p)}
        if op == "ping":
            return {"mod": "1.0.0+1.21.11", "protocol": 1, "schematics": 1, "materials_enabled": True,
                    "coordinates_hidden": self.hide_coordinates, "queued_events": 0, "ops": ["ping"]}
        if op == "list_schematics":
            return {"schematics": [self.schematic()]}
        if op in ("get_schematic", "get_materials", "get_groups", "get_shopping_list", "get_where", "material_action"):
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
            if payload.get("group"):
                entries = [e for e in entries if e["group"].lower() == payload["group"].lower()]
            entries.sort(key=lambda e: -e["remaining"])
            off, lim = int(payload.get("offset", 0)), int(payload.get("limit", 50))
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "summary": self.summary(), "total": len(entries),
                    "offset": off, "limit": lim, "items": entries[off:off + lim]}
        if op == "get_shopping_list":
            lines = []
            for item, (req, got) in self.items.items():
                if payload.get("group") and self.GROUPS[item].lower() != payload["group"].lower():
                    continue
                if req - got > 0:
                    lines.append({"item": item, "group": self.GROUPS[item], "remaining": req - got, "stack_size": 64, "text": str(req - got)})
            lines.sort(key=lambda l: (["Stone", "Wood", "Glass"].index(l["group"]), -l["remaining"]))
            text = "Shopping list for Iron farm\n" + "\n".join(f"  {l['item']} {l['text']}" for l in lines)
            out = {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "summary": self.summary(), "total_items": sum(l["remaining"] for l in lines),
                   "total_lines": len(lines), "shulker_boxes": len(lines), "lines": lines, "text": text}
            if payload.get("group"):
                out["group"] = payload["group"]
            return out
        if op == "get_groups":
            return {"schematic_id": SCHEMATIC_ID, "schematic": "Iron farm", "summary": self.summary(), "groups": self.groups()}
        if op == "material_action":
            if not payload.get("_inner"):
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
