"""Entry point: python -m cytra_syncmatica_bot [--config config.yaml] [--sync]"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import sys

from cytra_syncmatica_bot.config import ConfigError, load_config
from cytra_syncmatica_bot.core import SyncmaticaBot
from cytra_syncmatica_bot.db import Database
from cytra_syncmatica_bot.logging_setup import setup_logging
from cytra_syncmatica_bot.modlink import ModLink

log = logging.getLogger("bot")


async def run(config_path: str, force_sync: bool) -> int:
    try:
        cfg = load_config(config_path)
    except ConfigError as exc:
        print(f"config error: {exc}", file=sys.stderr)
        return 2
    setup_logging(cfg.logging.level, cfg.logging.file)
    db = Database(cfg.database)
    await db.connect()
    link = ModLink(cfg.server.host, cfg.server.port, cfg.server.secret, timeout=cfg.server.timeout)
    bot = SyncmaticaBot(cfg, db, link)
    bot.force_sync = force_sync
    loop = asyncio.get_running_loop()
    if os.name != "nt":
        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                loop.add_signal_handler(sig, lambda: asyncio.ensure_future(bot.close()))
            except (NotImplementedError, RuntimeError):
                pass
    try:
        await bot.start(cfg.discord.token)
    finally:
        await db.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Cytra-Syncmatica Discord bot")
    ap.add_argument("--config", default=os.environ.get("CYTRA_SYNCMATICA_CONFIG", "config.yaml"))
    ap.add_argument("--sync", action="store_true", help="force a slash-command sync")
    args = ap.parse_args(argv)
    try:
        return asyncio.run(run(args.config, args.sync))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
