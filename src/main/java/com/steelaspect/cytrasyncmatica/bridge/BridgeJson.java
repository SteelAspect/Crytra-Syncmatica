package com.steelaspect.cytrasyncmatica.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor;
import com.steelaspect.cytrasyncmatica.materials.StackFormat;
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
