"""Slash commands: /schematic list|info|materials|where and the interactive
materials message (select an item, then +1/+16/+64/Done/Reset or set an exact
count in a modal). Every write carries the Discord user's linked Minecraft
UUID; a refusal from the mod (not permitted, unknown item, ...) is shown only
to the person who pressed the button."""

from __future__ import annotations

import logging
import math

import discord
from discord import app_commands
from discord.ext import commands

from cytra_syncmatica_bot import ui
from cytra_syncmatica_bot.core import SyncmaticaBot, require_link
from cytra_syncmatica_bot.modlink import LinkError

log = logging.getLogger(__name__)
VIEW_TIMEOUT = 15 * 60


async def schematic_autocomplete(interaction: discord.Interaction, current: str) -> list[app_commands.Choice[str]]:
    bot: SyncmaticaBot = interaction.client  # type: ignore[assignment]
    try:
        names = [s["name"] for s in await bot.schematics()]
    except Exception:
        return []
    cur = current.lower()
    return [app_commands.Choice(name=n[:100], value=n[:100]) for n in names if cur in n.lower()][:25]


class SetCountModal(discord.ui.Modal, title="Set gathered count"):
    amount = discord.ui.TextInput(label="Gathered (whole number)", placeholder="e.g. 128", max_length=9)

    def __init__(self, view: "MaterialsView", item: str):
        super().__init__()
        self.view_ref = view
        self.item = item

    async def on_submit(self, interaction: discord.Interaction) -> None:
        try:
            value = int(str(self.amount.value).strip())
        except ValueError:
            await interaction.response.send_message("That is not a whole number.", ephemeral=True)
            return
        await self.view_ref.act(interaction, "set", amount=max(0, value), item=self.item)


