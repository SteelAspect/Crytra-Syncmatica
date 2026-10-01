"""The bot object: config, database, the Cytra Link connection, account
resolution, and the `link_event` / `link_state` events the cogs listen to."""

from __future__ import annotations

import hashlib
import json
import logging
import time

import discord
from discord import app_commands
from discord.ext import commands

from cytra_syncmatica_bot.config import Config
from cytra_syncmatica_bot.db import Database, lookup_shared_link
from cytra_syncmatica_bot.modlink import EXTENSION, ExtensionMissing, LinkError, LinkUnavailable, ModLink

log = logging.getLogger("bot")

COGS = [
    "cytra_syncmatica_bot.cogs.linking",
    "cytra_syncmatica_bot.cogs.schematics",
    "cytra_syncmatica_bot.cogs.feed",
]
META_SYNC_HASH = "command_sync_hash"
NOT_INSTALLED = ("Cytra-Syncmatica isn't installed on that server (or its Cytra Link is older than 0.3.0), "
                 "so there is nothing to show.")


class NotLinked(app_commands.CheckFailure):
    pass


class NotStaff(app_commands.CheckFailure):
    pass


class SyncmaticaBot(commands.Bot):
    def __init__(self, cfg: Config, db: Database, link: ModLink):
        intents = discord.Intents.default()
        super().__init__(command_prefix=commands.when_mentioned, intents=intents, help_command=None)
        self.cfg = cfg
        self.db = db
        self.link = link
        self.force_sync = False
        self.schematic_cache: list[dict] = []
        self.schematic_cache_at = 0.0

    # -- lifecycle -----------------------------------------------------------------

    async def setup_hook(self) -> None:
        self.tree.on_error = self._on_app_command_error
        self.link.on_state_change(self._on_link_state)
        self.link.on_event(self._on_link_event)
        for ext in COGS:
            try:
                await self.load_extension(ext)
            except Exception:
                log.exception("Failed to load extension %s", ext)
        await self._sync_commands()
        await self.link.start()

    async def close(self) -> None:
        await self.link.stop()
        await super().close()

    async def on_ready(self) -> None:
        log.info("Logged in as %s (%s)", self.user, self.user.id if self.user else "?")

    def _command_fingerprint(self, guild: discord.Object | None) -> str:
        cmds = sorted(self.tree.get_commands(guild=guild), key=lambda c: c.name)
        payload = []
        for c in cmds:
            try:
                payload.append(c.to_dict(self.tree))
            except TypeError:
                payload.append(c.to_dict())
        blob = json.dumps(payload, sort_keys=True) + f"|{guild.id if guild else 0}|{discord.__version__}"
        return hashlib.sha256(blob.encode("utf-8")).hexdigest()

    async def _sync_commands(self) -> None:
        """Sync only when the command set changed (or --sync), so restarts do not burn
        Discord's daily command-write budget."""
        guild = discord.Object(id=self.cfg.discord.guild_id) if self.cfg.discord.guild_id else None
        if guild is not None:
            self.tree.copy_global_to(guild=guild)
        fingerprint = self._command_fingerprint(guild)
        try:
            stored = await self.db.get_meta(META_SYNC_HASH)
        except Exception:
            stored = None
        if stored == fingerprint and not self.force_sync:
            log.info("slash commands unchanged since last sync; skipping")
            return
        try:
            if guild is not None:
                await self.tree.sync(guild=guild)
            else:
                await self.tree.sync()
        except discord.HTTPException:
            log.exception("command sync failed; existing commands keep working")
            return
        await self.db.set_meta(META_SYNC_HASH, fingerprint)

    # -- link plumbing -------------------------------------------------------------

    async def _on_link_state(self, connected: bool) -> None:
        if connected and not self.link.has_extension():
            log.warning("connected to %s, but it has no cytra-syncmatica extension: %s", self.cfg.server.name, NOT_INSTALLED)
        self.schematic_cache_at = 0.0
        self.dispatch("link_state", connected)

    async def _on_link_event(self, ns: str, type_: str, payload: dict, ts) -> None:
        if ns != EXTENSION:
            return
        if type_ in ("schematic_shared", "schematic_removed", "schematic_updated", "resync", "list_created"):
            self.schematic_cache_at = 0.0
        self.dispatch("link_event", type_, payload, ts)

    @property
    def extension_available(self) -> bool:
        return self.link.is_connected and self.link.has_extension()

    async def ext(self, op: str, payload: dict | None = None, timeout: float | None = None) -> dict:
        """One request to the mod; translates 'not installed' into a readable error."""
        if self.link.is_connected and not self.link.has_extension():
            raise ExtensionMissing(NOT_INSTALLED)
        return await self.link.ext(op, payload, timeout=timeout)

    async def schematics(self, max_age: float = 30.0) -> list[dict]:
        """list_schematics, cached briefly for autocomplete."""
        now = time.monotonic()
        if self.schematic_cache and now - self.schematic_cache_at < max_age:
            return self.schematic_cache
        out = await self.ext("list_schematics")
        self.schematic_cache = list(out.get("schematics") or [])
        self.schematic_cache_at = now
        return self.schematic_cache

    # -- accounts --------------------------------------------------------------------

    async def resolve_player(self, discord_id: int) -> tuple[str, str] | None:
        """(mc_uuid, mc_name) for a Discord user: own links first, then the optional
        read-only cytra-bridge database."""
        link = await self.db.get_link_by_discord(discord_id)
        if link is not None:
            return link.mc_uuid, link.mc_name
        shared = lookup_shared_link(self.cfg.linking.shared_db_path, discord_id)
        return shared

    def is_staff(self, user: discord.abc.User) -> bool:
        if not isinstance(user, discord.Member):
            return False
        if user.guild_permissions.administrator:
            return True
        ids = set(self.cfg.discord.staff_role_ids)
        return any(r.id in ids for r in user.roles)

    def channel(self, channel_id: int) -> discord.abc.Messageable | None:
        if not channel_id:
            return None
        ch = self.get_channel(channel_id)
        return ch if isinstance(ch, discord.abc.Messageable) else None

    # -- errors -----------------------------------------------------------------------

    async def _on_app_command_error(self, interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
        if isinstance(error, NotLinked):
            message = "Link your Minecraft account first: run `/cytra-syncmatica link` in game, then `/link <code>` here."
        elif isinstance(error, NotStaff):
            message = "You don't have permission to use this command."
        elif isinstance(error, app_commands.CheckFailure):
            message = "You can't use this command here."
        else:
            cause = getattr(error, "original", error)
            if isinstance(cause, ExtensionMissing):
                message = str(cause) if str(cause) else NOT_INSTALLED
            elif isinstance(cause, LinkUnavailable):
                message = f"The server link is down right now ({cause}). Try again in a moment."
            elif isinstance(cause, LinkError):
                message = f"The server refused that: {cause}"
            else:
                log.exception("Unhandled app command error in /%s",
                              interaction.command.qualified_name if interaction.command else "?", exc_info=error)
                message = "Something went wrong running that command. It's been logged."
        try:
            if interaction.response.is_done():
                await interaction.followup.send(message, ephemeral=True)
            else:
                await interaction.response.send_message(message, ephemeral=True)
        except discord.DiscordException:
            log.exception("Failed to send error response")


def require_link():
    """App-command check: the user must have a linked Minecraft account. The
    resolved (uuid, name) is stored on interaction.extras['player']."""
    async def predicate(interaction: discord.Interaction) -> bool:
        bot: SyncmaticaBot = interaction.client  # type: ignore[assignment]
        player = await bot.resolve_player(interaction.user.id)
        if player is None:
            raise NotLinked()
        interaction.extras["player"] = player
        return True
    return app_commands.check(predicate)


def require_staff():
    async def predicate(interaction: discord.Interaction) -> bool:
        bot: SyncmaticaBot = interaction.client  # type: ignore[assignment]
        if not bot.is_staff(interaction.user):
            raise NotStaff()
        return True
    return app_commands.check(predicate)
