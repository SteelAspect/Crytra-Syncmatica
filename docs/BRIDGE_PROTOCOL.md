# Cytra-Syncmatica bridge protocol

How the Discord bot talks to Cytra-Syncmatica. Everything travels inside
[Cytra Link](https://github.com/SteelAspect/cytra-bridge/tree/master/mod)'s
encrypted connection (Cytra Link **0.3.0 or newer**, which added the extension
frames); Cytra-Syncmatica opens no connection and no port of its own. The bot
dials the server, Cytra Link recognises it, and this extension answers under the
namespace **`cytra-syncmatica`**, protocol version **1**.

Frame limits (enforced by Cytra Link / the bot): a request from the bot is at
most **64 KB**; a reply or event frame is at most **8 MB**. Material lists are
paginated and previews are size-capped to stay well inside that.

Every example below is the complete JSON frame as the bot sends or receives it
(before encryption). `id` is the bot's request counter; the reply echoes it.

## Detecting the extension

The `welcome` frame Cytra Link sends after the bot's `hello` lists the loaded
extensions. A server without Cytra-Syncmatica (or with Cytra Link 0.2.x) has no
`ext` entry for it; the bot must then say so instead of sending requests.

```json
{"t":"welcome","proto":1,"boot":"3f1c9e0a1b2c3d4e","seq":1234,"gap":false,"mod":"0.3.0","mc":"1.21.11","running":true,"ext":["cytra-syncmatica"]}
```

## Requests (bot → mod) and replies (mod → bot)

Request shape: `{"t":"ext","id":n,"ns":"cytra-syncmatica","v":1,"op":"<op>","payload":{...}}`.
Reply shape: `{"t":"res","id":n,"ok":true,"out":{...}}` or `{"t":"res","id":n,"ok":false,"err":"<message>"}`.

Errors common to every op:

| `err` | meaning |
|---|---|
| `no extension cytra-syncmatica` | Cytra-Syncmatica is not installed on that server (sent by Cytra Link itself) |
| `server is not running` | the Minecraft server is starting or stopping |
| `the bridge is disabled in config/cytra-syncmatica/config.json` | `bridge.enabled` is `false` |
| `unknown op <op>` | the op does not exist in this version (see `ping` → `ops`) |
| `unknown schematic <name or id>` | no shared schematic matches `schematic` / `schematic_id` |
| `extension cytra-syncmatica timed out after 30 s` | Cytra Link gave up waiting for the server thread |

Schematic selection: every op that targets a schematic accepts either
`"schematic_id"` (the UUID from `list_schematics`, preferred) or `"schematic"`
(display name, exact match first, then case-insensitive name or file name, then
a UUID prefix of at least 8 characters).

### `ping`

```json
{"t":"ext","id":1,"ns":"cytra-syncmatica","v":1,"op":"ping","payload":{}}
```
```json
{"t":"res","id":1,"ok":true,"out":{"mod":"1.0.0+1.21.11","protocol":1,"schematics":3,"materials_enabled":true,"coordinates_hidden":false,"queued_events":0,"ops":["ping","list_schematics","get_schematic","get_materials","get_groups","get_shopping_list","get_where","material_action","get_layers","get_preview","list_projects","get_project","project_action","link_claim"]}}
```

### `list_schematics`

```json
{"t":"ext","id":2,"ns":"cytra-syncmatica","v":1,"op":"list_schematics","payload":{}}
```
```json
{"t":"res","id":2,"ok":true,"out":{"schematics":[
  {"id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","name":"Iron farm","file_name":"iron_farm","owner":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"last_modified_by":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"created_at":1790000000000,"modified_at":1790000500000,"dimension":"minecraft:overworld","rotation":"NONE","mirror":"NONE","coordinates_hidden":false,"origin":{"x":120,"y":64,"z":-340},"centre":{"x":123,"y":66,"z":-337},"size":{"x":6,"y":4,"z":6},"block_count":138,"unique_blocks":5,"materials":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3}}
]}}
```

Sorted by name. `owner` / `last_modified_by` are `null` when unknown. With
`bridge.hide_coordinates` on, `coordinates_hidden` is `true` and `origin` and
`centre` are absent. `size`, `block_count` (non-air blocks) and `unique_blocks`
come from the last extraction and are absent until it ran. `materials.available`
is `false` while the list has not been built yet.

### `get_schematic`

```json
{"t":"ext","id":3,"ns":"cytra-syncmatica","v":1,"op":"get_schematic","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":3,"ok":true,"out":{"schematic":{ ...same object as one list_schematics entry... }}}
```

When the material list could not be built, `out` also carries
`"materials_error":"schematic exceeds the block limit of 8000000"`.

### `get_materials`

Payload: target plus optional `"missing_only"` (default `false`), `"group"`
(only items of that material group, case-insensitive; echoed back as `group`),
`"sort"` (`"remaining"` default, or `"name"`), `"offset"` (default 0) and
`"limit"` (default 50, max 500). Every item carries its `group`.

```json
{"t":"ext","id":4,"ns":"cytra-syncmatica","v":1,"op":"get_materials","payload":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","missing_only":true,"offset":0,"limit":50}}
```
```json
{"t":"res","id":4,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","summary":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3},"total":4,"offset":0,"limit":50,"items":[
  {"item":"minecraft:stone","required":100,"gathered":40,"remaining":60,"complete":false,"group":"Stone","stack_size":64,"remaining_text":"60","editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"edited_at":1790000600000},
  {"item":"minecraft:oak_planks","required":20,"gathered":0,"remaining":20,"complete":false,"group":"Wood","stack_size":64,"remaining_text":"20","editor":null,"edited_at":0}
]}}
```

`total` is the number of items after the `missing_only` filter; page with
`offset`. `remaining_text` is the remaining count as shulker boxes + stacks +
items (`"2 SB + 3 st + 5"`), computed from `stack_size`.

Errors: `no material list yet for <name>` (extraction still running),
`material tracking is disabled`, or the extraction error text.

### `get_groups`

Progress per material group. Groups come from a built-in mapping of the item
id (Stone, Wood, Glass, Redstone, Lighting, Metal, Nether & End, Terrain,
Decoration, Liquids, Other) overridden per item by the server's
`config/cytra-syncmatica/groups.json` (`{"overrides": {"minecraft:stone": "Walls"}}`,
re-read whenever a list is (re)built). Groups are listed in that order, custom
names last, alphabetically; only groups with at least one item appear.

```json
{"t":"ext","id":9,"ns":"cytra-syncmatica","v":1,"op":"get_groups","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":9,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","summary":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3},"groups":[
  {"name":"Stone","items":2,"required":106,"gathered":40,"remaining":66,"percent":37.7,"complete":false},
  {"name":"Wood","items":2,"required":22,"gathered":0,"remaining":22,"percent":0.0,"complete":false},
  {"name":"Glass","items":1,"required":10,"gathered":0,"remaining":10,"percent":0.0,"complete":false}
]}}
```

Same errors as `get_materials`.

### `get_shopping_list`

What is still missing, grouped, with the remaining count as shulker boxes +
stacks + items and a ready-made plain-text version. Optional `"group"` keeps
one group only. Items that are complete are left out; an empty `lines` means
nothing is left.

```json
{"t":"ext","id":10,"ns":"cytra-syncmatica","v":1,"op":"get_shopping_list","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":10,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","summary":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3},"total_items":98,"total_lines":4,"shulker_boxes":4,"lines":[
  {"item":"minecraft:stone","group":"Stone","remaining":60,"stack_size":64,"text":"60"},
  {"item":"minecraft:stone_slab","group":"Stone","remaining":6,"stack_size":64,"text":"6"},
  {"item":"minecraft:oak_planks","group":"Wood","remaining":20,"stack_size":64,"text":"20"},
  {"item":"minecraft:oak_door","group":"Wood","remaining":2,"stack_size":64,"text":"2"}
],"text":"Shopping list for Iron farm\n98 items in 4 lines, about 4 shulker boxes\n\n== Stone ==\n  stone ... "}}
```

`shulker_boxes` counts whole boxes per item (each item boxed separately).
Same errors as `get_materials`.

### Targets: schematic or project

Every request that names a schematic (`get_materials`, `get_groups`,
`get_shopping_list`, `get_where`, `material_action`) also accepts a project
instead: `"project_id"` or `"project"` (name, case-insensitive, unique prefix
allowed) win over `"schematic_id"` / `"schematic"`. The reply then carries
`project_id` + `project` in place of `schematic_id` + `schematic`, the list is
the project's **combined** list (required and gathered summed over its
members), and every item has a `parts` array with the per-schematic split:

