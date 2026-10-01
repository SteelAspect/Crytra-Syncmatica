package com.steelaspect.cytrasyncmatica.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.command.SyncmaticaCommand;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialAccess;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.service.BridgeService;
import com.steelaspect.cytrasyncmatica.service.MaterialTrackingService;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.cytra.link.api.LinkCaller;
import net.cytra.link.api.LinkEvents;
import net.cytra.link.api.LinkExtension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Cytra Link extension: answers the bot's requests and forwards events.
 * Registered under the {@code "cytra-link"} Fabric entrypoint, so this class is
 * never loaded unless Cytra Link is installed. Requests arrive on a Netty thread
 * and are moved onto the server thread; replies complete from there. The wire
 * shapes are in docs/BRIDGE_PROTOCOL.md.
 */
public final class CytraLinkBridge implements LinkExtension, BridgeSink {
    public static final String NAMESPACE = "cytra-syncmatica";
    private static final Logger LOGGER = LogManager.getLogger("Cytra-Syncmatica/bridge");
    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 500;

    public CytraLinkBridge() {
        BridgeSinkRegistry.register(this);
        LOGGER.info("Cytra Link found: the Discord bridge is available (namespace '{}')", NAMESPACE);
    }

    // -- BridgeSink --------------------------------------------------------------

    @Override
    public void publish(final String type, final JsonObject payload) {
        LinkEvents.publish(NAMESPACE, BridgeService.PROTOCOL_VERSION, type, payload);
    }

    @Override
    public boolean anyBotConnected() {
        return LinkEvents.anyBotConnected();
    }

    // -- LinkExtension -----------------------------------------------------------

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    @Override
    public void onBotConnected() {
        final Context context = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (context == null || context.getBridge() == null) {
            return;
        }
        onServerThread(context, () -> context.getBridge().onBotConnected());
    }

