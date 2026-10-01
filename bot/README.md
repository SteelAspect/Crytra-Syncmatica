# Cytra-Syncmatica bot

A standalone Discord bot for Cytra-Syncmatica's shared schematics and
material lists. It is a brand-new bot with **its own Discord application,
its own code, its own config, its own SQLite file and its own process**; it
shares nothing with any chat or server bot you may already run and can live
on a different machine. The only thing it needs from the Minecraft server is
the [Cytra Link](https://github.com/SteelAspect/cytra-bridge/tree/master/mod)
mod (0.3.0 or newer): the bot **dials the server's game port** through it,
so nothing on the bot's machine listens and the server opens no extra port.
The exact messages are in [`docs/BRIDGE_PROTOCOL.md`](../docs/BRIDGE_PROTOCOL.md).

What it does:

- `/schematic list`, `/schematic info <name>`, `/schematic where <name>` (coordinates hidden when the server says so)
- `/schematic materials <name> [missing_only] [group]`: the shared list as an embed with a select menu to pick an item, a second select to filter by material group (Stone, Wood, Redstone, ...), buttons **+1 / +16 / +64 / Set… / Mark complete / Reset**, paging, and a missing-only toggle. Every press counts as *your* linked Minecraft player, with that player's in-game permissions; a refusal (not permitted, unknown item) is shown only to you.
- `/schematic groups <name>`: progress per material group
- `/schematic layers <name>`: build progress per layer (measured by the server's completion scan)
- `/schematic preview <name>`: the server-rendered top-down picture (map colours); the share post in the feed carries it too (`feed_preview`)
- `/schematic shopping <name> [group] [as_file]`: what is still missing as shulkers + stacks + items, grouped; long lists (or `as_file`) come as a text file
- `/materials <schematic> <item> <amount>`: quick add without the buttons
- `/project list|info|materials|shopping|where <name>`: projects (several schematics tracked as one combined list; the materials view shows the per-schematic split and edits fill the first schematic with something left first)
- `/project create|delete|add|remove`: manage projects as your linked player (the server checks `cytra-syncmatica.project.manage`, op level 2 by default)
- `/link <code>`, `/unlink`, `/whoami`, `/links`: account linking, stored by this bot alone. In game, run `/cytra-syncmatica link` to get a one-time code.
- A feed channel: shared schematics, project changes, item/group/schematic/project completions, and **one "materials" message per schematic that is edited in place** as the team gathers (also after a reconnect, via the mod's `resync`). Message ids are stored in SQLite so restarts keep editing the same messages.
- A clear "Cytra-Syncmatica isn't installed on that server" message when the server has only Cytra Link (or an old one).

## Requirements

- Python 3.11+ (3.12 recommended), `pip install -r requirements.txt`
- The Minecraft server runs Cytra Link **0.3.0+** and Cytra-Syncmatica
- Its own Discord application with a bot token (no privileged intents needed). Create a new application for it; do not reuse another bot's token.

## Setup

```bash
cd bot
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
cp .env.example .env
```

1. Put the bot token in `.env` as `DISCORD_TOKEN`.
2. Copy `secret=` from the server's `config/cytra-link.properties` into `.env` as `CYTRA_LINK_SECRET`.
3. In `config.yaml`: `discord.guild_id`, `discord.feed_channel_id` (optional), `server.host` / `server.port` (the game port unless Cytra Link has its own `port=`), `server.name`.
4. `python scripts/doctor.py` checks the config, the link, that the server lists the `cytra-syncmatica` extension, and the Discord IDs.
5. `python run.py` (or `python -m cytra_syncmatica_bot`). Add `--sync` after changing the command set.

Players link once with this bot (`/cytra-syncmatica link` in game, `/link <code>`
here); links are kept in this bot's own database and nowhere else. If other
bots use Cytra Link on the same server they are unaffected: Cytra Link accepts
up to four connections and each gets every event.

### Running as a service

`cytra-syncmatica-bot.service.example` is the same unit as below; it runs this
bot on its own, with its own working directory and virtualenv.

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
| `server.secret` | | Cytra Link's `secret=` (use `${CYTRA_LINK_SECRET}`) |
| `server.timeout` | `8.0` | seconds to wait for a reply |
| `syncmatica.page_size` | `15` | items per page (max 25) |
| `syncmatica.feed_edit` | `true` | edit one materials message per schematic in place |
| `syncmatica.feed_preview` | `true` | attach the top-down preview to "schematic shared" posts |
| `syncmatica.announce_item_completed` / `announce_group_completed` / `announce_schematic_completed` / `announce_project_completed` / `announce_project_changes` / `announce_schematic_shared` | `true` | feed posts |
| `syncmatica.announce_layer_completed` | `false` | one feed post per finished build layer (noisy on tall builds) |
| `linking.enabled` | `true` | register `/link`, `/unlink`, `/whoami`, `/links` |
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
