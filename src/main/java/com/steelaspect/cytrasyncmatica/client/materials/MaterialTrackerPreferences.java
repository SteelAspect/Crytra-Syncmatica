package com.steelaspect.cytrasyncmatica.client.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Per-player view settings: sort, filter and which items are pinned to the HUD. Local only. */
public final class MaterialTrackerPreferences {
    private static final Logger LOGGER = LogManager.getLogger(MaterialTrackerPreferences.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Path.of("config", Syncmatica.MOD_ID, "client", "tracker.json");

    public enum SortMode { REMAINING, NAME }

    private static SortMode sortMode = SortMode.REMAINING;
    private static boolean hideCompleted;
    private static boolean autoCount = true;
    private static final Map<String, Set<String>> pinned = new LinkedHashMap<>();
    private static String lastSchematicKey = "";
    private static boolean loaded;

    private MaterialTrackerPreferences() {
    }

    public static SortMode getSortMode() {
        load();
        return sortMode;
    }

    public static void cycleSortMode() {
        load();
        sortMode = sortMode == SortMode.REMAINING ? SortMode.NAME : SortMode.REMAINING;
        save();
    }

    public static boolean isHideCompleted() {
        load();
        return hideCompleted;
    }

    public static void toggleHideCompleted() {
        load();
        hideCompleted = !hideCompleted;
        save();
    }

    public static boolean isAutoCount() {
        load();
        return autoCount;
    }

    public static void toggleAutoCount() {
        load();
        autoCount = !autoCount;
        save();
    }

    public static String getLastSchematicKey() {
        load();
        return lastSchematicKey;
    }

    public static void setLastSchematicKey(final String key) {
        load();
        lastSchematicKey = key == null ? "" : key;
        save();
    }

    public static Set<String> pinnedItems(final String schematicKey) {
        load();
        return pinned.getOrDefault(schematicKey, Set.of());
    }

    public static boolean isPinned(final String schematicKey, final String itemId) {
        return pinnedItems(schematicKey).contains(itemId);
    }

    public static void togglePinned(final String schematicKey, final String itemId) {
        load();
        final Set<String> set = pinned.computeIfAbsent(schematicKey, k -> new LinkedHashSet<>());
        if (!set.remove(itemId)) {
            set.add(itemId);
        }
        if (set.isEmpty()) {
            pinned.remove(schematicKey);
        }
        save();
    }

    public static Map<String, Set<String>> allPinned() {
        load();
        return pinned;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("sort")) {
                try {
                    sortMode = SortMode.valueOf(o.get("sort").getAsString());
                } catch (final IllegalArgumentException ignored) {
                    sortMode = SortMode.REMAINING;
                }
            }
            hideCompleted = o.has("hide_completed") && o.get("hide_completed").getAsBoolean();
            autoCount = !o.has("auto_count") || o.get("auto_count").getAsBoolean();
            lastSchematicKey = o.has("last_schematic") ? o.get("last_schematic").getAsString() : "";
            if (o.has("pinned") && o.get("pinned").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> e : o.getAsJsonObject("pinned").entrySet()) {
                    final Set<String> set = new LinkedHashSet<>();
                    if (e.getValue().isJsonArray()) {
                        for (final JsonElement item : e.getValue().getAsJsonArray()) {
                            set.add(item.getAsString());
                        }
                    }
                    if (!set.isEmpty()) {
                        pinned.put(e.getKey(), set);
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.warn("Could not read {}", FILE, e);
        }
    }

    private static void save() {
        final JsonObject o = new JsonObject();
        o.addProperty("sort", sortMode.name());
        o.addProperty("hide_completed", hideCompleted);
        o.addProperty("auto_count", autoCount);
        o.addProperty("last_schematic", lastSchematicKey);
        final JsonObject pins = new JsonObject();
        for (final Map.Entry<String, Set<String>> e : pinned.entrySet()) {
            final JsonArray arr = new JsonArray();
            e.getValue().forEach(arr::add);
            pins.add(e.getKey(), arr);
        }
        o.add("pinned", pins);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.warn("Could not write {}", FILE, e);
        }
    }
}
