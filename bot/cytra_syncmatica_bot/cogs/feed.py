"""The feed channel: shared schematics, completions and progress. One
"materials" message per schematic is edited in place as item_changed events
arrive instead of posting again each time; on `resync` (bot attached) every
tracked message is re-fetched and refreshed. The message ids live in SQLite so
a bot restart keeps editing the same messages."""

from __future__ import annotations

import logging

import discord
from discord.ext import commands

from cytra_syncmatica_bot import ui
from cytra_syncmatica_bot.core import SyncmaticaBot
from cytra_syncmatica_bot.modlink import LinkError

log = logging.getLogger(__name__)


class Feed(commands.Cog):
    def __init__(self, bot: SyncmaticaBot):
        self.bot = bot
        self.cfg = bot.cfg
        self.posted: list[tuple[str, dict]] = []  # tests inspect this

    @property
    def channel(self) -> discord.abc.Messageable | None:
        return self.bot.channel(self.cfg.discord.feed_channel_id)

    @commands.Cog.listener()
    async def on_link_event(self, type_: str, payload: dict, ts) -> None:
        if self.channel is None:
            return
        handler = getattr(self, f"ev_{type_}", None)
        if handler is not None:
            try:
                await handler(payload)
            except Exception:
                log.exception("feed handler for %s failed", type_)

    # -- events --------------------------------------------------------------------

    async def ev_schematic_shared(self, payload: dict) -> None:
        if not self.cfg.syncmatica.announce_schematic_shared:
            return
        s = payload.get("schematic") or {}
        await self._send(embed=ui.schematic_embed(s, self.cfg.server.name))

    async def ev_schematic_updated(self, payload: dict) -> None:
        s = payload.get("schematic") or {}
        if s.get("id"):
            await self.refresh_materials_message(s["id"])

    async def ev_schematic_removed(self, payload: dict) -> None:
        sid = payload.get("id")
        if not sid:
            return
        fm = await self.bot.db.get_feed_message(sid)
        if fm is not None:
            msg = await self._fetch_message(fm.channel_id, fm.message_id)
            if msg is not None:
                try:
                    await msg.edit(content=f"*{payload.get('name', 'This schematic')} is no longer shared.*", embed=None, view=None)
                except discord.HTTPException:
                    pass
            await self.bot.db.delete_feed_message(sid)

    async def ev_list_created(self, payload: dict) -> None:
        s = payload.get("schematic") or {}
        if s.get("id"):
            await self.refresh_materials_message(s["id"], s.get("name"))

    async def ev_item_changed(self, payload: dict) -> None:
        sid = payload.get("schematic_id")
        if sid:
            await self.refresh_materials_message(sid, payload.get("schematic"))

    async def ev_item_completed(self, payload: dict) -> None:
        if not self.cfg.syncmatica.announce_item_completed:
            return
        item = payload.get("item") or {}
        who = ui.player_text(payload.get("editor"))
        await self._send(content=f"✅ **{ui.item_name(item.get('item', '?'))}** for **{payload.get('schematic', '?')}** is complete ({item.get('required', '?'):,} gathered) · {who}")

    async def ev_group_completed(self, payload: dict) -> None:
        sid = payload.get("schematic_id")
        if sid:
            await self.refresh_materials_message(sid, payload.get("schematic"))
        if not self.cfg.syncmatica.announce_group_completed:
            return
        g = payload.get("group") or {}
        await self._send(content=f"📦 Group **{g.get('name', '?')}** for **{payload.get('schematic', '?')}** is complete "
                                 f"({g.get('items', '?')} items, {g.get('required', 0):,} gathered) · {ui.player_text(payload.get('editor'))}")

    async def ev_schematic_completed(self, payload: dict) -> None:
        s = payload.get("schematic") or {}
        if s.get("id"):
            await self.refresh_materials_message(s["id"], s.get("name"))
        if not self.cfg.syncmatica.announce_schematic_completed:
            return
        await self._send(content=f"🎉 All materials for **{s.get('name', '?')}** are gathered · {ui.player_text(payload.get('editor'))}")

    async def ev_resync(self, payload: dict) -> None:
        for fm in await self.bot.db.all_feed_messages():
            await self.refresh_materials_message(fm.schematic_id)

    # -- the per-schematic materials message -----------------------------------------------

    async def refresh_materials_message(self, schematic_id: str, name: str | None = None) -> None:
        if not self.cfg.syncmatica.feed_edit:
            return
        try:
            out = await self.bot.ext("get_materials", {"schematic_id": schematic_id, "missing_only": True,
                                                       "offset": 0, "limit": self.cfg.syncmatica.page_size})
        except LinkError as exc:
            log.debug("materials message for %s not refreshed: %s", schematic_id, exc)
            return
        summary = out.get("summary") or {}
        embed = ui.materials_embed(out.get("schematic") or name or schematic_id, self.cfg.server.name, summary,
                                   list(out.get("items") or []), 1, 1, True)
        embed.set_footer(text="live · updated as the team gathers")
        fm = await self.bot.db.get_feed_message(schematic_id)
        if fm is not None:
            msg = await self._fetch_message(fm.channel_id, fm.message_id)
            if msg is not None:
                try:
                    await msg.edit(embed=embed)
                    self.posted.append(("edit", {"schematic_id": schematic_id}))
                    return
                except discord.HTTPException:
                    pass
            await self.bot.db.delete_feed_message(schematic_id)
        msg = await self._send(embed=embed)
        if msg is not None:
            await self.bot.db.set_feed_message(schematic_id, msg.channel.id, msg.id)

    async def _fetch_message(self, channel_id: int, message_id: int) -> discord.Message | None:
        channel = self.bot.channel(channel_id)
        if channel is None:
            return None
        try:
            return await channel.fetch_message(message_id)  # type: ignore[union-attr]
        except (discord.NotFound, discord.Forbidden, discord.HTTPException):
            return None

    async def _send(self, **kwargs) -> discord.Message | None:
        channel = self.channel
        if channel is None:
            return None
        try:
            msg = await channel.send(allowed_mentions=ui.NO_MENTIONS, **kwargs)
            self.posted.append(("send", kwargs))
            return msg
        except discord.HTTPException as exc:
            log.warning("could not post to the feed channel: %s", exc)
            return None


async def setup(bot: SyncmaticaBot) -> None:
    await bot.add_cog(Feed(bot))
