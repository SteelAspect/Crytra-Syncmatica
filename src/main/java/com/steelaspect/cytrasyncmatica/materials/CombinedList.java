package com.steelaspect.cytrasyncmatica.materials;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds several material lists (the schematics of a project) into one. Each
 * combined item remembers which list contributed how much, and an edit on the
 * combined item is split back over the parts in order: the first schematic
 * with something left takes the gathered items first.
 */
public final class CombinedList {
    private CombinedList() {
    }

    /** One list's share of an item. */
    public record Part(String key, String label, int required, int gathered) {
        public int remaining() {
            return Math.max(0, required - gathered);
        }
    }

    public record Source(String key, String label, MaterialList list) {
    }

    public record Combined(MaterialList list, Map<String, List<Part>> breakdown, int missingLists) {
    }

    /** Sources with a null list count as missing (still loading) and contribute nothing. */
    public static Combined combine(final List<Source> sources) {
        final MaterialList out = new MaterialList();
        final Map<String, List<Part>> breakdown = new LinkedHashMap<>();
        final Map<String, long[]> totals = new LinkedHashMap<>();
        final Map<String, MaterialEntry> latest = new LinkedHashMap<>();
        final Map<String, String> groups = new LinkedHashMap<>();
        int missing = 0;
        for (final Source s : sources) {
            if (s.list() == null) {
                missing++;
                continue;
            }
            for (final MaterialEntry e : s.list().getEntries()) {
                breakdown.computeIfAbsent(e.getItemId(), k -> new ArrayList<>())
                        .add(new Part(s.key(), s.label(), e.getRequired(), Math.min(e.getGathered(), e.getRequired())));
                final long[] t = totals.computeIfAbsent(e.getItemId(), k -> new long[2]);
                t[0] += e.getRequired();
                t[1] += Math.min(e.getGathered(), e.getRequired());
                final MaterialEntry prev = latest.get(e.getItemId());
                if (prev == null || e.getEditedAt() > prev.getEditedAt()) {
                    latest.put(e.getItemId(), e);
                }
                groups.putIfAbsent(e.getItemId(), e.getGroup());
            }
        }
        for (final Map.Entry<String, long[]> t : totals.entrySet()) {
            final MaterialEntry first = latest.get(t.getKey());
            final MaterialEntry e = new MaterialEntry(t.getKey(), (int) Math.min(Integer.MAX_VALUE, t.getValue()[0]));
            e.setGathered((int) Math.min(Integer.MAX_VALUE, t.getValue()[1]), first.getEditorUuid(), first.getEditorName(), first.getEditedAt());
            e.setGroup(groups.getOrDefault(t.getKey(), MaterialGroups.OTHER));
            out.put(e);
        }
        return new Combined(out, breakdown, missing);
    }

    /**
     * Splits an edit of the combined item over its parts. Returns the new
     * gathered count per part key (only parts that change).
     */
    public static Map<String, Integer> distribute(final List<Part> parts, final MaterialOp op, final int amount) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (parts == null || parts.isEmpty()) {
            return out;
        }
        switch (op) {
            case DONE -> {
                for (final Part p : parts) {
                    if (p.gathered() < p.required()) {
                        out.put(p.key(), p.required());
                    }
                }
            }
            case RESET -> {
                for (final Part p : parts) {
                    if (p.gathered() > 0) {
                        out.put(p.key(), 0);
                    }
                }
            }
            case SET -> {
                long total = 0;
                for (final Part p : parts) {
                    total += p.gathered();
                }
                add(parts, (long) amount - total, out);
            }
            case ADD -> add(parts, amount, out);
            default -> {
            }
        }
        return out;
    }

    private static void add(final List<Part> parts, long delta, final Map<String, Integer> out) {
        if (delta > 0) {
            for (final Part p : parts) {
                if (delta <= 0) {
                    break;
                }
                final int take = (int) Math.min(delta, p.remaining());
                if (take > 0) {
                    out.put(p.key(), p.gathered() + take);
                    delta -= take;
                }
            }
        } else if (delta < 0) {
            long give = -delta;
            for (int i = parts.size() - 1; i >= 0 && give > 0; i--) {
                final Part p = parts.get(i);
                final int take = (int) Math.min(give, p.gathered());
                if (take > 0) {
                    out.put(p.key(), p.gathered() - take);
                    give -= take;
                }
            }
        }
    }
}
