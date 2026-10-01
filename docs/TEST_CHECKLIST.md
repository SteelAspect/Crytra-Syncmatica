# Test checklist

How Cytra-Syncmatica 1.0.0 was verified. "Real server" means a stock Fabric
1.21.11 dedicated server (Fabric API only, flat world, offline mode) driven
through Cytra Link's console frames and the bridge extension; the scripts
that did it are described at the end. Items marked **needs a game client**
could not be run in this environment (no display, no Minecraft client).

| # | Check | Result | How |
|---|---|---|---|
| 1 | `./gradlew build` passes | ✅ 203 unit tests | Gradle, Loom 1.17, yarn 1.21.11+build.6 |
| 2 | Dedicated server boots with only Fabric API (no Litematica/MaLiLib) | ✅ | real server; the `recommends` warnings are the only mention of them |
| 3 | Dedicated server boots without Cytra Link | ✅ "Discord bridge: Cytra Link not installed" | real server |
| 4 | Dedicated server boots with Cytra Link 0.3.0 and registers the extension | ✅ "Cytra Link extension 'cytra-syncmatica' provided by mod 'cytra-syncmatica'" | real server |
| 5 | No extra listening ports, no outbound connections, no native code, no external processes | ✅ the JVM's only socket is the game port (`/proc/net/tcp` inspected while running, with a schematic loaded and previews rendered); the source has no `java.net` use | `netcheck.py` + grep |
| 6 | Secrets: the mod stores none; clients receive none | ✅ by construction (the bridge only uses Cytra Link's API; no token/webhook/password in config or packets) | code review |
| 7 | `/cytra-syncmatica load` extracts the material list with live registries (counts match Litematica's rules: doors once, double slabs ×2, water → buckets) | ✅ stone 100, planks 20, doors 2, slabs 6, glass 10 | real server |
| 8 | `export` writes CSV + txt (with group column) | ✅ | real server |
| 9 | `where`, `shopping` (click-to-copy, `group:` filter), `preview`, `project …`, `rescan` | ✅ | real server console |
| 10 | Material edits over the bridge honour the acting player's permissions, offline players included; unknown UUID / unknown item give clear errors | ✅ | bridge e2e (op and non-op UUIDs in `usercache.json`/`ops.json`) |
| 11 | Events queue while no bot is connected and are delivered on attach, followed by `resync`; `item_changed` is batched and reaches two bots; `item_completed`, `group_completed`, `schematic_completed`, `project_changed`, `project_completed`, `layer_completed` fire once | ✅ | bridge e2e |
| 12 | `get_materials` pagination, `missing_only`, `group` filter, `parts` on project targets; `get_groups`, `get_shopping_list`, `get_where`, `get_layers`, `get_preview` (96×96 PNG), `list_projects`/`get_project`/`project_action` | ✅ | bridge e2e |
| 13 | Layer progress: after `fill 0 0 0 5 0 5 stone` the server reports layer 0 complete within seconds and sends `layer_completed` | ✅ | bridge e2e |
| 14 | Projects, material counts, previews and build scans survive a server restart | ✅ | real server restarted between runs |
| 15 | Old Cytra Link 0.2.x (no extension API) → bot says "Cytra-Syncmatica isn't installed on that server" | ✅ | bot test with a fake server without `welcome.ext` |
| 16 | Bot: slash commands, materials view buttons as the linked player, refusals shown privately, group/project/shopping/layers/preview commands, feed messages edited in place, resync, project and layer posts | ✅ 27 pytest tests against an in-process fake Cytra Link server; `scripts/doctor.py` against the real server | `cd bot && pytest` |
| 17 | Cytra Link 0.3.0: extension discovery, duplicate namespace is a startup error, `ext` echo, unknown namespace, thrown/failed futures, 30 s timeout, events to two bots, old requests unaffected | ✅ | cytra-bridge e2e on the real server |
| 18 | Conflict detection: syncmatica_r or syncmatica installed → error in the log, `breaks` in `fabric.mod.json` | ✅ (unit of the check; `breaks` makes Fabric refuse the pair) | code |
| 19 | Client-only mode makes zero network calls | ⚠️ by construction (no packets are sent unless the server advertised `MATERIAL_TRACKING`; the only outbound code path is the vanilla connection) — **needs a game client** to observe |
| 20 | Material screen, HUD, shopping/layers/projects screens, Litematica buttons | **needs a game client** |
| 21 | Two clients see the same counts and "last edited by" live | **needs a game client** |
| 22 | Singleplayer: local counts and projects under `config/cytra-syncmatica/client/<world>/` | **needs a game client** |
| 23 | Layer screen switches Litematica's render layer; HUD layer line | **needs a game client** |

## Scripts used (scratch, not shipped)

- `boot_server.py <jars> --cmd "<console command>"...`: boots the test server
  with the given jars, waits for "Done", runs console commands through Cytra
  Link's `cmd` frame, greps the log for errors, stops.
- `e2e_bridge.py`: boots the server, loads `farm.litematic`, then runs every
  bridge check in rows 10–14 with two raw Cytra Link bot connections.
- `netcheck.py`: boots the server and lists the JVM's sockets from `/proc`.
- `make_litematic.py`: writes the 6×4×6 test schematic (100 stone, 20
  planks, 2 doors, 3 double slabs, 10 glass).
