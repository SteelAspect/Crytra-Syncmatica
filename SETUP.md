# Setup and test guide

Everything you need is in this repository:

| File | What it is |
|---|---|
| `release/cytra-syncmatica-1.0.0+1.21.11.jar` | the mod (client, dedicated server and singleplayer, one jar) |
| `release/cytra-link-0.3.0.jar` | the Cytra Link mod, server side only, needed only for the Discord bot |
| `release/test-farm.litematic` | a tiny 6×4×6 test schematic (100 stone, 20 oak planks, 2 oak doors, 3 double stone slabs, 10 glass) |
| `bot/` | the Discord bot (Python) |

Versions this was built and tested with (Minecraft **1.21.11**):

| Mod | Version | Where |
|---|---|---|
| Fabric Loader | 0.19.3 (0.16+ works) | https://fabricmc.net/use/ |
| Fabric API | 0.141.6+1.21.11 | https://modrinth.com/mod/fabric-api |
| Litematica | 0.26.11 | https://modrinth.com/mod/litematica |
| MaLiLib | 0.27.16 (0.27.14–0.27.16; 0.27.17+ breaks Litematica 0.26.11) | https://modrinth.com/mod/malilib |
| Java | 21 | |

---

## 1. Quickest test: singleplayer (10 minutes, no server)

1. Install Fabric Loader for 1.21.11 with the Fabric installer.
2. Put into `.minecraft/mods/`: Fabric API, Litematica, MaLiLib, `cytra-syncmatica-1.0.0+1.21.11.jar`.
3. Start the game, open a world. Open MaLiLib's config menu (default `A + C`, then the
   *Cytra-Syncmatica* tab) and bind the hotkeys `openMaterialTracker`, `toggleMaterialHud`,
   `openLayerProgress`.
4. Copy `release/test-farm.litematic` into `.minecraft/schematics/` and load it as a
   Litematica placement (Litematica menu → *Load Schematics*).
5. Press the `openMaterialTracker` key (or Litematica main menu → **Materials**).

What you should see:

- Status line says **Singleplayer · Discord: off**.
- Rows: stone 100, oak planks 20, oak door 2, stone slab 6, glass 10, each with a group
  (Stone, Wood, Glass) and remaining shown as stacks + items (`1 st + 36` for stone).
- `+1`/`+16`/`+64`/`Set`/`Done`/`Reset` change the gathered count; `Pin` puts the row on the HUD;
  **Hide completed**, **Sort**, **Group: …** work; **Add from inventory** counts what you carry.
- **Shopping list** opens the grouped shopping screen; **Copy to clipboard** copies it.
- **Layers** opens the layer screen; click a layer → Litematica shows only that layer;
  the HUD gets a line like `Layer 64: 0% · Build: 0% (0/4 layers)`.
- **Projects** → **New project…**, then **Add…** the schematic: the schematic picker now
  lists `[Project] <name>` and its combined list.
- Counts survive leaving and re-entering the world: they are in
  `.minecraft/config/cytra-syncmatica/client/<world name>/`.

Same steps apply on a server **without** the mod ("client-only" mode): nothing is sent to
that server, the counts live under `client/<server address>/`.

---

## 2. Dedicated server (own machine or panel host)

1. Fabric server for 1.21.11 (Fabric installer → *Server*). On a panel host (Kinetic etc.)
   pick the Fabric 1.21.11 template.
2. `mods/`: Fabric API + `cytra-syncmatica-1.0.0+1.21.11.jar`. **Not** Litematica/MaLiLib.
   For the Discord bot also add `cytra-link-0.3.0.jar`.
3. Start the server. Console shows:
   ```
   Cytra-Syncmatica 1.0.0+1.21.11 on a dedicated server; Discord bridge: available through Cytra Link
   Cytra Link ready (sharing the game port); secret is in config/cytra-link.properties
   ```
   (without Cytra Link: `… Discord bridge: Cytra Link not installed`, which is fine).
4. Config: `config/cytra-syncmatica/config.json` (defaults are good) or
   `/cytra-syncmatica config list` in the console. Everything the mod writes stays inside the
   server folder; it opens no ports.

Server test without any client:

1. Copy `release/test-farm.litematic` into the server's `syncmatics/` folder and run
   `cytra-syncmatica load` in the console → the schematic is shared, its material list and
   preview are built.
2. `cytra-syncmatica shopping farm` → `Shopping list for farm: 138 items, about 2 shulker boxes`
   followed by the grouped lines.
3. `cytra-syncmatica where farm`, `cytra-syncmatica export farm` (writes
   `config/cytra-syncmatica/exports/farm.csv` and `.txt`), `cytra-syncmatica preview farm`
   (writes `exports/farm.png`).
4. `cytra-syncmatica project create Base`, `cytra-syncmatica project add Base farm`,
   `cytra-syncmatica project info Base`.

Two-client test (what could not be tested here):

