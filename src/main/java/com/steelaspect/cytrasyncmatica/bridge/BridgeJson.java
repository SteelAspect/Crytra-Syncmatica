package com.steelaspect.cytrasyncmatica.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor;
import com.steelaspect.cytrasyncmatica.materials.CombinedList;
import com.steelaspect.cytrasyncmatica.materials.StackFormat;
import com.steelaspect.cytrasyncmatica.projects.Project;
import com.steelaspect.cytrasyncmatica.service.MaterialTrackingService;
import net.minecraft.util.math.BlockPos;

/** JSON shapes shared by replies and events; documented in docs/BRIDGE_PROTOCOL.md. */
public final class BridgeJson {
    private BridgeJson() {
    }

    public static JsonObject player(final PlayerIdentifier p) {
        if (p == null || p == PlayerIdentifier.MISSING_PLAYER) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("uuid", p.uuid.toString());
        o.addProperty("name", p.getName());
        return o;
    }

    public static JsonObject position(final BlockPos pos) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    /** The schematic's metadata; coordinates are left out when the server hides them. */
    public static JsonObject schematic(final ServerPlacement p, final MaterialList list, final MaterialListExtractor.Stats stats,
                                       final boolean hideCoordinates) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", p.getId().toString());
        o.addProperty("name", p.getName());
        o.addProperty("file_name", p.getFileName());
        o.add("owner", player(p.getOwner()));
        o.add("last_modified_by", player(p.getLastModifiedBy()));
        o.addProperty("created_at", p.getCreatedAtMillis());
        o.addProperty("modified_at", p.getLastModifiedAtMillis());
        o.addProperty("dimension", p.getDimension());
        o.addProperty("rotation", p.getRotation().name());
        o.addProperty("mirror", p.getMirror().name());
        o.addProperty("coordinates_hidden", hideCoordinates);
        if (!hideCoordinates) {
            o.add("origin", position(p.getPosition()));
            if (stats != null && stats.volume() > 0) {
                final BlockPos origin = p.getPosition();
                final JsonObject centre = new JsonObject();
                centre.addProperty("x", origin.getX() + stats.sizeX() / 2);
                centre.addProperty("y", origin.getY() + stats.sizeY() / 2);
                centre.addProperty("z", origin.getZ() + stats.sizeZ() / 2);
                o.add("centre", centre);
            }
        }
        if (stats != null) {
            final JsonObject size = new JsonObject();
            size.addProperty("x", stats.sizeX());
            size.addProperty("y", stats.sizeY());
            size.addProperty("z", stats.sizeZ());
            o.add("size", size);
            o.addProperty("block_count", stats.nonAirBlocks());
            o.addProperty("unique_blocks", stats.uniqueItems());
        }
        o.add("materials", summary(list));
        o.add("build", build(p, null));
        return o;
    }

    /** Adds {@code preview: {available, width, height, ...}} from the preview service. */
    public static JsonObject withPreview(final JsonObject schematic, final Context context, final ServerPlacement p) {
        final JsonObject preview = new JsonObject();
        final com.steelaspect.cytrasyncmatica.service.PreviewService previews = context.getPreviews();
        final com.steelaspect.cytrasyncmatica.service.PreviewService.Info info = previews == null ? null : previews.getInfo(p.getId());
        if (info != null) {
            schematic.add("preview", info.toJson());
            return schematic;
        }
        preview.addProperty("available", false);
        final String error = previews == null ? "previews are unavailable" : previews.getError(p.getId());
        if (error != null) {
            preview.addProperty("error", error);
        }
        schematic.add("preview", preview);
        return schematic;
    }

    /** Build completion of a placement from its scanned sub-regions, plus layer counts when a service is given. */
    public static JsonObject build(final ServerPlacement p, final com.steelaspect.cytrasyncmatica.service.BuildService build) {
        final JsonObject b = new JsonObject();
        long required = 0;
        long placed = 0;
        boolean scanned = false;
        for (final com.steelaspect.cytrasyncmatica.build_management.BuildRegion r : p.getBuildRegions().getRegions()) {
            required += r.getRequiredBlocks();
            if (r.isScanned()) {
                scanned = true;
                placed += r.getPlacedBlocks();
            }
        }
        b.addProperty("scanned", scanned);
        b.addProperty("required", required);
        b.addProperty("placed", placed);
        b.addProperty("percent", required == 0 ? 100.0 : Math.round(1000.0 * placed / required) / 10.0);
        b.addProperty("complete", scanned && placed >= required);
        if (build != null) {
            final java.util.List<com.steelaspect.cytrasyncmatica.service.BuildService.LayerProgress> layers = build.getLayerProgress(p);
            int done = 0;
            for (final com.steelaspect.cytrasyncmatica.service.BuildService.LayerProgress l : layers) {
                if (l.complete()) {
                    done++;
                }
            }
            b.addProperty("layers_total", layers.size());
            b.addProperty("layers_complete", done);
        }
        return b;
    }

    public static JsonObject layer(final com.steelaspect.cytrasyncmatica.service.BuildService.LayerProgress l) {
        final JsonObject o = new JsonObject();
        o.addProperty("y", l.y());
        o.addProperty("expected", l.expected());
        o.addProperty("placed", l.placed());
        o.addProperty("percent", Math.round(l.percent() * 10.0) / 10.0);
        o.addProperty("complete", l.complete());
        return o;
    }

    public static JsonObject summary(final MaterialList list) {
        final JsonObject m = new JsonObject();
        if (list == null) {
            m.addProperty("available", false);
            return m;
        }
        m.addProperty("available", true);
        m.addProperty("items", list.size());
        m.addProperty("required", list.totalRequired());
        m.addProperty("gathered", list.totalGathered());
        m.addProperty("remaining", list.totalRemaining());
        m.addProperty("percent", Math.round(list.percentComplete() * 10.0) / 10.0);
        m.addProperty("complete", list.isComplete());
        m.addProperty("groups", list.groups().size());
        return m;
    }

    public static JsonArray groups(final MaterialList list) {
        final JsonArray arr = new JsonArray();
        if (list != null) {
            for (final MaterialList.GroupTotals g : list.groups()) {
                arr.add(g.toJson());
            }
        }
        return arr;
    }

    public static JsonObject entry(final MaterialEntry e) {
        final JsonObject o = new JsonObject();
        o.addProperty("item", e.getItemId());
        o.addProperty("required", e.getRequired());
        o.addProperty("gathered", e.getGathered());
        o.addProperty("remaining", e.getRemaining());
        o.addProperty("complete", e.isComplete());
        o.addProperty("group", e.getGroup());
        final int stack = MaterialTrackingService.stackSizeOf(e.getItemId());
        o.addProperty("stack_size", stack);
        o.addProperty("remaining_text", StackFormat.format(e.getRemaining(), stack));
        if (e.getEditorUuid() != null) {
            final JsonObject editor = new JsonObject();
            editor.addProperty("uuid", e.getEditorUuid().toString());
            editor.addProperty("name", e.getEditorName());
            o.add("editor", editor);
        } else {
            o.add("editor", null);
        }
        o.addProperty("edited_at", e.getEditedAt());
        return o;
    }

    /** A project: its members (with their own summaries) and the combined summary. */
    public static JsonObject project(final Project project, final Context context, final boolean hideCoordinates) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", project.getId().toString());
        o.addProperty("name", project.getName());
        o.addProperty("created_by", project.getCreatedBy());
        o.addProperty("created_at", project.getCreatedAt());
        final JsonArray members = new JsonArray();
        final MaterialTrackingService materials = context.getMaterialTracking();
        for (final ServerPlacement p : context.getProjects().members(project)) {
            final JsonObject m = new JsonObject();
            m.addProperty("id", p.getId().toString());
            m.addProperty("name", p.getName());
            m.addProperty("dimension", p.getDimension());
            if (!hideCoordinates) {
                m.add("origin", position(p.getPosition()));
            }
            m.add("materials", summary(materials == null ? null : materials.getList(p)));
            members.add(m);
        }
        o.add("members", members);
        final CombinedList.Combined c = context.getProjects().combined(project);
        final JsonObject combined = summary(c.list());
        combined.addProperty("lists_missing", c.missingLists());
        o.add("materials", combined);
        return o;
    }

    /** get_materials item with the per-schematic breakdown of a project. */
    public static JsonObject entryWithParts(final MaterialEntry e, final java.util.List<CombinedList.Part> parts) {
        final JsonObject o = entry(e);
        final JsonArray arr = new JsonArray();
        if (parts != null) {
            for (final CombinedList.Part p : parts) {
                final JsonObject part = new JsonObject();
                part.addProperty("schematic_id", p.key());
                part.addProperty("schematic", p.label());
                part.addProperty("required", p.required());
                part.addProperty("gathered", p.gathered());
                part.addProperty("remaining", p.remaining());
                arr.add(part);
            }
        }
        o.add("parts", arr);
        return o;
    }

    public static JsonArray topRemaining(final MaterialList list, final int n) {
        final JsonArray arr = new JsonArray();
        if (list == null) {
            return arr;
        }
        for (final MaterialEntry e : MaterialTrackingService.sortedCopy(list)) {
            if (arr.size() >= n || e.getRemaining() == 0) {
                break;
            }
            arr.add(entry(e));
        }
        return arr;
    }
}
