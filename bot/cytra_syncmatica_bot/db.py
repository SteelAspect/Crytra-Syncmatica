"""SQLite state: Discord <-> Minecraft links and the feed messages the bot edits in place."""

from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

import aiosqlite

SCHEMA = """
CREATE TABLE IF NOT EXISTS links (
    discord_id INTEGER PRIMARY KEY,
    mc_uuid TEXT NOT NULL,
    mc_name TEXT NOT NULL,
    linked_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS links_uuid ON links(mc_uuid);
CREATE TABLE IF NOT EXISTS feed_messages (
    schematic_id TEXT PRIMARY KEY,
    channel_id INTEGER NOT NULL,
    message_id INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


@dataclass(slots=True)
class Link:
    discord_id: int
    mc_uuid: str
    mc_name: str
    linked_at: int


@dataclass(slots=True)
class FeedMessage:
    schematic_id: str
    channel_id: int
    message_id: int
    updated_at: int


def now() -> int:
    return int(time.time())


class Database:
    def __init__(self, path: str | Path):
        self.path = str(path)
        self._conn: aiosqlite.Connection | None = None

    @property
    def conn(self) -> aiosqlite.Connection:
        assert self._conn is not None, "Database.connect() first"
        return self._conn

    async def connect(self) -> None:
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = await aiosqlite.connect(self.path)
        self._conn.row_factory = aiosqlite.Row
        await self._conn.executescript(SCHEMA)
        await self._conn.commit()

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    # -- links -------------------------------------------------------------------

    async def get_link_by_discord(self, discord_id: int) -> Link | None:
        async with self.conn.execute("SELECT * FROM links WHERE discord_id = ?", (discord_id,)) as cur:
            row = await cur.fetchone()
        return Link(**dict(row)) if row else None

    async def get_link_by_uuid(self, mc_uuid: str) -> Link | None:
        async with self.conn.execute("SELECT * FROM links WHERE mc_uuid = ?", (mc_uuid.lower(),)) as cur:
            row = await cur.fetchone()
        return Link(**dict(row)) if row else None

    async def set_link(self, discord_id: int, mc_uuid: str, mc_name: str) -> Link:
        """One link per Discord user and per Minecraft account: both old rows are replaced."""
        await self.conn.execute("DELETE FROM links WHERE discord_id = ? OR mc_uuid = ?", (discord_id, mc_uuid.lower()))
        link = Link(discord_id, mc_uuid.lower(), mc_name, now())
        await self.conn.execute("INSERT INTO links VALUES (?, ?, ?, ?)",
                                (link.discord_id, link.mc_uuid, link.mc_name, link.linked_at))
        await self.conn.commit()
        return link

    async def delete_link(self, discord_id: int) -> bool:
        cur = await self.conn.execute("DELETE FROM links WHERE discord_id = ?", (discord_id,))
        await self.conn.commit()
        return cur.rowcount > 0

    async def all_links(self) -> list[Link]:
        async with self.conn.execute("SELECT * FROM links ORDER BY linked_at") as cur:
            return [Link(**dict(r)) for r in await cur.fetchall()]

    # -- feed messages -------------------------------------------------------------

    async def get_feed_message(self, schematic_id: str) -> FeedMessage | None:
        async with self.conn.execute("SELECT * FROM feed_messages WHERE schematic_id = ?", (schematic_id,)) as cur:
            row = await cur.fetchone()
        return FeedMessage(**dict(row)) if row else None

    async def set_feed_message(self, schematic_id: str, channel_id: int, message_id: int) -> None:
        await self.conn.execute("INSERT OR REPLACE INTO feed_messages VALUES (?, ?, ?, ?)",
                                (schematic_id, channel_id, message_id, now()))
        await self.conn.commit()

    async def delete_feed_message(self, schematic_id: str) -> None:
        await self.conn.execute("DELETE FROM feed_messages WHERE schematic_id = ?", (schematic_id,))
        await self.conn.commit()

    async def all_feed_messages(self) -> list[FeedMessage]:
        async with self.conn.execute("SELECT * FROM feed_messages") as cur:
            return [FeedMessage(**dict(r)) for r in await cur.fetchall()]

    # -- meta ------------------------------------------------------------------------

    async def get_meta(self, key: str) -> str | None:
        async with self.conn.execute("SELECT value FROM meta WHERE key = ?", (key,)) as cur:
            row = await cur.fetchone()
        return row["value"] if row else None

    async def set_meta(self, key: str, value: str) -> None:
        await self.conn.execute("INSERT OR REPLACE INTO meta VALUES (?, ?)", (key, value))
        await self.conn.commit()


def lookup_shared_link(shared_db_path: str, discord_id: int) -> tuple[str, str] | None:
    """Read-only fallback into a cytra-bridge bot's database (table players: uuid, ign, discord_id).
    Returns (uuid, name) or None. Never writes; a missing file or table is simply 'no link'."""
    if not shared_db_path or not Path(shared_db_path).is_file():
        return None
    try:
        conn = sqlite3.connect(f"file:{shared_db_path}?mode=ro", uri=True)
        try:
            row = conn.execute("SELECT uuid, ign FROM players WHERE discord_id = ?", (discord_id,)).fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return None
    return (str(row[0]).lower(), str(row[1])) if row else None