```json
{"item":"minecraft:stone","required":150,"gathered":90,"remaining":60,"complete":false,"group":"Stone","stack_size":64,"remaining_text":"60","editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"edited_at":1790000600000,"parts":[
  {"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","required":100,"gathered":40,"remaining":60},
  {"schematic_id":"1c3b7d2f-aaaa-4bbb-8ccc-556677889900","schematic":"Gold farm","required":50,"gathered":50,"remaining":0}
]}
```

A `material_action` on a project is split over its members: `add` fills the
first schematic with something left first, a negative `add` takes back from
the last, `set` is an `add` of the difference, `done` / `reset` apply to
every member. `get_where` on a project returns `"schematics": [ ...one
get_where reply per member... ]`. Errors: `unknown project <name>`, `no
material list yet for project <name>` (no member has a list yet).

### `get_where`

```json
{"t":"ext","id":5,"ns":"cytra-syncmatica","v":1,"op":"get_where","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":5,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","dimension":"minecraft:overworld","coordinates_hidden":false,"origin":{"x":120,"y":64,"z":-340},"centre":{"x":123,"y":66,"z":-337},"size":{"x":6,"y":4,"z":6}}}
```

With coordinates hidden only `schematic_id`, `schematic`, `dimension` and
`"coordinates_hidden":true` are present. Coordinates are hidden from the bot
when `bridge.hide_coordinates` is on. (In game, `/cytra-syncmatica where
<schematic>` shows the same data; there the `sharing.hide_coordinates_without_permission`
option and the `cytra-syncmatica.where` permission decide, not `bridge.*`.)

