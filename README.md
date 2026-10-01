# Cytra-Syncmatica

Shared [Litematica](https://modrinth.com/mod/litematica) schematics for a team,
with the team's own material tracking, build management, layer progress,
previews and an optional Discord bridge that works on panel hosts.
Minecraft Java **1.21.11**, Fabric, one jar for the client, the dedicated
server and singleplayer.

Cytra-Syncmatica is a fork of
[Syncmatica Revolution (syncmatica_r)](https://github.com/Conflux-Union/syncmatica_r)
by the Conflux-Union authors, released under CC0-1.0. Thanks to them: the
sharing, sync, permissions and build-management code is theirs. This fork is
also CC0-1.0 (see [LICENSE](LICENSE)). It is **not** network-compatible with
syncmatica_r or the original syncmatica; it refuses to load next to either and
warns in the log. What was removed, kept, renamed and added is listed in
[CHANGELOG.md](CHANGELOG.md).

## What it does

- **Share schematics** on a server: upload a Litematica placement, everyone
  with the mod sees it at the same place, with locking while someone moves it.
- **Team material tracking**: the server reads the material list out of the
  shared schematic; every item has one shared "gathered" count that the whole
  team edits (+1/+16/+64/set/done/reset), with "last edited by". Remaining
  amounts are shown as shulker boxes + stacks + items. Groups (Stone, Wood,
  Redstone, ...), a shopping list, CSV/txt export.
- **Works without a server too**: on a server without the mod, or in
  singleplayer, the same screens use Litematica's own material list and keep
  your counts in a local file. Zero network traffic in that mode.
- **Projects**: several schematics tracked as one combined list.
- **Build management** (from upstream): claim sub-regions, incremental build
  progress, now also **per layer**, with a layer screen that drives
  Litematica's render layer and a HUD line.
- **Previews**: the server renders a top-down map-colour picture of every
  shared schematic.
- **Discord bridge** through [Cytra Link](https://github.com/steelaspect/cytra-bridge)
  0.3.0 and the standalone bot in [`bot/`](bot/): browse schematics and
  materials, tick items off from Discord as your linked Minecraft player,
  live feed of progress. The mod itself opens **no ports and no connections**
  and holds **no Discord secrets**; the bot dials the server's game port
  through Cytra Link.

## Requirements

| Side | Needs |
|---|---|
| Client | Fabric Loader 0.16+, Fabric API, **Litematica** and **MaLiLib** for 1.21.11, Java 21 |
| Dedicated server | Fabric Loader 0.16+, Fabric API, Java 21. Litematica/MaLiLib are **not** needed |
| Singleplayer | same as client |
| Discord bridge (optional) | Cytra Link ≥ 0.3.0 in the server's `mods/`, the bot from `bot/` somewhere with internet |

fabric-permissions-api is bundled; LuckPerms or any other permissions mod
works, vanilla op levels are the fallback.

## Install

**Client:** drop `cytra-syncmatica-<version>+1.21.11.jar`, Litematica and
MaLiLib into `.minecraft/mods/`. Hotkeys and settings are in MaLiLib's config
menu (default key `A+C`) under *Cytra-Syncmatica*, and in
`config/cytra-syncmatica/client.json`.

**Dedicated server:** put the same jar in `mods/` next to Fabric API and
restart. The config appears at `config/cytra-syncmatica/config.json`; data
that belongs to the world (material counts, projects, build scans, previews)
lives in `<world>/cytra-syncmatica/`, so a world backup includes it. Shared
schematic files are in `./syncmatics/`.

**Panel host (Kinetic and similar):** everything is a file inside the server
folder, nothing listens on an extra port, nothing needs root or a shell.

1. *Files → mods → Upload* the Cytra-Syncmatica jar (and Fabric API if it is
   not there). For the Discord bridge also upload `cytra-link-0.3.0.jar`.
2. Restart. Check the console for
   `Cytra-Syncmatica ... on a dedicated server; Discord bridge: available through Cytra Link`
   (or `... not installed` without Cytra Link; both are fine).
3. Edit `config/cytra-syncmatica/config.json` in the file manager if you want
   to change anything (see below), or use `/cytra-syncmatica config set ...`
   from the console. Changes made with the command are saved.
4. For the bridge, copy `secret=` from `config/cytra-link.properties` into the
   bot's `.env` (see [`bot/README.md`](bot/README.md)). Up to 4 bots can be
   connected at once through Cytra Link.

**Singleplayer:** just the client install. The integrated server runs the
server side; counts and projects are stored under `config/cytra-syncmatica/client/<world>/`.

## In game

Open the material screen with the `openMaterialTracker` hotkey, the
**Materials** button in Litematica's main menu, or **Team tracker** in
Litematica's material list. The status line says which mode you are in:

- **Connected**: the server has the mod and material tracking on. Lists and
  counts are shared with everyone online; edits are sent to the server.
- **Client-only**: the server does not have the mod. Lists come from
  Litematica, counts stay in `config/cytra-syncmatica/client/<server>/<schematic>.json`.
- **Singleplayer**: local, like client-only.

Buttons: *Schematic…* (also lists projects), *Projects*, *Sort*, *Group*,
*Hide completed*, *Auto-count* (counts your inventory and the container you
have open), *Add from inventory*, *Shopping list*, *Layers*, *Export*,
*Refresh*. Each row: icon, required, gathered, in inventory, remaining
(SB + stacks + items), last edited by, and +1/+16/+64/Set/Done/Reset/Pin.
Pinned items appear on the HUD (`toggleMaterialHud`); its position, scale,
row count and the layer line are in `client.json`.

**Layers**: click a layer to show only it in Litematica, *Follow player*
keeps Litematica's single layer at your feet, *Next incomplete* jumps to the
lowest unfinished layer. The client measures this itself by comparing
Litematica's schematic world with the real world, so it works in every mode
(unloaded chunks count as unknown).

Hotkeys (unbound by default): `openMaterialTracker`, `toggleMaterialHud`,
`openLayerProgress`, `openBuildManagement`.

## Commands (server / singleplayer)

| Command | Does | Permission |
|---|---|---|
| `/cytra-syncmatica load` | load `.litematic` files dropped into `syncmatics/` | `cytra-syncmatica.command.load` (op 2) |
| `/cytra-syncmatica config list\|get\|set\|reset` | live config | `cytra-syncmatica.config` (op 2) |
| `/cytra-syncmatica export <schematic or project>` | CSV + txt into `config/cytra-syncmatica/exports/` | everyone |
| `/cytra-syncmatica shopping <schematic or project> [group:<name>]` | what is missing, grouped, click-to-copy | everyone |
| `/cytra-syncmatica where <schematic or project>` | dimension, origin, centre (click to copy), distance | everyone; coordinates need `cytra-syncmatica.where` when `sharing.hide_coordinates_without_permission` is on |
| `/cytra-syncmatica preview <schematic>` | writes the top-down PNG into `exports/` | everyone |
| `/cytra-syncmatica project list\|info <name>` | projects | everyone |
| `/cytra-syncmatica project create <name>` / `delete <name>` / `add "<project>" <schematic>` / `remove "<project>" <schematic>` | manage projects | `cytra-syncmatica.project.manage` (op 2) |
| `/cytra-syncmatica link` | one-time code to link your Discord account through the bot | everyone (needs Cytra Link) |
| `/cytra-syncmatica rescan <schematic>` | throw away build counts and measure again | `cytra-syncmatica.command` (op 2) |

Schematic names tab-complete. Material edits in game need
`cytra-syncmatica.materials.edit` (allowed by default); *Reset* needs
`cytra-syncmatica.materials.reset` (op 2 by default). Sharing, build claims
and placement management keep upstream's nodes under the `cytra-syncmatica.`
prefix (`share`, `build.claim`, `manage`, `command`).

## Server config reference (`config/cytra-syncmatica/config.json`)

| Section | Key | Default | Meaning |
|---|---|---|---|
| `quota` | `enabled` | `false` | per-player upload quota |
| | `limit` | `40000000` | bytes per player |
| `sharing` | `max_schematic_megabytes` | `64` | largest schematic accepted (1–64) |
| | `hide_coordinates_without_permission` | `false` | `where` shows only the dimension to players without `cytra-syncmatica.where` |
| `materials` | `enabled` | `true` | team material tracking (also gates projects, shopping, groups) |
| | `max_schematic_blocks` | `8000000` | schematics above this get no material list (1M–64M) |
| `preview` | `enabled` | `true` | render top-down previews |
| | `max_pixels` | `1048576` | image size budget (65536–16777216) |
| `bridge` | `enabled` | `true` | answer Discord requests and send events (needs Cytra Link) |
| | `batch_seconds` | `5` | batching window for item changes (0 = immediate) |
| | `queue_limit` | `500` | events kept while no bot is connected |
| | `hide_coordinates` | `false` | never send coordinates to Discord |
| `build` | `enabled` | `true` | build management |
| | `completion_enabled` | `true` | build progress and layer scanning |
| | `scan_blocks_per_tick` | `4096` | scan budget |
| | `scan_interval` | `1200` | ticks between scans of one placement |
| | `full_rescan_interval` | `36000` | ticks between full passes |
| `debug` | `doPackageLogging` | `false` | packet logging |

`config/cytra-syncmatica/groups.json` maps item ids to group names
(`{"overrides": {"minecraft:stone": "Walls"}}`); the client reads the same
file for local lists. Secrets: none. The only secret of the whole setup is
Cytra Link's, in `config/cytra-link.properties`, and clients never receive it.

## Client config (`config/cytra-syncmatica/client.json`)

`hudEnabled`, `hudScale`, `hudX`, `hudY`, `hudMaxRows`, `hudLayerLine`,
`layerScanPerTick`, `autoCountOpenContainers`, `followClaims`,
`warnOnForeignPlacement`, plus the hotkeys. Tracker preferences (sort, hide
completed, auto-count, pinned items) are in `client/tracker.json`.

## Discord bridge and bot

The server side is an extension of Cytra Link (`"cytra-link"` entrypoint,
namespace `cytra-syncmatica`); the wire protocol with every message is in
[docs/BRIDGE_PROTOCOL.md](docs/BRIDGE_PROTOCOL.md). The bot is a brand-new,
standalone bot in [`bot/`](bot/): its own Discord application, code, config,
database and process, unrelated to any chat bots you run, and it can live on
a different machine. Its README covers: slash commands for schematics,
materials (buttons, group filter, per-item edits as your linked player, with
the player's in-game permissions), projects, shopping lists, layers and
previews, account linking (`/cytra-syncmatica link` in game, `/link <code>`
in Discord), and a feed channel that keeps one live materials message per
schematic. A server with only Cytra Link (or an older one) gets a clear
"Cytra-Syncmatica isn't installed" answer.

## Building

```
./gradlew build
```

produces `build/libs/cytra-syncmatica-<version>+1.21.11.jar` (Java 21). The
bridge compiles against `libs/cytra-link-0.3.0.jar` (compile-only, see
`libs/README.md`). Unit tests: `./gradlew test`. Bot tests: `cd bot && pytest`.

## Verification

[docs/TEST_CHECKLIST.md](docs/TEST_CHECKLIST.md) lists what was tested, how,
and what still needs a game client.