    @Override
    public CompletableFuture<JsonObject> handle(final String op, final int version, final JsonObject payload, final LinkCaller caller) {
        final Context context = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (context == null || !context.isStarted() || context.getBridge() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("server is not running"));
        }
        if (!context.getBridge().isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("the bridge is disabled in config/cytra-syncmatica/config.json"));
        }
        final CompletableFuture<JsonObject> future = new CompletableFuture<>();
        onServerThread(context, () -> {
            try {
                final CompletableFuture<JsonObject> result = dispatch(context, op, payload);
                result.whenComplete((out, err) -> {
                    if (err != null) {
                        future.completeExceptionally(err);
                    } else {
                        future.complete(out);
                    }
                });
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static void onServerThread(final Context context, final Runnable task) {
        final MinecraftServer server = context.getMinecraftServer();
        if (server == null) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    private CompletableFuture<JsonObject> dispatch(final Context context, final String op, final JsonObject payload) {
        switch (op) {
            case "ping":
                return done(ping(context));
            case "list_schematics":
                return done(listSchematics(context));
            case "get_schematic":
                return done(getSchematic(context, payload));
            case "get_materials":
                return done(getMaterials(context, payload));
            case "get_groups":
                return done(getGroups(context, payload));
            case "get_layers":
                return done(getLayers(context, payload));
            case "get_preview":
                return getPreview(context, payload);
            case "list_projects":
                return done(listProjects(context));
            case "get_project":
                return done(getProject(context, payload));
            case "project_action":
                return projectAction(context, payload);
            case "get_shopping_list":
                return done(getShoppingList(context, payload));
            case "get_where":
                return done(getWhere(context, payload));
            case "material_action":
                return materialAction(context, payload);
            case "link_claim":
                return done(linkClaim(context, payload));
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private static CompletableFuture<JsonObject> done(final JsonObject o) {
        return CompletableFuture.completedFuture(o);
    }

    // -- ops ---------------------------------------------------------------------

    private JsonObject ping(final Context context) {
        final JsonObject o = new JsonObject();
        o.addProperty("mod", Syncmatica.getVersion());
        o.addProperty("protocol", BridgeService.PROTOCOL_VERSION);
        o.addProperty("schematics", context.getSyncmaticManager().getAll().size());
        o.addProperty("materials_enabled", context.getMaterialTracking() != null && context.getMaterialTracking().isEnabled());
        o.addProperty("coordinates_hidden", context.getBridge().isHideCoordinates());
        o.addProperty("queued_events", context.getBridge().queuedEvents());
        final JsonArray ops = new JsonArray();
        for (final String s : new String[] {"ping", "list_schematics", "get_schematic", "get_materials", "get_groups", "get_shopping_list", "get_where", "material_action", "get_layers", "get_preview", "list_projects", "get_project", "project_action", "link_claim"}) {
            ops.add(s);
        }
        o.add("ops", ops);
        return o;
    }

    private JsonObject listSchematics(final Context context) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        final boolean hide = context.getBridge().isHideCoordinates();
        final JsonArray arr = new JsonArray();
        final List<ServerPlacement> all = new ArrayList<>(context.getSyncmaticManager().getAll());
        all.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (final ServerPlacement p : all) {
            arr.add(BridgeJson.withPreview(BridgeJson.schematic(p, materials == null ? null : materials.getList(p),
                    materials == null ? null : materials.getStats(p.getId()), hide), context, p));
        }
        final JsonObject o = new JsonObject();
        o.add("schematics", arr);
        return o;
    }

    private JsonObject getSchematic(final Context context, final JsonObject payload) {
        final ServerPlacement p = requirePlacement(context, payload);
        final MaterialTrackingService materials = context.getMaterialTracking();
        final JsonObject o = new JsonObject();
        o.add("schematic", BridgeJson.withPreview(BridgeJson.schematic(p, materials == null ? null : materials.getList(p),
                materials == null ? null : materials.getStats(p.getId()), context.getBridge().isHideCoordinates()), context, p));
        final String error = materials == null ? null : materials.getExtractionError(p.getId());
        if (error != null) {
            o.addProperty("materials_error", error);
        }
        return o;
    }

    private JsonObject getMaterials(final Context context, final JsonObject payload) {
        final Target t = requireTarget(context, payload);
        final MaterialList list = requireList(context, t);
        final boolean missingOnly = bool(payload, "missing_only", false);
        final int offset = Math.max(0, integer(payload, "offset", 0));
        final int limit = Math.max(1, Math.min(MAX_PAGE, integer(payload, "limit", DEFAULT_PAGE)));
        final String sort = str(payload, "sort", "remaining");
        List<MaterialEntry> entries = "name".equals(sort) ? new ArrayList<>(list.copyEntries()) : MaterialTrackingService.sortedCopy(list);
        if ("name".equals(sort)) {
            entries.sort((a, b) -> a.getItemId().compareTo(b.getItemId()));
        }
        if (missingOnly) {
            entries.removeIf(MaterialEntry::isComplete);
        }
        final String group = str(payload, "group", "");
        if (!group.isBlank()) {
            entries.removeIf(e -> !e.getGroup().equalsIgnoreCase(group.trim()));
        }
        final java.util.Map<String, List<com.steelaspect.cytrasyncmatica.materials.CombinedList.Part>> breakdown =
                t.isProject() ? context.getProjects().combined(t.project()).breakdown() : null;
        final JsonArray items = new JsonArray();
        for (int i = offset; i < entries.size() && items.size() < limit; i++) {
            final MaterialEntry e = entries.get(i);
            items.add(breakdown == null ? BridgeJson.entry(e) : BridgeJson.entryWithParts(e, breakdown.get(e.getItemId())));
        }
        final JsonObject o = t.header();
        o.add("summary", BridgeJson.summary(list));
        o.addProperty("total", entries.size());
        o.addProperty("offset", offset);
        o.addProperty("limit", limit);
        if (!group.isBlank()) {
            o.addProperty("group", group.trim());
        }
        o.add("items", items);
        return o;
    }

    private JsonObject getGroups(final Context context, final JsonObject payload) {
        final Target t = requireTarget(context, payload);
        final MaterialList list = requireList(context, t);
        final JsonObject o = t.header();
        o.add("summary", BridgeJson.summary(list));
        o.add("groups", BridgeJson.groups(list));
        return o;
    }

    private JsonObject getShoppingList(final Context context, final JsonObject payload) {
        final Target t = requireTarget(context, payload);
        final MaterialList list = requireList(context, t);
        final String group = str(payload, "group", "");
        final List<com.steelaspect.cytrasyncmatica.materials.ShoppingList.Line> lines =
                com.steelaspect.cytrasyncmatica.materials.ShoppingList.build(list, null, MaterialTrackingService::stackSizeOf, group);
        final String title = t.name() + (group.isBlank() ? "" : " (" + group.trim() + ")");
        final JsonObject o = com.steelaspect.cytrasyncmatica.materials.ShoppingList.toJson(title, lines);
        for (final java.util.Map.Entry<String, JsonElement> h : t.header().entrySet()) {
            o.add(h.getKey(), h.getValue());
        }
        if (!group.isBlank()) {
            o.addProperty("group", group.trim());
        }
        o.add("summary", BridgeJson.summary(list));
        return o;
    }

    private static MaterialList requireList(final Context context, final Target t) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        if (materials == null) {
            throw new IllegalStateException("material tracking is disabled");
        }
        if (t.isProject()) {
            final com.steelaspect.cytrasyncmatica.materials.CombinedList.Combined c = context.getProjects().combined(t.project());
            if (c.list().isEmpty() && c.missingLists() > 0) {
                throw new IllegalStateException("no material list yet for project " + t.name());
            }
            return c.list();
        }
        final MaterialList list = materials.getList(t.placement());
        if (list == null) {
            final String error = materials.getExtractionError(t.placement().getId());
            throw new IllegalStateException(error == null ? "no material list yet for " + t.name() : error);
        }
        return list;
    }

    private JsonObject getWhere(final Context context, final JsonObject payload) {
        final Target t = requireTarget(context, payload);
        final boolean hide = context.getBridge().isHideCoordinates();
        if (t.isProject()) {
            final JsonObject o = t.header();
            o.addProperty("coordinates_hidden", hide);
            final JsonArray arr = new JsonArray();
            for (final ServerPlacement m : context.getProjects().members(t.project())) {
                arr.add(whereOf(context, m, hide));
            }
            o.add("schematics", arr);
            return o;
        }
        return whereOf(context, t.placement(), hide);
    }

    private static JsonObject whereOf(final Context context, final ServerPlacement p, final boolean hide) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        final JsonObject o = new JsonObject();
        o.addProperty("schematic_id", p.getId().toString());
        o.addProperty("schematic", p.getName());
        o.addProperty("dimension", p.getDimension());
        o.addProperty("coordinates_hidden", hide);
        if (!hide) {
            final JsonObject s = BridgeJson.schematic(p, null, materials == null ? null : materials.getStats(p.getId()), false);
            o.add("origin", s.get("origin"));
            o.add("centre", s.has("centre") ? s.get("centre") : null);
            o.add("size", s.has("size") ? s.get("size") : null);
        }
        return o;
    }

    private CompletableFuture<JsonObject> materialAction(final Context context, final JsonObject payload) {
        final Target t = requireTarget(context, payload);
        final String item = str(payload, "item", null);
        final MaterialOp op = MaterialOp.fromWireName(str(payload, "action", null));
        final int amount = integer(payload, "amount", 1);
        final UUID uuid = uuid(str(payload, "mc_uuid", null));
        if (item == null || item.isBlank()) {
            throw new IllegalArgumentException("missing item");
        }
        if (op == null) {
            throw new IllegalArgumentException("action must be one of add, set, done, reset");
        }
        if (uuid == null) {
            throw new IllegalArgumentException("missing or malformed mc_uuid");
        }
        final MaterialTrackingService materials = context.getMaterialTracking();
        if (materials == null || !materials.isEnabled()) {
            throw new IllegalStateException("material tracking is disabled");
        }
        final MinecraftServer server = context.getMinecraftServer();
        final GameProfile profile = resolveProfile(server, uuid);
        if (profile == null) {
            throw new IllegalArgumentException("unknown player " + uuid + ": never joined this server");
        }
        final CompletableFuture<Boolean> allowed = op == MaterialOp.RESET
                ? Permissions.check(profile, MaterialAccess.RESET_PERMISSION, MaterialAccess.RESET_PERMISSION_LEVEL, server)
                : Permissions.check(profile, MaterialAccess.EDIT_PERMISSION, MaterialAccess.EDIT_FALLBACK);
        final CompletableFuture<JsonObject> out = new CompletableFuture<>();
        allowed.whenComplete((ok, err) -> onServerThread(context, () -> {
            try {
                if (err != null) {
                    throw new IllegalStateException("permission check failed: " + err.getMessage());
                }
                if (!Boolean.TRUE.equals(ok)) {
                    throw new IllegalStateException("player " + profile.name() + " is not permitted to " + op.wireName()
                            + " (needs " + MaterialAccess.requiredNode(op) + ")");
                }
                final PlayerIdentifier editor = context.getPlayerIdentifierProvider().createOrGet(profile.id(), profile.name());
                final String outcome;
                if (t.isProject()) {
                    outcome = context.getProjects().apply(t.project(), item, op, amount, editor).name();
                } else {
                    outcome = materials.apply(t.placement(), item, op, amount, editor).name();
                }
                switch (outcome) {
                    case "UNKNOWN_ITEM" -> throw new IllegalArgumentException("unknown item " + item + " in " + t.name());
                    case "NO_LIST" -> throw new IllegalStateException("no material list yet for " + t.name());
                    case "DISABLED" -> throw new IllegalStateException("material tracking is disabled");
                    default -> {
                    }
                }
                final MaterialList list = requireList(context, t);
                final JsonObject o = t.header();
                o.addProperty("changed", "OK".equals(outcome));
                o.add("item", t.isProject()
                        ? BridgeJson.entryWithParts(list.get(item), context.getProjects().combined(t.project()).breakdown().get(item))
                        : BridgeJson.entry(list.get(item)));
                o.add("summary", BridgeJson.summary(list));
                out.complete(o);
            } catch (final Throwable failure) {
                out.completeExceptionally(failure);
            }
        }));
        return out;
    }

    /** Build progress per world layer, from the server's incremental completion scan. */
    private JsonObject getLayers(final Context context, final JsonObject payload) {
        final ServerPlacement p = requirePlacement(context, payload);
        final com.steelaspect.cytrasyncmatica.service.BuildService build = context.getBuildService();
        if (build == null || !build.isEnabled()) {
            throw new IllegalStateException("build management is disabled");
        }
        if (!build.isCompletionEnabled()) {
            throw new IllegalStateException("build completion tracking is disabled (build.completion_enabled)");
        }
        final JsonObject o = new JsonObject();
        o.addProperty("schematic_id", p.getId().toString());
        o.addProperty("schematic", p.getName());
        o.addProperty("scanned", build.isScanned(p));
        o.add("build", BridgeJson.build(p, build));
        final JsonArray layers = new JsonArray();
        int done = 0;
        final java.util.List<com.steelaspect.cytrasyncmatica.service.BuildService.LayerProgress> progress = build.getLayerProgress(p);
        for (final com.steelaspect.cytrasyncmatica.service.BuildService.LayerProgress l : progress) {
            layers.add(BridgeJson.layer(l));
            if (l.complete()) {
                done++;
            }
        }
        o.add("layers", layers);
        o.addProperty("layers_total", progress.size());
        o.addProperty("layers_complete", done);
        return o;
    }

    /** The top-down PNG, base64 encoded; rendered when the schematic was shared/updated/loaded. */
    private CompletableFuture<JsonObject> getPreview(final Context context, final JsonObject payload) {
        final ServerPlacement p = requirePlacement(context, payload);
        final com.steelaspect.cytrasyncmatica.service.PreviewService previews = context.getPreviews();
        if (previews == null || !previews.isEnabled()) {
            throw new IllegalStateException("previews are disabled (preview.enabled)");
        }
        final com.steelaspect.cytrasyncmatica.service.PreviewService.Info info = previews.getInfo(p.getId());
        if (info == null) {
            final String error = previews.getError(p.getId());
            throw new IllegalStateException(error == null ? "no preview yet for " + p.getName() : "preview failed: " + error);
        }
        final CompletableFuture<JsonObject> out = new CompletableFuture<>();
        previews.getPng(p.getId()).whenComplete((png, err) -> {
            if (err != null) {
                out.completeExceptionally(new IllegalStateException("could not read the preview: " + err.getMessage()));
                return;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("schematic_id", p.getId().toString());
            o.addProperty("schematic", p.getName());
            o.add("preview", info.toJson());
            o.addProperty("format", "png");
            o.addProperty("png_base64", java.util.Base64.getEncoder().encodeToString(png));
            out.complete(o);
        });
        return out;
    }

    // -- projects ----------------------------------------------------------------

    private JsonObject listProjects(final Context context) {
        final JsonArray arr = new JsonArray();
        final boolean hide = context.getBridge().isHideCoordinates();
        for (final com.steelaspect.cytrasyncmatica.projects.Project p : context.getProjects().all()) {
            arr.add(BridgeJson.project(p, context, hide));
        }
        final JsonObject o = new JsonObject();
        o.add("projects", arr);
        return o;
    }

    private JsonObject getProject(final Context context, final JsonObject payload) {
        final com.steelaspect.cytrasyncmatica.projects.Project p = requireProject(context, payload);
        final JsonObject o = new JsonObject();
        o.add("project", BridgeJson.project(p, context, context.getBridge().isHideCoordinates()));
        final com.steelaspect.cytrasyncmatica.materials.CombinedList.Combined c = context.getProjects().combined(p);
        o.add("top_remaining", BridgeJson.topRemaining(c.list(), 5));
        return o;
    }

    /** create / delete / rename / add / remove, on behalf of a player with cytra-syncmatica.project.manage. */
    private CompletableFuture<JsonObject> projectAction(final Context context, final JsonObject payload) {
        final String action = str(payload, "action", null);
        final UUID uuid = uuid(str(payload, "mc_uuid", null));
        if (action == null || !List.of("create", "delete", "rename", "add", "remove").contains(action)) {
            throw new IllegalArgumentException("action must be one of create, delete, rename, add, remove");
        }
        if (uuid == null) {
            throw new IllegalArgumentException("missing or malformed mc_uuid");
        }
        final MinecraftServer server = context.getMinecraftServer();
        final GameProfile profile = resolveProfile(server, uuid);
        if (profile == null) {
            throw new IllegalArgumentException("unknown player " + uuid + ": never joined this server");
        }
        final CompletableFuture<Boolean> allowed = Permissions.check(profile, com.steelaspect.cytrasyncmatica.projects.ProjectAccess.MANAGE_PERMISSION,
                com.steelaspect.cytrasyncmatica.projects.ProjectAccess.MANAGE_PERMISSION_LEVEL, server);
        final CompletableFuture<JsonObject> out = new CompletableFuture<>();
        allowed.whenComplete((ok, err) -> onServerThread(context, () -> {
            try {
                if (err != null) {
                    throw new IllegalStateException("permission check failed: " + err.getMessage());
                }
                if (!Boolean.TRUE.equals(ok)) {
                    throw new IllegalStateException("player " + profile.name() + " is not permitted to manage projects (needs "
                            + com.steelaspect.cytrasyncmatica.projects.ProjectAccess.MANAGE_PERMISSION + ")");
                }
                final PlayerIdentifier by = context.getPlayerIdentifierProvider().createOrGet(profile.id(), profile.name());
                final com.steelaspect.cytrasyncmatica.service.ProjectService projects = context.getProjects();
                com.steelaspect.cytrasyncmatica.projects.Project project;
                boolean changed = true;
                switch (action) {
                    case "create" -> project = projects.create(str(payload, "name", ""), by);
                    case "delete" -> {
                        project = requireProject(context, payload);
                        changed = projects.delete(project, by);
                    }
                    case "rename" -> {
                        project = requireProject(context, payload);
                        projects.rename(project, str(payload, "name", ""), by);
                    }
                    default -> {
                        project = requireProject(context, payload);
                        final ServerPlacement placement = requirePlacement(context, payload);
                        changed = "add".equals(action) ? projects.addMember(project, placement, by) : projects.removeMember(project, placement, by);
                    }
                }
                final JsonObject o = new JsonObject();
                o.addProperty("action", action);
                o.addProperty("changed", changed);
                o.add("project", BridgeJson.project(project, context, context.getBridge().isHideCoordinates()));
                out.complete(o);
            } catch (final Throwable failure) {
                out.completeExceptionally(failure);
            }
        }));
        return out;
    }

    /** Either a shared schematic or a project. */
    private record Target(ServerPlacement placement, com.steelaspect.cytrasyncmatica.projects.Project project) {
        boolean isProject() {
            return project != null;
        }

        String name() {
            return isProject() ? project.getName() : placement.getName();
        }

        JsonObject header() {
            final JsonObject o = new JsonObject();
            if (isProject()) {
                o.addProperty("project_id", project.getId().toString());
                o.addProperty("project", project.getName());
            } else {
                o.addProperty("schematic_id", placement.getId().toString());
                o.addProperty("schematic", placement.getName());
            }
            return o;
        }
    }

    /** {@code project_id} / {@code project} win over {@code schematic_id} / {@code schematic}. */
    private static Target requireTarget(final Context context, final JsonObject payload) {
        if (payload != null && (payload.has("project_id") || payload.has("project"))) {
            return new Target(null, requireProject(context, payload));
        }
        return new Target(requirePlacement(context, payload), null);
    }

    private static com.steelaspect.cytrasyncmatica.projects.Project requireProject(final Context context, final JsonObject payload) {
        if (context.getProjects() == null) {
            throw new IllegalStateException("projects are unavailable");
        }
        final String id = str(payload, "project_id", null);
        if (id != null) {
            final com.steelaspect.cytrasyncmatica.projects.Project p = context.getProjects().get(uuid(id));
            if (p != null) {
                return p;
            }
        }
        final String name = str(payload, "project", null);
        if (name != null) {
            final Optional<com.steelaspect.cytrasyncmatica.projects.Project> p = context.getProjects().findByName(name);
            if (p.isPresent()) {
                return p.get();
            }
        }
        throw new IllegalArgumentException("unknown project " + (name != null ? name : id != null ? id : "(none given)"));
    }

    private JsonObject linkClaim(final Context context, final JsonObject payload) {
        final LinkCodes.Claim claim = context.getBridge().getLinkCodes().claim(str(payload, "code", null));
        if (claim == null) {
            throw new IllegalArgumentException("unknown or expired code");
        }
        final JsonObject o = new JsonObject();
        o.addProperty("mc_uuid", claim.uuid().toString());
        o.addProperty("mc_name", claim.name());
        return o;
    }

    // -- helpers -----------------------------------------------------------------

    /** Online player, else the user cache; null when this server never saw the UUID. */
    private static GameProfile resolveProfile(final MinecraftServer server, final UUID uuid) {
        if (server != null) {
            final ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
            if (online != null) {
                return online.getGameProfile();
            }
            if (server.getApiServices().nameToIdCache() != null) {
                final Optional<net.minecraft.server.PlayerConfigEntry> cached = server.getApiServices().nameToIdCache().getByUuid(uuid);
                if (cached.isPresent()) {
                    return new GameProfile(uuid, cached.get().name());
                }
            }
        }
        return null;
    }

    private static ServerPlacement requirePlacement(final Context context, final JsonObject payload) {
        final String id = str(payload, "schematic_id", null);
        if (id != null) {
            final UUID u = uuid(id);
            final ServerPlacement p = u == null ? null : context.getSyncmaticManager().getPlacement(u);
            if (p != null) {
                return p;
            }
        }
        final String name = str(payload, "schematic", null);
        if (name != null) {
            final Optional<ServerPlacement> p = SyncmaticaCommand.findPlacementByName(context, name);
            if (p.isPresent()) {
                return p.get();
            }
        }
        throw new IllegalArgumentException("unknown schematic " + (name != null ? name : id != null ? id : "(none given)"));
    }

    private static String str(final JsonObject o, final String key, final String def) {
        final JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    private static int integer(final JsonObject o, final String key, final int def) {
        final JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() ? def : e.getAsInt();
        } catch (final RuntimeException ex) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(final JsonObject o, final String key, final boolean def) {
        final JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    private static UUID uuid(final String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
