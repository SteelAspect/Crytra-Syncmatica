"""config.yaml -> typed dataclasses, with ${ENV} substitution from the environment / .env."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


class ConfigError(Exception):
    """config.yaml is missing something or holds a bad value."""


def _load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip("'\""))


def _substitute_env(value, where: str = "config"):
    if isinstance(value, str):
        def repl(m: re.Match) -> str:
            env_val = os.environ.get(m.group(1))
            if env_val is None:
                raise ConfigError(f"{where}: ${{{m.group(1)}}} is referenced in config but not set in the environment or .env")
            return env_val
        return _ENV_PATTERN.sub(repl, value)
    if isinstance(value, dict):
        return {k: _substitute_env(v, f"{where}.{k}") for k, v in value.items()}
    if isinstance(value, list):
        return [_substitute_env(v, f"{where}[{i}]") for i, v in enumerate(value)]
    return value


@dataclass
class DiscordConfig:
    token: str
    guild_id: int
    feed_channel_id: int = 0
    staff_role_ids: list[int] = field(default_factory=list)


@dataclass
class ServerConfig:
    name: str = "Minecraft"
    host: str = "127.0.0.1"
    port: int = 25565
    secret: str = ""
    timeout: float = 8.0


@dataclass
class SyncmaticaConfig:
    page_size: int = 15
    feed_edit: bool = True
    feed_preview: bool = True
    announce_item_completed: bool = True
    announce_group_completed: bool = True
    announce_project_completed: bool = True
    announce_project_changes: bool = True
    announce_layer_completed: bool = False
    announce_schematic_completed: bool = True
    announce_schematic_shared: bool = True


@dataclass
class LinkingConfig:
    enabled: bool = True
    shared_db_path: str = ""


@dataclass
class LoggingConfig:
    level: str = "INFO"
    file: str | None = "logs/bot.log"


@dataclass
class Config:
    discord: DiscordConfig
    server: ServerConfig
    syncmatica: SyncmaticaConfig = field(default_factory=SyncmaticaConfig)
    linking: LinkingConfig = field(default_factory=LinkingConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)
    database: str = "syncmatica-bot.sqlite3"
    base_dir: Path = field(default_factory=lambda: Path("."))


def _section(raw: dict, name: str) -> dict:
    sec = raw.get(name)
    if sec is None:
        return {}
    if not isinstance(sec, dict):
        raise ConfigError(f"config section '{name}' must be a mapping")
    return sec


def _resolve(base_dir: Path, p: str | None) -> str | None:
    if not p:
        return None
    path = Path(p)
    return p if path.is_absolute() else str(base_dir / path)


def load_config(path: str | Path) -> Config:
    path = Path(path)
    if not path.exists():
        raise ConfigError(f"Config file not found: {path}. Copy config.example.yaml to config.yaml and fill it in.")
    base_dir = path.resolve().parent
    _load_dotenv(base_dir / ".env")
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(raw, dict):
        raise ConfigError("top level of config must be a mapping")
    raw = _substitute_env(raw)
    try:
        d = _section(raw, "discord")
        if not str(d.get("token") or "").strip():
            raise ConfigError("discord.token is required")
        discord_cfg = DiscordConfig(
            token=str(d["token"]),
            guild_id=int(d.get("guild_id", 0)),
            feed_channel_id=int(d.get("feed_channel_id", 0)),
            staff_role_ids=[int(x) for x in d.get("staff_role_ids", [])],
        )
        s = _section(raw, "server")
        server_cfg = ServerConfig(
            name=str(s.get("name", "Minecraft")),
            host=str(s.get("host", "")).strip(),
            port=int(s.get("port", 25565)),
            secret=str(s.get("secret", "")).strip(),
            timeout=float(s.get("timeout", 8.0)),
        )
        sync_cfg = SyncmaticaConfig(**_section(raw, "syncmatica"))
        link_cfg = LinkingConfig(**_section(raw, "linking"))
        log_cfg = LoggingConfig(**_section(raw, "logging"))
    except ConfigError:
        raise
    except (TypeError, ValueError, KeyError) as exc:
        raise ConfigError(f"Invalid config value: {exc}") from exc
    if not server_cfg.host:
        raise ConfigError("server.host is required (the Minecraft server address)")
    if not server_cfg.secret:
        raise ConfigError("server.secret is required (secret= from config/cytra-link.properties on the server)")
    if not discord_cfg.guild_id:
        raise ConfigError("discord.guild_id is required")
    if sync_cfg.page_size < 1 or sync_cfg.page_size > 25:
        raise ConfigError("syncmatica.page_size must be 1..25 (a Discord select menu holds 25 entries)")
    log_cfg.level = str(log_cfg.level).upper()
    log_cfg.file = _resolve(base_dir, log_cfg.file)
    link_cfg.shared_db_path = _resolve(base_dir, link_cfg.shared_db_path) or ""
    return Config(discord=discord_cfg, server=server_cfg, syncmatica=sync_cfg, linking=link_cfg, logging=log_cfg,
                  database=_resolve(base_dir, str(raw.get("database", "syncmatica-bot.sqlite3"))) or "syncmatica-bot.sqlite3",
                  base_dir=base_dir)