### `material_action`

Changes one item's shared gathered count on behalf of a Minecraft player. The
bot resolves the Discord user to a Minecraft UUID through its own account links
and sends it as `mc_uuid`; the mod checks that player's permissions exactly as
for an in-game edit (`cytra-syncmatica.materials.edit`, fallback allowed;
`cytra-syncmatica.materials.reset`, fallback op level 2; LuckPerms-compatible
through fabric-permissions-api, offline players included).

| `action` | effect | `amount` |
|---|---|---|
| `add` | gathered += amount (negative allowed; clamped to 0..required) | default 1 |
| `set` | gathered = amount | required |
| `done` | gathered = required | ignored |
| `reset` | gathered = 0 (needs the reset permission) | ignored |

`set` is idempotent; prefer it over `add` when a retry is possible.

```json
{"t":"ext","id":6,"ns":"cytra-syncmatica","v":1,"op":"material_action","payload":{"schematic":"Iron farm","item":"minecraft:stone","action":"add","amount":64,"mc_uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5"}}
```
```json
{"t":"res","id":6,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","changed":true,"item":{"item":"minecraft:stone","required":100,"gathered":100,"remaining":0,"complete":true,"group":"Stone","stack_size":64,"remaining_text":"0","editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"edited_at":1790000700000},"summary":{"available":true,"items":5,"required":138,"gathered":100,"remaining":38,"percent":72.5,"complete":false,"groups":3}}}
```

`changed` is `false` when the count was already at that value. Errors:

| `err` | when |
|---|---|
| `missing item` / `action must be one of add, set, done, reset` / `missing or malformed mc_uuid` / `amount must be an integer` | bad payload |
| `unknown player <uuid>: never joined this server` | the UUID is not online and not in the server's user cache |
| `player <name> is not permitted to <action> (needs <node>)` | permission check failed |
| `unknown item <id> in <schematic>` | item is not in that schematic's list |
| `no material list yet for <schematic>` / `material tracking is disabled` | nothing to edit |

### `get_layers`

Build progress per world layer, from the server's incremental completion scan
(build management; needs `build.enabled` and `build.completion_enabled`).
Only layers the schematic fills are listed, lowest Y first. `scanned` is
false until at least one sub-region was measured (its chunks have to be
loaded once).

```json
{"t":"ext","id":13,"ns":"cytra-syncmatica","v":1,"op":"get_layers","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":13,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","scanned":true,"build":{"scanned":true,"required":135,"placed":36,"percent":26.7,"complete":false,"layers_total":4,"layers_complete":1},"layers":[
  {"y":64,"expected":36,"placed":36,"percent":100.0,"complete":true},
  {"y":65,"expected":36,"placed":0,"percent":0.0,"complete":false},
  {"y":66,"expected":36,"placed":0,"percent":0.0,"complete":false},
  {"y":67,"expected":29,"placed":0,"percent":0.0,"complete":false}
],"layers_total":4,"layers_complete":1}}
```

`expected` / `required` count the positions that need a block placed, the
way the build scan counts them (air, the upper half of doors and beds, and
blocks without an item form are left out). Every schematic object
(`list_schematics`, `get_schematic`, events) also carries
`"build": {"scanned","required","placed","percent","complete"}`.
Errors: `build management is disabled`, `build completion tracking is
disabled (build.completion_enabled)`.