class MaterialsView(discord.ui.View):
    """Paged material list with an item select and edit buttons. State is re-fetched
    from the mod on every refresh so several people can use the same message."""

    def __init__(self, bot: SyncmaticaBot, schematic_id: str, schematic_name: str, missing_only: bool, page: int = 0):
        super().__init__(timeout=VIEW_TIMEOUT)
        self.bot = bot
        self.schematic_id = schematic_id
        self.schematic_name = schematic_name
        self.missing_only = missing_only
        self.page = page
        self.pages = 1
        self.items: list[dict] = []
        self.summary: dict = {}
        self.selected: str | None = None
        self.message: discord.Message | None = None

    @property
    def page_size(self) -> int:
        return self.bot.cfg.syncmatica.page_size

    async def fetch(self) -> None:
        out = await self.bot.ext("get_materials", {"schematic_id": self.schematic_id, "missing_only": self.missing_only,
                                                   "offset": self.page * self.page_size, "limit": self.page_size})
        self.items = list(out.get("items") or [])
        self.summary = out.get("summary") or {}
        self.schematic_name = out.get("schematic", self.schematic_name)
        total = int(out.get("total", 0))
        self.pages = max(1, math.ceil(total / self.page_size))
        if self.page >= self.pages:
            self.page = self.pages - 1
        if self.selected and all(i["item"] != self.selected for i in self.items):
            self.selected = None
        self._rebuild()

    def embed(self) -> discord.Embed:
        return ui.materials_embed(self.schematic_name, self.bot.cfg.server.name, self.summary, self.items,
                                 self.page + 1, self.pages, self.missing_only)

    def _rebuild(self) -> None:
        self.clear_items()
        select = discord.ui.Select(placeholder="Pick an item to edit…", min_values=1, max_values=1, row=0,
                                   options=[discord.SelectOption(label=ui.item_name(i["item"])[:100],
                                                                 description=f"{i['gathered']}/{i['required']} gathered"[:100],
                                                                 value=i["item"][:100], default=i["item"] == self.selected)
                                            for i in self.items] or [discord.SelectOption(label="nothing here", value="-")])
        select.callback = self._on_select
        select.disabled = not self.items
        self.add_item(select)
        has = self.selected is not None
        for label, action, amount in (("+1", "add", 1), ("+16", "add", 16), ("+64", "add", 64)):
            self.add_item(self._button(label, discord.ButtonStyle.secondary, 1, not has, lambda i, a=action, n=amount: self.act(i, a, amount=n)))
        self.add_item(self._button("Set…", discord.ButtonStyle.primary, 1, not has, self._on_set))
        self.add_item(self._button("Mark complete", discord.ButtonStyle.success, 2, not has, lambda i: self.act(i, "done")))
        self.add_item(self._button("Reset", discord.ButtonStyle.danger, 2, not has, lambda i: self.act(i, "reset")))
        self.add_item(self._button("◀", discord.ButtonStyle.secondary, 3, self.page <= 0, lambda i: self._turn(i, -1)))
        self.add_item(self._button("▶", discord.ButtonStyle.secondary, 3, self.page >= self.pages - 1, lambda i: self._turn(i, 1)))
        self.add_item(self._button("Missing only" if not self.missing_only else "Show all", discord.ButtonStyle.secondary, 3, False, self._toggle_missing))
        self.add_item(self._button("Refresh", discord.ButtonStyle.secondary, 3, False, self._refresh))

    @staticmethod
    def _button(label, style, row, disabled, callback):
        b = discord.ui.Button(label=label, style=style, row=row, disabled=disabled)
        b.callback = callback
        return b

    async def _on_select(self, interaction: discord.Interaction) -> None:
        values = interaction.data.get("values") if interaction.data else None
        self.selected = values[0] if values else None
        self._rebuild()
        await interaction.response.edit_message(embed=self.embed(), view=self)

    async def _on_set(self, interaction: discord.Interaction) -> None:
        if not self.selected:
            await interaction.response.send_message("Pick an item first.", ephemeral=True)
            return
        await interaction.response.send_modal(SetCountModal(self, self.selected))

    async def _turn(self, interaction: discord.Interaction, delta: int) -> None:
        self.page = max(0, self.page + delta)
        await self._refresh(interaction)

    async def _toggle_missing(self, interaction: discord.Interaction) -> None:
        self.missing_only = not self.missing_only
        self.page = 0
        await self._refresh(interaction)

    async def _refresh(self, interaction: discord.Interaction) -> None:
        try:
            await self.fetch()
        except LinkError as exc:
            await interaction.response.send_message(f"Could not refresh: {exc}", ephemeral=True)
            return
        await interaction.response.edit_message(embed=self.embed(), view=self)

    async def act(self, interaction: discord.Interaction, action: str, amount: int = 1, item: str | None = None) -> None:
        item = item or self.selected
        if not item:
            await interaction.response.send_message("Pick an item first.", ephemeral=True)
            return
        player = await self.bot.resolve_player(interaction.user.id)
        if player is None:
            await interaction.response.send_message(
                "Link your Minecraft account first: `/cytra-syncmatica link` in game, then `/link <code>` here.", ephemeral=True)
            return
        try:
            out = await self.bot.ext("material_action", {"schematic_id": self.schematic_id, "item": item, "action": action,
                                                         "amount": amount, "mc_uuid": player[0]})
        except LinkError as exc:
            await interaction.response.send_message(f"The server refused that: {exc}", ephemeral=True)
            return
        try:
            await self.fetch()
        except LinkError:
            pass
        changed = out.get("item") or {}
        note = f"{ui.item_name(item)}: {changed.get('gathered', '?')}/{changed.get('required', '?')} as **{player[1]}**"
        if interaction.response.is_done():
            if self.message is not None:
                await self.message.edit(embed=self.embed(), view=self)
            await interaction.followup.send(note, ephemeral=True)
        else:
            await interaction.response.edit_message(embed=self.embed(), view=self)
            await interaction.followup.send(note, ephemeral=True)

    async def on_timeout(self) -> None:
        for child in self.children:
            child.disabled = True  # type: ignore[attr-defined]
        if self.message is not None:
            try:
                await self.message.edit(view=self)
            except discord.HTTPException:
                pass


