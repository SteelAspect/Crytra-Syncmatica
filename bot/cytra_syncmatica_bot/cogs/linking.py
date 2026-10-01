"""Account linking: `/cytra-syncmatica link` in game gives a one-time code,
`/link <code>` here claims it through the bridge. The link is what lets a
button press or `/schematic materials` edit carry the right Minecraft player
(permissions and "last edited by")."""

from __future__ import annotations

import logging

import discord
from discord import app_commands
from discord.ext import commands

from cytra_syncmatica_bot.core import SyncmaticaBot, require_staff
from cytra_syncmatica_bot.modlink import LinkError
from cytra_syncmatica_bot.ui import NO_MENTIONS

log = logging.getLogger(__name__)


class Linking(commands.Cog):
    def __init__(self, bot: SyncmaticaBot):
        self.bot = bot
        self.cfg = bot.cfg

    @app_commands.command(name="link", description="Link your Minecraft account with the code from /cytra-syncmatica link in game")
    @app_commands.describe(code="The 6-character code the game gave you")
    async def link(self, interaction: discord.Interaction, code: str) -> None:
        await interaction.response.defer(ephemeral=True)
        try:
            out = await self.bot.ext("link_claim", {"code": code.strip()})
        except LinkError as exc:
            await interaction.followup.send(f"Could not link: {exc}", ephemeral=True)
            return
        uuid, name = str(out.get("mc_uuid", "")), str(out.get("mc_name", "?"))
        if not uuid:
            await interaction.followup.send("The server did not return a player for that code.", ephemeral=True)
            return
        await self.bot.db.set_link(interaction.user.id, uuid, name)
        log.info("linked %s (%s) to Minecraft %s %s", interaction.user, interaction.user.id, name, uuid)
        await interaction.followup.send(f"Linked to **{name}**. Your edits now count as that player.", ephemeral=True,
                                        allowed_mentions=NO_MENTIONS)

    @app_commands.command(name="unlink", description="Remove your Minecraft link (staff: someone else's)")
    @app_commands.describe(member="Staff only: the member to unlink")
    async def unlink(self, interaction: discord.Interaction, member: discord.Member | None = None) -> None:
        target = interaction.user
        if member is not None and member.id != interaction.user.id:
            if not self.bot.is_staff(interaction.user):
                await interaction.response.send_message("Only staff can unlink someone else.", ephemeral=True)
                return
            target = member
        removed = await self.bot.db.delete_link(target.id)
        await interaction.response.send_message(
            f"Unlinked {target.mention}." if removed else f"{target.mention} had no link here.",
            ephemeral=True, allowed_mentions=NO_MENTIONS)

    @app_commands.command(name="whoami", description="Show which Minecraft account you are linked to")
    async def whoami(self, interaction: discord.Interaction) -> None:
        player = await self.bot.resolve_player(interaction.user.id)
        if player is None:
            await interaction.response.send_message(
                "Not linked. Run `/cytra-syncmatica link` in game, then `/link <code>` here.", ephemeral=True)
            return
        own = await self.bot.db.get_link_by_discord(interaction.user.id)
        source = "" if own else " (from the shared cytra-bridge database)"
        await interaction.response.send_message(f"You are **{player[1]}** (`{player[0]}`){source}.", ephemeral=True)

    @app_commands.command(name="links", description="Staff: list the linked accounts stored by this bot")
    @require_staff()
    async def links(self, interaction: discord.Interaction) -> None:
        rows = await self.bot.db.all_links()
        if not rows:
            await interaction.response.send_message("No links stored here yet.", ephemeral=True)
            return
        text = "\n".join(f"<@{r.discord_id}> → **{r.mc_name}** `{r.mc_uuid}`" for r in rows[:50])
        await interaction.response.send_message(text, ephemeral=True, allowed_mentions=NO_MENTIONS)


async def setup(bot: SyncmaticaBot) -> None:
    if not bot.cfg.linking.enabled:
        log.info("linking disabled in config; /link, /unlink, /whoami, /links not registered")
        return
    await bot.add_cog(Linking(bot))