### `get_preview`

A top-down picture of the schematic in vanilla map colours (each column shows
its highest block, lit by height), rendered by the server on a background
thread whenever the schematic is shared, updated or loaded, and kept under
`<world>/cytra-syncmatica/previews/`. Small schematics are scaled up to 16 px
per block; big ones are sampled (every `step`-th block) so the image stays
within `preview.max_pixels` and the PNG under 5.5 MB (Cytra Link's frame
limit, base64 included).

```json
{"t":"ext","id":14,"ns":"cytra-syncmatica","v":1,"op":"get_preview","payload":{"schematic":"Iron farm"}}
```
```json
{"t":"res","id":14,"ok":true,"out":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","preview":{"available":true,"width":96,"height":96,"blocks_x":6,"blocks_z":6,"scale":16,"step":1,"bytes":646,"generated_at":1790000000000},"format":"png","png_base64":"iVBORw0KGgo..."}}
```

Every schematic object also carries `"preview": {"available": true, ...same
fields...}` or `{"available": false, "error": "..."}` while nothing is
rendered yet. Errors: `previews are disabled (preview.enabled)`, `no preview
yet for <name>`, `preview failed: <reason>`.

### `list_projects`

```json
{"t":"ext","id":11,"ns":"cytra-syncmatica","v":1,"op":"list_projects","payload":{}}
```
```json
{"t":"res","id":11,"ok":true,"out":{"projects":[
  {"id":"5d1e2f3a-1111-4222-8333-444455556666","name":"Base","created_by":"Notch","created_at":1790000000000,"members":[
    {"id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","name":"Iron farm","dimension":"minecraft:overworld","origin":{"x":120,"y":64,"z":-340},"materials":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3}}
  ],"materials":{"available":true,"items":5,"required":138,"gathered":40,"remaining":98,"percent":29.0,"complete":false,"groups":3,"lists_missing":0}}
]}}
```

`origin` is left out of members when `bridge.hide_coordinates` is on.
`materials.lists_missing` counts members whose list is not built yet.

### `get_project`

Payload: `"project_id"` or `"project"`. Reply: `"project"` (as above) plus
`"top_remaining"` (up to five combined items with the most left).

### `project_action`

Creates or changes a project on behalf of a Minecraft player; the mod checks
`cytra-syncmatica.project.manage` (fallback op level 2) for `mc_uuid` the way
`material_action` checks the material permissions.

| `action` | payload | effect |
|---|---|---|
| `create` | `name` | new empty project |
| `rename` | project target + `name` | rename |
| `delete` | project target | delete (schematics stay shared) |
| `add` | project target + schematic target | add a member |
| `remove` | project target + schematic target | remove a member |

```json
{"t":"ext","id":12,"ns":"cytra-syncmatica","v":1,"op":"project_action","payload":{"action":"add","project":"Base","schematic":"Iron farm","mc_uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5"}}
```
```json
{"t":"res","id":12,"ok":true,"out":{"action":"add","changed":true,"project":{ ...list_projects entry... }}}
```

`changed` is `false` when the member was already there (or not there, for
`remove`). Errors: `unknown project`, `a project named 'X' already exists`,
`project name is empty`, `too many projects (max 256)`, the permission and
unknown-player errors of `material_action`.

### `link_claim`

Account linking. In game, `/cytra-syncmatica link` gives the player a one-time
code (6 characters, 10 minutes, one active code per player). The bot's `/link
<code>` sends it here and stores the returned UUID against the Discord user.

```json
{"t":"ext","id":7,"ns":"cytra-syncmatica","v":1,"op":"link_claim","payload":{"code":"K7P2XQ"}}
```
```json
{"t":"res","id":7,"ok":true,"out":{"mc_uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","mc_name":"Notch"}}
```

Error: `unknown or expired code`. Codes are consumed on success and are not
case-sensitive (`0`/`O` and `1`/`I` are treated alike).

## Events (mod → bot)

Shape: `{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"<type>","payload":{...},"ts":<ms>}`.
Every connected bot receives every event. Events are not replayed by Cytra
Link; while no bot is connected the mod keeps up to `bridge.queue_limit`
(default 500) events in memory, drops the oldest beyond that (logged once), and
when a bot attaches it sends the queue followed by a `resync`.

### `schematic_shared`, `schematic_updated`, `schematic_removed`

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"schematic_shared","payload":{"schematic":{ ...same object as a list_schematics entry... }},"ts":1790000000000}
```
```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"schematic_removed","payload":{"id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","name":"Iron farm"},"ts":1790000000000}
```

