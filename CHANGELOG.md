# Changelog

All notable changes to Cytra-Syncmatica. The project is a fork of
[Syncmatica Revolution (syncmatica_r)](https://github.com/Conflux-Union/syncmatica_r)
(CC0-1.0), taken at upstream commit `c330633`.

## 1.0.0 (unreleased) — fork of syncmatica_r

### Removed from upstream

Every item below is gone from the code, the network protocol, the commands, the
config, the UI, the lang files, the docs and the build:

- **Material claims** (`MATERIAL_CLAIMS` feature, `material_claim_toggle` packet,
  `syncmatica_r.claim` permission, claim buttons/tooltips, "claimed" HUD).
- **Upstream material tracking** (`MaterialService`, the `material/*` package,
  `MATERIAL_PROGRESS` feature and its section of the metadata stream, the
  **Material Collections** dashboard, the material progress screen, the material
  HUD overlay, `hudEnabled`/`hudScale` client options, XLSX/CSV export of claims,
  `materials.*` config section). Replaced by Cytra-Syncmatica's own tracking.
- **Stocking areas** (container scanning, sign matching, the stocking area
  registry, `setStockingarea`/`clearStockingarea`/`stockingArea new|edit|delete|list`
  commands, `set_stocking_area`/`stocking_area_manage` packets,
  `STOCKING_AREA_SETUP`/`NAMED_STOCKING_AREAS` features, `meta.json` stocking
  state, legacy `placements.json` stocking migration).
- **TweakerMore integration** (`compat/tweakermore`, `tweakermore_mixin`,
  the TweakerMore lang assets, the `tweakermore` suggests entry).
- **MATERIAL_API** (`api/SyncmaticaMaterialApi`, `docs/MATERIAL_API.md`).
- **Optional web interface** (the `web/*` Undertow server, the Vite frontend,
  `/syncmatica_r web setpassword|disable`, `web.*` config, the shadow/relocation
  build steps and the npm build). Panel hosts cannot open ports.
- **GitHub update checker** and its `checkupdate`/`check_pre_release` config keys
  (client-only mode must make no network calls).
- **Upstream breaking-change notices and legacy config migration**
  (`config/syncmatica` → `config/syncmatica_r` copying, `hud_settings.json`
  migration, 0.4.x notice toasts).
- **Multi-version preprocessor build** (`versions/*`, ReplayMod preprocessor,
  Java 16/17/25 toolchains). This mod targets Minecraft 1.21.11 only.
- **Legacy `syncmatica` channel flavour** on the wire: there is one namespace now.

### Kept

- Schematic/placement sharing and sync: upload, download, lock/unlock (modify
  exchange), rename, dimension handling, quota, transfer limits, feature handshake.
- Build management: sub-region claiming, incremental build-progress scanning,
  rescan command, build dashboard, foreign-placement warning, follow-claims.
- The permissions system (fabric-permissions-api, LuckPerms compatible, with
  vanilla op-level fallbacks), minus the removed nodes.

### Renamed

| Upstream | Cytra-Syncmatica |
|---|---|
| mod id `syncmatica_r` | `cytra-syncmatica` |
| package `cn.net.rms.syncmatica_r` | `com.steelaspect.cytrasyncmatica` |
| network channels `syncmatica_r:*` and `syncmatica:*` | `cytra-syncmatica:*` (one namespace; not wire-compatible with upstream, `breaks` both upstream ids) |
| `/syncmatica_r …` | `/cytra-syncmatica …` |
| permissions `syncmatica_r.share`, `.build.claim`, `.manage`, `.command`, `.command.load`, `.config` | `cytra-syncmatica.share`, `.build.claim`, `.manage`, `.command`, `.command.load`, `.config` |
| config `config/syncmatica_r/config.json`, `client.json`; world `<world>/syncmatica_r/` | `config/cytra-syncmatica/config.json`, `client.json`; `<world>/cytra-syncmatica/` |
| config section `materials.max_schematic_megabytes` | `sharing.max_schematic_megabytes` (the only `materials.*` key sharing/build management needed) |
| `/syncmatica_r <name> rescanBuild` | `/cytra-syncmatica rescan <name>` (the word "project" now means a set of schematics) |
| mixin configs `syncmatica_r.mixin.json`, `syncmatica_r.litematica_mixin.json` | `cytra-syncmatica.mixin.json`, `cytra-syncmatica.litematica_mixin.json` (the Litematica one is client-only) |
| lang keys `syncmatica_r.*` | `cytra-syncmatica.*` (157 → 75 keys after removals) |
| server litematic folder `./syncmatics` | unchanged |
| jar `syncmatica_r-<mc>-<ver>.jar` | `cytra-syncmatica-<ver>+1.21.11.jar` |

### Changed

- Separate `main`, `client` and `server` entrypoints. All Litematica/MaLiLib/
  rendering/screen code lives under `client.*`, `litematica.*`, `litematica_mixin.*`
  and `mixin_actor.*`; the common `communication` package no longer references
  Minecraft client classes (`ExchangeTarget` is abstract with
  `ServerExchangeTarget` and `client.network.ClientExchangeTarget`).
- Litematica and MaLiLib are `recommends` in `fabric.mod.json` (Fabric cannot
  scope `depends` to one side); the client refuses to start without them, the
  dedicated server needs only Fabric API.
- `fabric-permissions-api` is bundled (jar-in-jar).

### Added

- `sharing` config section (`max_schematic_megabytes`,
  `hide_coordinates_without_permission`).
- A dedicated-server entrypoint that reports whether Cytra Link is present.
- Own material tracking (Step 2): the server extracts the material list from
  the shared schematic (Litematica's counting rules), keeps one shared
  gathered count per item per schematic with "last edited by" history in
  `<world>/cytra-syncmatica/materials/<id>.json`, and syncs it over the new
  `material_list` / `material_update` / `material_request` / `material_edit`
  packets (feature `MATERIAL_TRACKING`). Remaining counts are shown as
  shulker boxes + stacks + items. `materials` config section (`enabled`,
  `max_schematic_blocks`). Permissions `cytra-syncmatica.materials.edit`
  (fallback allowed) and `cytra-syncmatica.materials.reset` (fallback op
  level 2).
- `/cytra-syncmatica export <schematic>` writes CSV + txt to
  `config/cytra-syncmatica/exports/`.
- Client material tracker with automatic mode detection (connected /
  client-only / singleplayer; client-only and singleplayer keep counts in
  `config/cytra-syncmatica/client/<server-or-world>/<schematic>.json` and
  never use the network), inventory + open-container auto-count, material
  screen (+1/+16/+64/Set/Done/Reset/Pin, hide completed, sort, Add from
  inventory, Export), HUD overlay of pinned items (position/scale/rows),
  hotkeys `openMaterialTracker` / `toggleMaterialHud`, "Team tracker" button
  in Litematica's material list and a Materials button in its main menu.
- Discord bridge through Cytra Link 0.3.0 (optional dependency, loaded only
  when Cytra Link is installed): requests `ping`, `list_schematics`,
  `get_schematic`, `get_materials`, `get_where`, `material_action`,
  `link_claim`; events `schematic_shared` / `schematic_updated` /
  `schematic_removed`, `list_created`, `item_changed` (batched),
  `item_completed`, `schematic_completed`, `resync`; `bridge` config section
  (`enabled`, `batch_seconds`, `queue_limit`, `hide_coordinates`); queue
  while no bot is connected. See `docs/BRIDGE_PROTOCOL.md`. The mod opens no
  sockets and holds no secrets.
- `/cytra-syncmatica link` issues a one-time code for linking a Discord
  account through the bot.
- `/cytra-syncmatica where <schematic>` (Step 3.5): dimension, origin and
  centre (click to copy), size, and distance from the player when in the same
  dimension. With `sharing.hide_coordinates_without_permission` on, players
  without `cytra-syncmatica.where` (fallback allowed) only see the dimension.
- Material groups (Step 3.1): every item belongs to a group (Stone, Wood,
  Glass, Redstone, Lighting, Metal, Nether & End, Terrain, Decoration,
  Liquids, Other) from a built-in mapping, overridable per item in
  `config/cytra-syncmatica/groups.json` (server for shared lists; the same
  file on the client for client-only and singleplayer lists). The material
  screen has a "Group" button that cycles through the groups and shows the
  group's progress; the search box also matches group names; exports carry a
  `group` column. Bridge: `get_groups`, a `group` filter on `get_materials`,
  `group` on every item, a `group_completed` event.
- Shopping list (Step 3.2): what is still missing as shulker boxes + stacks
  + items, grouped by material group. In game: `/cytra-syncmatica shopping
  <schematic> [group:<name>]` with a click-to-copy of the full text; on the
  client a "Shopping list" screen (minus what you carry, group filter, copy
  to clipboard, export to `config/cytra-syncmatica/exports/<name>-shopping.txt`).
  Bridge: `get_shopping_list`.
- Projects (Step 3.6): named sets of shared schematics tracked as one
  combined material list (`<world>/cytra-syncmatica/projects.json`).
  `/cytra-syncmatica project list|info|create|delete|add|remove`; `where`,
  `shopping` and `export` accept a project name. An edit on a combined item
  fills the first schematic with something left first. Permission
  `cytra-syncmatica.project.manage` (fallback op level 2) for changes.
  Client: a Projects screen (new project, add/remove schematics, open) in
  every mode; client-only and singleplayer keep projects in
  `config/cytra-syncmatica/client/<server-or-world>/projects.json`; the
  material screen shows a project's combined list with a per-schematic
  breakdown on hover. Bridge: `list_projects`, `get_project`,
  `project_action`, project targets on every list request, `parts` per
  item, events `project_changed` / `project_completed`. New packets
  `project_list` / `project_request` / `project_manage`.
- Layer progress (Step 3.3). Server: the build completion scan now also
  counts per world layer (stored with the per-chunk counts under
  `<world>/cytra-syncmatica/`), exposed as `get_layers`, a `build` summary on
  every schematic object and a `layer_completed` bridge event. Client: a
  "Layers" screen (per-layer bars; click a layer to show it in Litematica,
  "All layers", "Follow player", "Next incomplete"), measured on the client by
  comparing Litematica's schematic world with the real world in every mode,
  a "Layer Y: x% · Build: y%" line at the top of the material HUD
  (`hudLayerLine`), hotkey `openLayerProgress`, `layerScanPerTick` setting.
- Top-down previews (Step 3.4): the server renders a map-colour PNG of every
  shared schematic on a background thread (on share, update and load; kept in
  `<world>/cytra-syncmatica/previews/` and restored after a restart),
  `preview` config section (`enabled`, `max_pixels`),
  `/cytra-syncmatica preview <schematic>` writes it to
  `config/cytra-syncmatica/exports/<name>.png`, bridge `get_preview` plus a
  `preview` field on every schematic object.
- Standalone Discord bot in `bot/` (a brand-new bot with its own Discord
  application, code, config and SQLite file; it shares nothing with other
  bots and only speaks Cytra Link's wire protocol to the server):
  `/link`, `/unlink`, `/whoami`, `/links`, `/schematic list|info|materials|where`,
  `/schematic groups`, `/schematic shopping`, `/schematic layers`, `/schematic preview`, `/materials`, `/project …`, a group filter in the materials view,
  live feed channel.
