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
