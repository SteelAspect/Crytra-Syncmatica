"""Slash commands for projects: named sets of shared schematics with one
combined material list. Viewing is open to everyone; create/delete/add/remove
run as the caller's linked Minecraft player and the mod checks
cytra-syncmatica.project.manage (op level 2 by default)."""

from __future__ import annotations

import logging

import discord
from discord import app_commands
from discord.ext import commands

from cytra_syncmatica_bot import ui
from cytra_syncmatica_bot.cogs.schematics import MaterialsView, schematic_autocomplete, shopping_embed_and_file
from cytra_syncmatica_bot.core import SyncmaticaBot, require_link

log = logging.getLogger(__name__)


async def project_autocomplete(interaction: discord.Interaction, current: str) -> list[app_commands.Choice[str]]:
    bot: SyncmaticaBot = interaction.client  # type: ignore[assignment]
    try:
        names = [p["name"] for p in (await bot.ext("list_projects")).get("projects") or []]
    except Exception:
        return []
    cur = current.lower()
    return [app_commands.Choice(name=n[:100], value=n[:100]) for n in names if cur in n.lower()][:25]


class Projects(commands.Cog):
    project = app_commands.Group(name="project", description="Projects: several schematics tracked as one")

    def __init__(self, bot: SyncmaticaBot):
        self.bot = bot
        self.cfg = bot.cfg

    async def _find(self, name: str) -> dict:
        return (await self.bot.ext("get_project", {"project": name}))["project"]

    @project.command(name="list", description="List the projects on the server")
    async def list_(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer()
        projects = (await self.bot.ext("list_projects")).get("projects") or []
        if not projects:
            await interaction.followup.send("No projects yet. Create one with `/project create`.")
            return
        e = discord.Embed(title=f"Projects on {self.cfg.server.name}", colour=ui.Palette.INFO)
        e.description = "\n".join(ui.project_line(p) for p in projects[:40])
        await interaction.followup.send(embed=e, allowed_mentions=ui.NO_MENTIONS)

    @project.command(name="info", description="Members and combined progress of a project")
    @app_commands.describe(name="Project name")
    @app_commands.autocomplete(name=project_autocomplete)
    async def info(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer()
        out = await self.bot.ext("get_project", {"project": name})
        await interaction.followup.send(embed=ui.project_embed(out["project"], self.cfg.server.name, out.get("top_remaining") or []),
                                        allowed_mentions=ui.NO_MENTIONS)

    @project.command(name="materials", description="The combined material list of a project, with edit buttons")
    @app_commands.describe(name="Project name", missing_only="Only items that are not complete", group="Only one material group")
    @app_commands.autocomplete(name=project_autocomplete)
    async def materials(self, interaction: discord.Interaction, name: str, missing_only: bool = False, group: str | None = None) -> None:
        await interaction.response.defer()
        p = await self._find(name)
        view = MaterialsView(self.bot, p["id"], p["name"], missing_only, group=group, target_key="project_id")
        await view.fetch()
        view.message = await interaction.followup.send(embed=view.embed(), view=view, allowed_mentions=ui.NO_MENTIONS, wait=True)

    @project.command(name="shopping", description="What is still missing for the whole project")
    @app_commands.describe(name="Project name", group="Only one material group", as_file="Always attach the list as a text file")
    @app_commands.autocomplete(name=project_autocomplete)
    async def shopping(self, interaction: discord.Interaction, name: str, group: str | None = None, as_file: bool = False) -> None:
        await interaction.response.defer()
        req = {"project": name}
        if group:
            req["group"] = group
        out = await self.bot.ext("get_shopping_list", req)
        e, f = shopping_embed_and_file(self.cfg.server.name, out, name, as_file)
        if f is not None:
            await interaction.followup.send(embed=e, file=f)
        else:
            await interaction.followup.send(embed=e)

    @project.command(name="where", description="Where each schematic of a project is")
    @app_commands.describe(name="Project name")
    @app_commands.autocomplete(name=project_autocomplete)
    async def where(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer()
        out = await self.bot.ext("get_where", {"project": name})
        e = discord.Embed(title=f"Where is project {out.get('project', name)}?", colour=ui.Palette.INFO)
        e.set_author(name=self.cfg.server.name)
        for w in out.get("schematics") or []:
            if w.get("coordinates_hidden"):
                value = f"{str(w.get('dimension', '?')).replace('minecraft:', '')} · coordinates hidden"
            else:
                o = w.get("origin") or {}
                value = f"{str(w.get('dimension', '?')).replace('minecraft:', '')} · origin `{o.get('x')} {o.get('y')} {o.get('z')}`"
            e.add_field(name=w.get("schematic", "?"), value=value, inline=False)
        if not e.fields:
            e.description = "This project has no schematics yet."
        await interaction.followup.send(embed=e)

    # -- management (the mod checks the caller's permission) ------------------------------

    async def _action(self, interaction: discord.Interaction, payload: dict) -> None:
        uuid, mc_name = interaction.extras["player"]
        payload["mc_uuid"] = uuid
        out = await self.bot.ext("project_action", payload)
        p = out.get("project") or {}
        verb = {"create": "created", "delete": "deleted", "add": "updated", "remove": "updated", "rename": "renamed"}[payload["action"]]
        note = "" if out.get("changed", True) else " (nothing changed)"
        await interaction.followup.send(f"Project **{p.get('name', '?')}** {verb} as **{mc_name}**{note}.", ephemeral=True)

    @project.command(name="create", description="Create a project (needs cytra-syncmatica.project.manage in game)")
    @app_commands.describe(name="Name of the new project")
    @require_link()
    async def create(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer(ephemeral=True)
        await self._action(interaction, {"action": "create", "name": name})

    @project.command(name="delete", description="Delete a project (its schematics stay shared)")
    @app_commands.describe(name="Project name")
    @app_commands.autocomplete(name=project_autocomplete)
    @require_link()
    async def delete(self, interaction: discord.Interaction, name: str) -> None:
        await interaction.response.defer(ephemeral=True)
        await self._action(interaction, {"action": "delete", "project": name})

    @project.command(name="add", description="Add a shared schematic to a project")
    @app_commands.describe(name="Project name", schematic="Schematic name")
    @app_commands.autocomplete(name=project_autocomplete, schematic=schematic_autocomplete)
    @require_link()
    async def add(self, interaction: discord.Interaction, name: str, schematic: str) -> None:
        await interaction.response.defer(ephemeral=True)
        await self._action(interaction, {"action": "add", "project": name, "schematic": schematic})

    @project.command(name="remove", description="Remove a schematic from a project")
    @app_commands.describe(name="Project name", schematic="Schematic name")
    @app_commands.autocomplete(name=project_autocomplete, schematic=schematic_autocomplete)
    @require_link()
    async def remove(self, interaction: discord.Interaction, name: str, schematic: str) -> None:
        await interaction.response.defer(ephemeral=True)
        await self._action(interaction, {"action": "remove", "project": name, "schematic": schematic})


async def setup(bot: SyncmaticaBot) -> None:
    await bot.add_cog(Projects(bot))
