#!/usr/bin/env python3
"""Pre-flight check for the Cytra-Syncmatica bot.

    python scripts/doctor.py                 # ./config.yaml
    python scripts/doctor.py --config x.yaml
    python scripts/doctor.py --offline       # config only, no connections

Read-only. Connects to the server through Cytra Link once, checks that the
cytra-syncmatica extension is listed and answers `ping`, then (unless
--offline) logs into Discord to verify the guild and feed channel."""

from __future__ import annotations

import argparse
import asyncio
import os
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from cytra_syncmatica_bot.config import ConfigError, load_config  # noqa: E402
from cytra_syncmatica_bot.modlink import EXTENSION, LinkAuthError, LinkError, ModLink  # noqa: E402

OK, WARN, FAIL, SKIP = "[ ok ]", "[warn]", "[FAIL]", "[skip]"


class Report:
    def __init__(self):
        self.rows = []

    def add(self, mark, what, detail=""):
        self.rows.append(mark)
        print(f"{mark} {what}" + (f"\n         {detail}" if detail else ""))

    def summarise(self) -> int:
        fails = self.rows.count(FAIL)
        print(f"\n{self.rows.count(OK)} ok · {self.rows.count(WARN)} warning(s) · {fails} failure(s) · {self.rows.count(SKIP)} skipped")
        return 1 if fails else 0


async def check_link(cfg, r: Report) -> None:
    link = ModLink(cfg.server.host, cfg.server.port, cfg.server.secret, timeout=cfg.server.timeout)
    try:
        await link.connect_once()
    except LinkAuthError as exc:
        r.add(FAIL, "Cytra Link login", str(exc)); return
    except (OSError, asyncio.TimeoutError, ConnectionError) as exc:
        r.add(FAIL, "Cytra Link reachable", f"{cfg.server.host}:{cfg.server.port}: {exc or 'timed out'}"); return
    try:
        r.add(OK, "Cytra Link login", f"{cfg.server.host}:{cfg.server.port} · Cytra Link {link.mod_version} · Minecraft {link.mc_version}")
        if link.has_extension(EXTENSION):
            r.add(OK, f"server lists the {EXTENSION} extension")
            try:
                out = await link.ext("ping")
                r.add(OK, "cytra-syncmatica answers ping",
                      f"mod {out.get('mod')} · protocol {out.get('protocol')} · {out.get('schematics')} schematic(s) · "
                      f"materials {'on' if out.get('materials_enabled') else 'OFF'} · coordinates {'hidden' if out.get('coordinates_hidden') else 'shown'}")
            except LinkError as exc:
                r.add(FAIL, "cytra-syncmatica answers ping", str(exc))
        else:
            r.add(FAIL, f"server lists the {EXTENSION} extension",
                  f"welcome.ext = {link.extensions}: install Cytra-Syncmatica on the server, and Cytra Link 0.3.0 or newer "
                  f"(this one is {link.mod_version})")
    finally:
        await link.stop()


async def check_discord(cfg, r: Report) -> None:
    import discord

    client = discord.Client(intents=discord.Intents(guilds=True))

    async def run() -> None:
        guild = client.get_guild(cfg.discord.guild_id)
        if guild is None:
            r.add(FAIL, "bot is in discord.guild_id", f"not in guild {cfg.discord.guild_id}: invite it, or fix the ID"); return
        r.add(OK, "bot is in the guild", guild.name)
        if cfg.discord.feed_channel_id:
            ch = guild.get_channel(cfg.discord.feed_channel_id)
            me = guild.me
            if ch is None:
                r.add(FAIL, "discord.feed_channel_id exists", f"no channel {cfg.discord.feed_channel_id}")
            elif me is not None and not ch.permissions_for(me).send_messages:
                r.add(FAIL, "feed channel writable", f"the bot cannot post in #{ch.name}")
            else:
                r.add(OK, "feed channel", f"#{ch.name}")
        else:
            r.add(WARN, "no feed channel", "set discord.feed_channel_id to get shared/progress posts")
        for rid in cfg.discord.staff_role_ids:
            if guild.get_role(rid) is None:
                r.add(FAIL, "discord.staff_role_ids exist", f"no role {rid}")

    @client.event
    async def on_ready() -> None:
        try:
            await run()
        except Exception as exc:  # noqa: BLE001
            r.add(FAIL, "Discord checks", f"{type(exc).__name__}: {exc}")
        finally:
            await client.close()

    try:
        await asyncio.wait_for(client.start(cfg.discord.token), timeout=60)
    except discord.LoginFailure:
        r.add(FAIL, "Discord token accepted", "reset it in the Developer Portal and update .env")
    except asyncio.TimeoutError:
        r.add(FAIL, "Discord reachable", "no READY within 60 s")
    finally:
        if not client.is_closed():
            await client.close()


async def main_async(args) -> int:
    r = Report()
    try:
        cfg = load_config(args.config)
    except ConfigError as exc:
        r.add(FAIL, f"{args.config} loads", str(exc)); return r.summarise()
    r.add(OK, f"{args.config} loads")
    if cfg.linking.shared_db_path:
        p = pathlib.Path(cfg.linking.shared_db_path)
        r.add(OK if p.is_file() else WARN, "linking.shared_db_path", str(p) + ("" if p.is_file() else " (missing: fallback links unavailable)"))
    if args.offline:
        r.add(SKIP, "Cytra Link", "--offline"); r.add(SKIP, "Discord", "--offline")
    else:
        await check_link(cfg, r)
        if not args.skip_discord:
            await check_discord(cfg, r)
    return r.summarise()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Cytra-Syncmatica bot doctor")
    ap.add_argument("--config", default=os.environ.get("CYTRA_SYNCMATICA_CONFIG", "config.yaml"))
    ap.add_argument("--offline", action="store_true")
    ap.add_argument("--skip-discord", action="store_true", help="check the server link only")
    return asyncio.run(main_async(ap.parse_args(argv)))


if __name__ == "__main__":
    sys.exit(main())