1. Two players with the client install (section 1) join. Both open the material screen:
   the status line says **Connected · Discord: server decides** and the shared `farm`
   is listed.
2. Player A presses `+16` on stone → player B's screen updates live, with
   `last edited by A`.
3. Player B presses **Reset** → refused unless B is op level 2 or has
   `cytra-syncmatica.materials.reset` (LuckPerms works).
4. Share a schematic from a client: Litematica placement → Cytra-Syncmatica *Share*
   (shift-click). Both see it; the server builds its list; the Discord feed (if set up)
   posts it with the preview.

Permissions (fabric-permissions-api / LuckPerms, vanilla op level in brackets):
`cytra-syncmatica.share`, `.materials.edit` (everyone), `.materials.reset` (op 2),
`.project.manage` (op 2), `.where` (everyone; matters only with
`sharing.hide_coordinates_without_permission`), `.command` / `.command.load` /
`.config` (op 2), `.build.claim`, `.manage`.

---

## 3. The Discord bot

### 3a. Create the Discord application (one time, in a browser)

1. https://discord.com/developers/applications → **New Application**, name it
   (e.g. *Syncmatica*). This is its own application; do not reuse another bot's.
2. **Bot** tab → **Reset Token** → copy the token (you see it once). No privileged
   intents are needed; leave them off.
3. **OAuth2 → URL Generator**: scopes `bot` and `applications.commands`; bot permissions
   *View Channels, Send Messages, Embed Links, Attach Files, Read Message History*.
   Or use this URL with your application id:
   ```
   https://discord.com/oauth2/authorize?client_id=YOUR_APP_ID&scope=bot%20applications.commands&permissions=117760
   ```
   Open it and add the bot to your Discord server.
4. In Discord, *User Settings → Advanced → Developer Mode* on; right-click the server →
   **Copy Server ID**; right-click the feed channel → **Copy Channel ID**.

### 3b. Install and run (any machine with internet; Python 3.11+)

```bash
cd bot
python -m venv .venv && . .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
cp .env.example .env
```

- `.env`: `DISCORD_TOKEN=<token from 3a>` and `CYTRA_LINK_SECRET=<secret= from the
  server's config/cytra-link.properties>` (open that file in the panel's file manager).
- `config.yaml`: `discord.guild_id` (server id), `discord.feed_channel_id` (channel id or
  0), `server.host` (the server's IP or hostname as shown in the panel), `server.port`
  (the game port), `server.name`.

Check, then run:

```bash
python scripts/doctor.py      # config, connection, "extension cytra-syncmatica listed", Discord ids
python run.py --sync          # first start: registers the slash commands
```

Later starts: `python run.py`. As a service: `cytra-syncmatica-bot.service.example`.

### 3c. Test the bot

1. In Discord: `/schematic list` → shows `farm`. `/schematic info farm`,
   `/schematic materials farm`, `/schematic groups farm`, `/schematic shopping farm`,
   `/schematic layers farm`, `/schematic preview farm` (attaches the picture),
   `/project list`.
2. Linking: in game (or the server console as a player is not possible, so use a client)
   run `/cytra-syncmatica link` → a 6-character code; in Discord `/link <code>`;
   `/whoami` shows your Minecraft name.
3. `/schematic materials farm` → pick *stone* in the select → **+16**. The server applies
   it as your Minecraft player; anyone with the material screen open sees it; the feed
   channel's "materials" message for `farm` updates in place. **Reset** is refused with a
   private message unless your player may reset.
4. Finish an item in game (**Done**) → the feed posts `✅ … is complete`; finish a group →
   `📦 Group … is complete`; all items → `🎉`.
5. Stop and restart the bot: the feed keeps editing the same messages; after a reconnect
   the server sends `resync` and the messages refresh.

---

## 4. If something does not work

| Symptom | Check |
|---|---|
| Client crashes at start mentioning Litematica/MaLiLib | both must be installed on the client; versions as in the table |
| "Cytra-Syncmatica isn't installed on that server" in Discord | the server runs Cytra Link but not this mod, or an old Cytra Link (< 0.3.0) |
| `doctor.py`: cannot connect / auth failed | `server.host`/`port` must be the game port the players use; `CYTRA_LINK_SECRET` must match `secret=` exactly; the panel's DDoS filter may need the `port=` option in `config/cytra-link.properties` and an extra port |
| Slash commands missing in Discord | run once with `--sync`; `discord.guild_id` must be the server where the bot was invited |
| No material list for a schematic | `materials.enabled` is true and the schematic is below `materials.max_schematic_blocks` (8M blocks); `/cytra-syncmatica config list` |
| Layers show nothing on the server side (`/schematic layers`) | the schematic's chunks must have been loaded once since the server started (`build.completion_enabled` on) |
| Both this mod and syncmatica_r installed | Fabric refuses to start: remove syncmatica_r (they are not compatible) |

Log files: server `logs/latest.log`; bot `bot/logs/bot.log`.