`schematic_shared` is sent as soon as the placement is registered; its
material list follows a moment later as `list_created` (the preview, once
implemented, is requested separately). `schematic_updated` follows a lock/unlock
edit of the placement (position, rotation, sub-regions, name).

### `list_created`

The material list was (re)built from the schematic file.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"list_created","payload":{"schematic":{ ...list_schematics entry... },"top_remaining":[{ ...get_materials item... }]},"ts":1790000001000}
```

### `item_changed` (batched)

Edits are grouped per schematic and sent at most once every
`bridge.batch_seconds` (default 5; `0` sends immediately). Several edits of one
item inside the window collapse into one change whose `old` is the value before
the first edit and `new` the value after the last. `top_remaining` lists up to 5
unfinished items with the most remaining.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"item_changed","payload":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","changes":[
  {"item":"minecraft:stone","required":100,"gathered":64,"remaining":36,"complete":false,"stack_size":64,"remaining_text":"36","editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"edited_at":1790000700000,"old":0,"new":64,"op":"add"}
],"materials":{"available":true,"items":5,"required":138,"gathered":64,"remaining":74,"percent":46.4,"complete":false,"groups":3},"top_remaining":[ ... ]},"ts":1790000705000}
```

### `item_completed`

Sent immediately (not batched) when an item reaches its required count.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"item_completed","payload":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","item":{ ...get_materials item... },"editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}},"ts":1790000700000}
```

### `group_completed`

Every item of one group is gathered (sent once per edit that completes the
group; any pending `item_changed` batch for that schematic is flushed first).
`group` is one `get_groups` entry.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"group_completed","payload":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","group":{"name":"Glass","items":1,"required":10,"gathered":10,"remaining":0,"percent":100.0,"complete":true},"editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}},"ts":1790000702000}
```

### `schematic_completed`

Every item is gathered. Any pending `item_changed` batch for that schematic is
flushed first.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"schematic_completed","payload":{"schematic":{ ...list_schematics entry... },"editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}},"ts":1790000800000}
```

### `layer_completed`

A world layer went from incomplete to complete in the server's scan (once per
layer; not sent for layers that were already complete when the server started).

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"layer_completed","payload":{"schematic_id":"8f2a6c1e-1b2c-4d3e-9f00-112233445566","schematic":"Iron farm","layer":{"y":64,"expected":36,"placed":36,"percent":100.0,"complete":true},"layers_complete":1,"layers_total":4,"build":{"scanned":true,"required":135,"placed":36,"percent":26.7,"complete":false,"layers_total":4,"layers_complete":1}},"ts":1790001000000}
```

### `project_changed`

After `create`, `rename`, `add`, `remove` or `delete` (also when a shared
schematic is removed and drops out of a project). `action` is one of
`created`, `renamed`, `member_added`, `member_removed`, `deleted`; `by` is the
acting player or `null`.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"project_changed","payload":{"action":"member_added","project":{ ...list_projects entry... },"by":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}},"ts":1790000800000}
```

### `project_completed`

Every item of every member is gathered (once per edit that completes it).

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"project_completed","payload":{"project":{ ...list_projects entry... },"editor":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}},"ts":1790000900000}
```

### `resync`

Sent to a bot right after it attaches (first connection or reconnect), after
any queued events. The bot should re-fetch what it displays.

```json
{"t":"ev","ns":"cytra-syncmatica","v":1,"type":"resync","payload":{"queued_events_sent":3,"schematics":3},"ts":1790000900000}
```

## Server configuration (`config/cytra-syncmatica/config.json`, section `bridge`)

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | answer requests and send events (needs Cytra Link installed to do anything) |
| `batch_seconds` | `5` | `item_changed` batching window per schematic; `0` = immediate |
| `queue_limit` | `500` | events kept while no bot is connected |
| `hide_coordinates` | `false` | strip `origin`/`centre` from every reply and event |

Live changes: `/cytra-syncmatica config set bridge <key> <value>`.

## Server configuration, other sections

`preview.enabled` (default `true`) and `preview.max_pixels` (default
1048576, 65536–16777216) control `get_preview`; `build.completion_enabled`
controls `get_layers`; `materials.enabled` controls everything material
related. All are live through `/cytra-syncmatica config set <section> <key> <value>`.
