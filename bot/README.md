# Cytra-Syncmatica bot

A small Discord bot for Cytra-Syncmatica's shared material lists. It is its own
Discord application, separate from cytra-bridge's bots, and it talks to the
Minecraft server the same way they do: it **dials the server** through the
[Cytra Link](https://github.com/SteelAspect/cytra-bridge/tree/master/mod) mod
(0.3.0 or newer) on the game port. Nothing on the bot's machine listens; the
server opens no extra port. The exact messages are in
[`docs/BRIDGE_PROTOCOL.md`](../docs/BRIDGE_PROTOCOL.md).

What it does:

- `/schematic list`, `/schematic info <name>`, `/schematic where <name>` (coordinates hidden when the server says so)
- `/schematic materials <name> [missing_only]`: the shared list as an embed with a select menu to pick an item and buttons **+1 / +16 / +64 / Set… / Mark complete / Reset**, paging, and a missing-only toggle. Every press counts as *your* linked Minecraft player, with that player's in-game permissions; a refusal (not permitted, unknown item) is shown only to you.
- `/materials <schematic> <item> <amount>`: quick add without the buttons
- `/link <code>`, `/unlink`, `/whoami`, `/links`: account linking. In game, run `/cytra-syncmatica link` to get a one-time code.
- A feed channel: shared schematics, item/schematic completions, and **one "materials" message per schematic that is edited in place** as the team gathers (also after a reconnect, via the mod's `resync`). Message ids are stored in SQLite so restarts keep editing the same messages.
- A clear "Cytra-Syncmatica isn't installed on that server" message when the server has only Cytra Link (or an old one).

## Requirements

- Python 3.11+ (3.12 recommended), `pip install -r requirements.txt`
- The Minecraft server runs Cytra Link **0.3.0+** and Cytra-Syncmatica
- A Discord application with a bot token (no privileged intents needed)

## Setup

```bash
cd bot
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
cp .env.example .env
```

1. Put the bot token in `.env` as `DISCORD_TOKEN`.
2. Copy `secret=` from the server's `config/cytra-link.properties` into `.env` as `MOD_LINK_SECRET`.
3. In `config.yaml`: `discord.guild_id`, `discord.feed_channel_id` (optional), `server.host` / `server.port` (the game port unless Cytra Link has its own `port=`), `server.name`.
4. Optional: if a cytra-bridge bot already links players on this Discord, point `linking.shared_db_path` at its SQLite file (read-only fallback), so people need not `/link` twice.
5. `python scripts/doctor.py` checks the config, the link, that the server lists the `cytra-syncmatica` extension, and the Discord IDs.
6. `python run.py` (or `python -m cytra_syncmatica_bot`). Add `--sync` after changing the command set.

Two bots on one server (for example cytra-bridge's bot and this one) are fine:
Cytra Link accepts up to four connections, each gets every event.

### Running as a service

```ini
[Unit]
Description=Cytra-Syncmatica Discord bot
After=network-online.target

[Service]
WorkingDirectory=/opt/cytra-syncmatica-bot
ExecStart=/opt/cytra-syncmatica-bot/.venv/bin/python run.py
Restart=on-failure
User=bot

[Install]
WantedBy=multi-user.target
```

## Config reference (`config.yaml`)

| key | default | meaning |
|---|---|---|
| `discord.token` | | bot token (use `${DISCORD_TOKEN}`) |
| `discord.guild_id` | | the server the slash commands live in |
| `discord.feed_channel_id` | `0` | feed channel; `0` disables the feed |
| `discord.staff_role_ids` | `[]` | may `/unlink` others and use `/links` |
| `server.name` | `Minecraft` | shown in embeds |
| `server.host`, `server.port` | | the Minecraft server address and game port |
| `server.secret` | | Cytra Link's `secret=` (use `${MOD_LINK_SECRET}`) |
| `server.timeout` | `8.0` | seconds to wait for a reply |
| `syncmatica.page_size` | `15` | items per page (max 25) |
| `syncmatica.feed_edit` | `true` | edit one materials message per schematic in place |
| `syncmatica.announce_item_completed` / `announce_schematic_completed` / `announce_schematic_shared` | `true` | feed posts |
| `linking.enabled` | `true` | register `/link`, `/unlink`, `/whoami`, `/links` |
| `linking.shared_db_path` | `""` | read-only fallback to a cytra-bridge database |
| `database` | `syncmatica-bot.sqlite3` | this bot's SQLite file |
| `logging.level`, `logging.file` | `INFO`, `logs/bot.log` | logging |

## Tests

```bash
pip install pytest pytest-asyncio
python -m pytest -q
```

`tests/fake_mod_server.py` is an in-process stand-in for Cytra Link that speaks
the real wire format including `ext`/`ev`; `tests/helpers.py` adds a fake
`cytra-syncmatica` extension with a schematic and permission rules.
