package com.steelaspect.cytrasyncmatica.materials;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * What is still missing, as a list a builder can take to the storage room:
 * one line per item with the remaining count as shulker boxes + stacks +
 * items, grouped by material group. Optionally subtracts what the reader
 * already carries.
 */
public final class ShoppingList {
    private ShoppingList() {
    }

    public record Line(String itemId, String group, int remaining, int stackSize, String text) {
        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("item", itemId);
            o.addProperty("group", group);
            o.addProperty("remaining", remaining);
            o.addProperty("stack_size", stackSize);
            o.addProperty("text", text);
            return o;
        }
    }

    /**
     * @param inventory  item id -> count the reader carries; subtracted first (may be null)
     * @param stackSizes max stack size per item id
     * @param group      only this group (case-insensitive), or null/blank for all
     */
    public static List<Line> build(final MaterialList list, final Map<String, Integer> inventory,
                                   final ToIntFunction<String> stackSizes, final String group) {
        final List<Line> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        final String filter = group == null || group.isBlank() ? null : group.trim();
        for (final MaterialEntry e : list.getEntries()) {
            if (filter != null && !e.getGroup().equalsIgnoreCase(filter)) {
                continue;
            }
            int remaining = e.getRemaining();
            if (inventory != null) {
                remaining -= Math.max(0, inventory.getOrDefault(e.getItemId(), 0));
            }
            if (remaining <= 0) {
                continue;
            }
            final int stack = Math.max(1, stackSizes.applyAsInt(e.getItemId()));
            out.add(new Line(e.getItemId(), e.getGroup(), remaining, stack, StackFormat.format(remaining, stack)));
        }
        out.sort((a, b) -> {
            final int g = MaterialGroups.compare(a.group(), b.group());
            return g != 0 ? g : Integer.compare(b.remaining(), a.remaining());
        });
        return out;
    }

    public static long totalItems(final List<Line> lines) {
        long n = 0;
        for (final Line l : lines) {
            n += l.remaining();
        }
        return n;
    }

    /** Whole shulker boxes needed if every item is boxed separately. */
    public static long totalShulkers(final List<Line> lines) {
        long n = 0;
        for (final Line l : lines) {
            n += StackFormat.shulkersNeeded(l.remaining(), l.stackSize());
        }
        return n;
    }

    /** Plain text, grouped, ready for a clipboard or a .txt file. */
    public static String toText(final String title, final List<Line> lines) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Shopping list for ").append(title).append('\n');
        if (lines.isEmpty()) {
            sb.append("Nothing left to gather.\n");
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "%d items in %d lines, about %d shulker boxes%n", totalItems(lines), lines.size(), totalShulkers(lines)));
        String current = null;
        for (final Line l : lines) {
            if (!l.group().equals(current)) {
                current = l.group();
                sb.append('\n').append("== ").append(current).append(" ==\n");
            }
            sb.append(String.format(Locale.ROOT, "  %-32s %-22s (%d)%n", prettyName(l.itemId()), l.text(), l.remaining()));
        }
        return sb.toString();
    }

    public static JsonObject toJson(final String title, final List<Line> lines) {
        final JsonObject o = new JsonObject();
        o.addProperty("total_items", totalItems(lines));
        o.addProperty("total_lines", lines.size());
        o.addProperty("shulker_boxes", totalShulkers(lines));
        final JsonArray arr = new JsonArray();
        for (final Line l : lines) {
            arr.add(l.toJson());
        }
        o.add("lines", arr);
        o.addProperty("text", toText(title, lines));
        return o;
    }

    /** {@code minecraft:oak_planks} -> {@code oak planks}; no registry needed. */
    public static String prettyName(final String itemId) {
        final String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return path.replace('_', ' ');
    }
}
