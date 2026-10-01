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

- `sharing` config section (`max_schematic_megabytes`).
- A dedicated-server entrypoint that reports whether Cytra Link is present.