class Schematics(commands.Cog):
    schematic = app_commands.Group(name="schematic", description="Shared schematics on the server")

    def __init__(self, bot: SyncmaticaBot):
        self.bot = bot
        self.cfg = bot.cfg

    async def _find(self, name: str) -> dict:
        out = await self.bot.ext("get_schematic", {"schematic": name})
        return out["schematic"]

    @schematic.command(name="list", description="List the schematics shared on the server")
    async def list_(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer()
        schematics = await self.bot.schematics(max_age=0)
        if not schematics:
            await interaction.followup.send("No schematics are shared on the server yet.")
            return
        e = discord.Embed(title=f"Shared schematics on {self.cfg.server.name}", colour=ui.Palette.INFO)
        lines = []
        for s in schematics[:40]:
            m = s.get("materials") or {}
            pct = f"{m['percent']:.0f}%" if m.get("available") else "no list yet"
            lines.append(f"**{s['name']}** · {ui.player_text(s.get('owner'))} · {str(s.get('dimension', '')).replace('minecraft:', '')} · {pct}")
        e.description = "\n".join(lines)
        if len(schematics) > 40:
            e.set_footer(text=f"and {len(schematics) - 40} more")
        await interaction.followup.send(embed=e, allowed_mentions=ui.NO_MENTIONS)

    @schematic.command(name="info", description="Details of one shared schematic")
    @app_commands.describe(name="Schematic name")
    @app_commands.autocomplete(name=schematic_autocomplete)
    async def info(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer()
        s = await self._find(name)
        await interaction.followup.send(embed=ui.schematic_embed(s, self.cfg.server.name), allowed_mentions=ui.NO_MENTIONS)

    @schematic.command(name="materials", description="The shared material list, with buttons to update counts")
    @app_commands.describe(name="Schematic name", missing_only="Show only items that are not complete")
    @app_commands.autocomplete(name=schematic_autocomplete)
    async def materials(self, interaction: discord.Interaction, name: str, missing_only: bool = False) -> None:
        await interaction.response.defer()
        s = await self._find(name)
        view = MaterialsView(self.bot, s["id"], s["name"], missing_only)
        await view.fetch()
        view.message = await interaction.followup.send(embed=view.embed(), view=view, allowed_mentions=ui.NO_MENTIONS, wait=True)

    @schematic.command(name="where", description="Dimension and coordinates of a shared schematic")
    @app_commands.describe(name="Schematic name")
    @app_commands.autocomplete(name=schematic_autocomplete)
    async def where(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer()
        w = await self.bot.ext("get_where", {"schematic": name})
        await interaction.followup.send(embed=ui.where_embed(w, self.cfg.server.name))

    @app_commands.command(name="materials", description="Quick edit: add to an item's gathered count")
    @app_commands.describe(schematic="Schematic name", item="Item id, e.g. minecraft:stone", amount="How many to add (negative to remove)")
    @app_commands.autocomplete(schematic=schematic_autocomplete)
    @require_link()
    async def materials_add(self, interaction: discord.Interaction, schematic: str, item: str, amount: int) -> None:
        await interaction.response.defer(ephemeral=True)
        uuid, mc_name = interaction.extras["player"]
        if ":" not in item:
            item = "minecraft:" + item.strip().lower().replace(" ", "_")
        out = await self.bot.ext("material_action", {"schematic": schematic, "item": item, "action": "add", "amount": amount, "mc_uuid": uuid})
        it = out.get("item") or {}
        await interaction.followup.send(
            f"{ui.item_name(item)} in **{out.get('schematic', schematic)}**: {it.get('gathered')}/{it.get('required')} gathered as **{mc_name}**.",
            ephemeral=True)


async def setup(bot: SyncmaticaBot) -> None:
    await bot.add_cog(Schematics(bot))
