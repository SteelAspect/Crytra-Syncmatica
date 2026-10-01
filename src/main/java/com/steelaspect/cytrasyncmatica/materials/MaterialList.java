package com.steelaspect.cytrasyncmatica.materials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The material list of one schematic: item id -> entry, in a stable order. */
public final class MaterialList {
    private final Map<String, MaterialEntry> entries = new LinkedHashMap<>();

    public MaterialEntry get(final String itemId) {
        return entries.get(itemId);
    }

    public Collection<MaterialEntry> getEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public List<MaterialEntry> copyEntries() {
        final List<MaterialEntry> copy = new ArrayList<>(entries.size());
        for (final MaterialEntry e : entries.values()) {
            copy.add(e.copy());
        }
        return copy;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    public MaterialEntry put(final MaterialEntry entry) {
        entries.put(entry.getItemId(), entry);
        return entry;
    }

    /**
     * Replaces the required counts with a fresh extraction, keeping gathered
     * counts and editors of items that are still needed. Items no longer in
     * the schematic disappear; new items start at zero.
     */
    public void applyRequirements(final Map<String, Integer> required) {
        final Map<String, MaterialEntry> old = new LinkedHashMap<>(entries);
        entries.clear();
        for (final Map.Entry<String, Integer> r : required.entrySet()) {
            final MaterialEntry previous = old.get(r.getKey());
            final MaterialEntry e = new MaterialEntry(r.getKey(), r.getValue());
            if (previous != null) {
                e.setGathered(previous.getGathered(), previous.getEditorUuid(), previous.getEditorName(), previous.getEditedAt());
                e.setGroup(previous.getGroup());
            }
            entries.put(e.getItemId(), e);
        }
    }

    public long totalRequired() {
        long n = 0;
        for (final MaterialEntry e : entries.values()) {
            n += e.getRequired();
        }
        return n;
    }

    public long totalGathered() {
        long n = 0;
        for (final MaterialEntry e : entries.values()) {
            n += Math.min(e.getGathered(), e.getRequired());
        }
        return n;
    }

    public long totalRemaining() {
        return Math.max(0L, totalRequired() - totalGathered());
    }

    /** 0..100, by item count; an empty list counts as complete. */
    public double percentComplete() {
        final long required = totalRequired();
        return required == 0 ? 100.0 : 100.0 * totalGathered() / required;
    }

    public boolean isComplete() {
        for (final MaterialEntry e : entries.values()) {
            if (!e.isComplete()) {
                return false;
            }
        }
        return true;
    }

    // -- groups ------------------------------------------------------------------

    /** Totals of one group; {@code items} is the number of distinct items in it. */
    public record GroupTotals(String name, int items, long required, long gathered, long remaining, boolean complete) {
        public double percent() {
            return required == 0 ? 100.0 : 100.0 * gathered / required;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("items", items);
            o.addProperty("required", required);
            o.addProperty("gathered", gathered);
            o.addProperty("remaining", remaining);
            o.addProperty("percent", Math.round(percent() * 10.0) / 10.0);
            o.addProperty("complete", complete);
            return o;
        }
    }

    /** Every group that has at least one item, in display order (built-in groups first). */
    public List<GroupTotals> groups() {
        final Map<String, long[]> acc = new LinkedHashMap<>();
        for (final MaterialEntry e : entries.values()) {
            final long[] t = acc.computeIfAbsent(e.getGroup(), k -> new long[4]);
            t[0]++;
            t[1] += e.getRequired();
            t[2] += Math.min(e.getGathered(), e.getRequired());
            if (!e.isComplete()) {
                t[3] = 1;
            }
        }
        final List<GroupTotals> out = new ArrayList<>(acc.size());
        for (final Map.Entry<String, long[]> g : acc.entrySet()) {
            final long[] t = g.getValue();
            out.add(new GroupTotals(g.getKey(), (int) t[0], t[1], t[2], Math.max(0L, t[1] - t[2]), t[3] == 0));
        }
        out.sort((a, b) -> MaterialGroups.compare(a.name(), b.name()));
        return out;
    }

    /** Totals of one group, or null when no item is in it. */
    public GroupTotals group(final String name) {
        for (final GroupTotals g : groups()) {
            if (g.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return null;
    }

    public boolean isGroupComplete(final String name) {
        boolean any = false;
        for (final MaterialEntry e : entries.values()) {
            if (e.getGroup().equalsIgnoreCase(name)) {
                any = true;
                if (!e.isComplete()) {
                    return false;
                }
            }
        }
        return any;
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        final JsonArray items = new JsonArray();
        for (final MaterialEntry e : entries.values()) {
            items.add(e.toJson());
        }
        o.add("items", items);
        return o;
    }

    public static MaterialList fromJson(final JsonObject o) {
        final MaterialList list = new MaterialList();
        if (o == null || !o.has("items")) {
            return list;
        }
        for (final JsonElement el : o.getAsJsonArray("items")) {
            if (el.isJsonObject()) {
                final MaterialEntry e = MaterialEntry.fromJson(el.getAsJsonObject());
                if (e != null) {
                    list.put(e);
                }
            }
        }
        return list;
    }
}
