"""Embeds and text helpers shared by the commands and the feed."""

from __future__ import annotations

import discord

NO_MENTIONS = discord.AllowedMentions.none()


class Palette:
    INFO = 0x5865F2
    OK = 0x57F287
    WARN = 0xFEE75C
    DOWN = 0xED4245
    GOLD = 0xF1C40F


def progress_bar(percent: float, width: int = 12) -> str:
    filled = max(0, min(width, round(width * percent / 100.0)))
    return "█" * filled + "░" * (width - filled)


def item_name(item_id: str) -> str:
    return item_id.split(":", 1)[-1].replace("_", " ")


def player_text(p: dict | None) -> str:
    return p["name"] if isinstance(p, dict) and p.get("name") else "nobody yet"


def rel(ms: int | None) -> str:
    return f"<t:{int(ms // 1000)}:R>" if ms else ""


def schematic_embed(s: dict, server_name: str) -> discord.Embed:
    m = s.get("materials") or {}
    e = discord.Embed(title=s.get("name", "?"), colour=Palette.INFO)
    e.set_author(name=f"{server_name} · shared schematic")
    e.add_field(name="Shared by", value=player_text(s.get("owner")), inline=True)
    e.add_field(name="Dimension", value=str(s.get("dimension", "?")).replace("minecraft:", ""), inline=True)
    size = s.get("size")
    if size:
        e.add_field(name="Size", value=f"{size['x']}×{size['y']}×{size['z']}", inline=True)
    if "block_count" in s:
        e.add_field(name="Blocks", value=f"{s['block_count']:,} ({s.get('unique_blocks', '?')} unique)", inline=True)
    if s.get("coordinates_hidden"):
        e.add_field(name="Where", value="coordinates hidden by the server", inline=False)
    elif s.get("origin"):
        o = s["origin"]
        where = f"origin `{o['x']} {o['y']} {o['z']}`"
        if s.get("centre"):
            c = s["centre"]
            where += f" · centre `{c['x']} {c['y']} {c['z']}`"
        e.add_field(name="Where", value=where, inline=False)
    if m.get("available"):
        e.add_field(name="Materials", value=f"{progress_bar(m['percent'])} {m['percent']:.1f}% · {m['remaining']:,} of {m['required']:,} items still needed", inline=False)
    else:
        e.add_field(name="Materials", value="list not built yet", inline=False)
    e.set_footer(text=f"id {s.get('id', '?')[:8]}")
    return e


def materials_embed(target_name: str, server_name: str, summary: dict, items: list[dict], page: int, pages: int,
                    missing_only: bool, top_remaining: list[dict] | None = None, group: str | None = None) -> discord.Embed:
    e = discord.Embed(title=f"Materials: {target_name}", colour=Palette.OK if summary.get("complete") else Palette.GOLD)
    e.set_author(name=server_name)
    if summary.get("available"):
        e.description = (f"{progress_bar(summary['percent'])} **{summary['percent']:.1f}%** · "
                         f"{summary['gathered']:,} / {summary['required']:,} gathered · {summary['remaining']:,} remaining")
    else:
        e.description = "The material list has not been built yet."
    lines = []
    for it in items:
        mark = "✅" if it.get("complete") else "▫️"
        remaining = "done" if it.get("complete") else f"{it['remaining_text']} left"
        editor = it.get("editor")
        who = f" · {editor['name']}" if editor else ""
        lines.append(f"{mark} **{item_name(it['item'])}** {it['gathered']:,}/{it['required']:,} · {remaining}{who}")
    title = "Missing items" if missing_only else "Items"
    if group:
        title += f" · {group}"
    e.add_field(name=title + f" (page {page}/{max(pages, 1)})",
                value="\n".join(lines) if lines else "nothing to show", inline=False)
    if top_remaining:
        e.add_field(name="Most needed", value=", ".join(f"{item_name(t['item'])} ({t['remaining_text']})" for t in top_remaining[:5]), inline=False)
    return e


def groups_embed(target_name: str, server_name: str, summary: dict, groups: list[dict]) -> discord.Embed:
    e = discord.Embed(title=f"Material groups: {target_name}", colour=Palette.OK if summary.get("complete") else Palette.INFO)
    e.set_author(name=server_name)
    if summary.get("available"):
        e.description = f"{progress_bar(summary['percent'])} **{summary['percent']:.1f}%** overall · {len(groups)} groups"
    lines = []
    for g in groups:
        mark = "✅" if g.get("complete") else "▫️"
        lines.append(f"{mark} **{g['name']}** {progress_bar(g.get('percent', 0.0), 8)} {g.get('percent', 0.0):.0f}% · "
                     f"{g['gathered']:,}/{g['required']:,} · {g['items']} item{'s' if g['items'] != 1 else ''}")
    e.add_field(name="Groups", value="\n".join(lines) if lines else "no materials yet", inline=False)
    e.set_footer(text="Groups come from the server's groups.json overrides and a built-in mapping")
    return e


def where_embed(w: dict, server_name: str) -> discord.Embed:
    e = discord.Embed(title=f"Where is {w.get('schematic', '?')}?", colour=Palette.INFO)
    e.set_author(name=server_name)
    e.add_field(name="Dimension", value=str(w.get("dimension", "?")).replace("minecraft:", ""), inline=True)
    if w.get("coordinates_hidden"):
        e.add_field(name="Coordinates", value="hidden by the server", inline=False)
    else:
        o = w.get("origin") or {}
        e.add_field(name="Origin", value=f"`{o.get('x')} {o.get('y')} {o.get('z')}`", inline=True)
        c = w.get("centre")
        if c:
            e.add_field(name="Centre", value=f"`{c.get('x')} {c.get('y')} {c.get('z')}`", inline=True)
        s = w.get("size")
        if s:
            e.add_field(name="Size", value=f"{s['x']}×{s['y']}×{s['z']}", inline=True)
    return e
