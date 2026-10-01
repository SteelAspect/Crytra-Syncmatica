package com.steelaspect.cytrasyncmatica.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts every material into one group ("Stone", "Wood", "Redstone", ...) so a
 * long list can be worked through in sections. Groups come from a built-in
 * mapping on the item id (no registry access, so it also runs in tests and on
 * clients without the server's data packs), overridden per item by
 * {@code config/cytra-syncmatica/groups.json}:
 * <pre>{"overrides": {"minecraft:stone": "Walls", "minecraft:oak_planks": "Roof"}}</pre>
 * Group names are free text; the same file format is read on the server (for
 * shared lists) and on the client (for client-only and singleplayer lists).
 */
public final class MaterialGroups {
    private static final Logger LOGGER = LogManager.getLogger(MaterialGroups.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String FILE_NAME = "groups.json";
    public static final int MAX_GROUP_NAME = 32;

    public static final String STONE = "Stone";
    public static final String WOOD = "Wood";
    public static final String GLASS = "Glass";
    public static final String REDSTONE = "Redstone";
    public static final String LIGHTING = "Lighting";
    public static final String METAL = "Metal";
    public static final String NETHER_END = "Nether & End";
    public static final String TERRAIN = "Terrain";
    public static final String DECORATION = "Decoration";
    public static final String LIQUIDS = "Liquids";
    public static final String OTHER = "Other";

    /** Display order of the built-in groups; overrides sort after these, alphabetically. */
    public static final List<String> BUILT_IN = List.of(STONE, WOOD, GLASS, REDSTONE, LIGHTING, METAL, NETHER_END,
            TERRAIN, DECORATION, LIQUIDS, OTHER);

    private static final String[] WOOD_TYPES = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "pale_oak", "bamboo", "crimson", "warped"};
    private static final String[] REDSTONE_WORDS = {"redstone", "piston", "observer", "hopper", "comparator", "repeater",
            "dropper", "dispenser", "rail", "lever", "button", "pressure_plate", "tripwire", "daylight_detector", "target",
            "note_block", "lectern", "sculk_sensor", "lightning_rod", "tnt", "slime_block", "honey_block", "crafter",
            "copper_bulb", "trapped_chest"};
    private static final String[] LIGHT_WORDS = {"torch", "lantern", "glowstone", "shroomlight", "froglight", "candle",
            "campfire", "end_rod", "glow_lichen", "redstone_lamp", "ochre_froglight", "glow_berries"};
    private static final String[] NETHER_END_WORDS = {"netherrack", "nether_", "soul_", "basalt", "blackstone",
            "magma", "end_stone", "purpur", "chorus", "obsidian", "shroomlight", "wither_rose", "end_portal",
            "quartz", "ancient_debris", "gilded"};
    private static final String[] METAL_WORDS = {"iron_", "gold_", "copper", "netherite", "chain", "anvil", "cauldron",
            "bell", "lodestone", "raw_"};
    private static final String[] GLASS_WORDS = {"glass"};
    private static final String[] WOOD_WORDS = {"planks", "_log", "_wood", "stripped_", "fence", "door", "sign", "ladder",
            "bookshelf", "chest", "barrel", "crafting_table", "composter", "loom", "cartography", "fletching", "smithing",
            "beehive", "bee_nest"};
    private static final String[] STONE_WORDS = {"stone", "cobble", "deepslate", "andesite", "diorite", "granite", "tuff",
            "calcite", "dripstone", "brick", "polished", "smooth", "mossy", "prismarine", "terracotta", "concrete",
            "mud_brick", "packed_mud", "amethyst_block", "bedrock"};
    private static final String[] TERRAIN_WORDS = {"dirt", "grass_block", "sand", "gravel", "clay", "mud", "podzol",
            "mycelium", "moss", "snow", "ice", "farmland", "dirt_path", "rooted", "coarse"};
    private static final String[] DECORATION_WORDS = {"wool", "carpet", "_bed", "banner", "flower", "decorated_pot", "painting",
            "item_frame", "leaves", "sapling", "mushroom", "vine", "lily", "head", "skull", "coral", "cake", "tulip",
            "orchid", "fern", "bush", "azalea", "pumpkin", "melon", "hay_block", "cobweb", "sponge", "scaffolding",
            "dandelion", "poppy", "allium", "daisy", "rose", "lilac", "peony", "sunflower", "pitcher", "torchflower",
            "seagrass", "kelp", "cactus", "sugar_cane", "bamboo_block", "dead_bush", "spore", "pink_petals", "wildflowers",
            "leaf_litter", "sculk", "dragon_egg", "beacon", "conduit"};

    private MaterialGroups() {
    }

    /** The group for an item id, honouring {@code overrides} first. Never null or empty. */
    public static String groupOf(final String itemId, final Map<String, String> overrides) {
        if (overrides != null) {
            final String o = overrides.get(itemId);
            if (o != null && !o.isBlank()) {
                return clean(o);
            }
        }
        return builtIn(itemId);
    }

    /** The built-in mapping only. */
    public static String builtIn(final String itemId) {
        if (itemId == null) {
            return OTHER;
        }
        final String path = (itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId).toLowerCase(Locale.ROOT);
        if (path.endsWith("_bucket")) {
            return LIQUIDS;
        }
        if (path.equals("redstone_lamp")) {
            return LIGHTING;
        }
        if (path.contains("redstone")) {
            return REDSTONE;
        }
        if (containsAny(path, LIGHT_WORDS)) {
            return LIGHTING;
        }
        if (containsAny(path, REDSTONE_WORDS)) {
            return REDSTONE;
        }
        if (containsAny(path, GLASS_WORDS)) {
            return GLASS;
        }
        if (containsAny(path, NETHER_END_WORDS)) {
            return NETHER_END;
        }
        if (containsAny(path, METAL_WORDS)) {
            return METAL;
        }
        if (startsWithWoodType(path) || containsAny(path, WOOD_WORDS)) {
            return WOOD;
        }
        if (containsAny(path, DECORATION_WORDS)) {
            return DECORATION;
        }
        if (containsAny(path, STONE_WORDS)) {
            return STONE;
        }
        if (containsAny(path, TERRAIN_WORDS)) {
            return TERRAIN;
        }
        return OTHER;
    }

    private static boolean startsWithWoodType(final String path) {
        for (final String w : WOOD_TYPES) {
            if (path.startsWith(w + "_")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(final String s, final String[] words) {
        for (final String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Trims and caps a group name from a file or request; empty becomes {@link #OTHER}. */
    public static String clean(final String name) {
        if (name == null) {
            return OTHER;
        }
        final String t = name.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return OTHER;
        }
        return t.length() > MAX_GROUP_NAME ? t.substring(0, MAX_GROUP_NAME) : t;
    }

    /** Assigns a group to every entry of the list. */
    public static void assign(final MaterialList list, final Map<String, String> overrides) {
        for (final MaterialEntry e : list.getEntries()) {
            e.setGroup(groupOf(e.getItemId(), overrides));
        }
    }

    /** Sort key: built-in order first, then custom names alphabetically. */
    public static int order(final String group) {
        final int i = BUILT_IN.indexOf(group);
        return i >= 0 ? i : BUILT_IN.size();
    }

    public static int compare(final String a, final String b) {
        final int oa = order(a);
        final int ob = order(b);
        if (oa != ob) {
            return Integer.compare(oa, ob);
        }
        return a.compareToIgnoreCase(b);
    }

    // -- overrides file ----------------------------------------------------------

    /**
     * Reads {@code overrides} from the file; a missing file yields none (see
     * {@link #ensureTemplate(Path)}). Never throws; a broken file logs and
     * yields no overrides.
     */
    public static Map<String, String> loadOverrides(final Path file) {
        if (file == null) {
            return Collections.emptyMap();
        }
        if (!Files.isRegularFile(file)) {
            return Collections.emptyMap();
        }
        try {
            final JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return Collections.emptyMap();
            }
            final JsonObject o = root.getAsJsonObject();
            final JsonElement ov = o.get("overrides");
            if (ov == null || !ov.isJsonObject()) {
                return Collections.emptyMap();
            }
            final Map<String, String> out = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> e : ov.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    final String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
                    out.put(key, clean(e.getValue().getAsString()));
                }
            }
            return out;
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}: {}", file, e.toString());
            return Collections.emptyMap();
        }
    }

    /** Creates the file with an empty mapping so admins find it; a no-op when it exists. */
    public static void ensureTemplate(final Path file) {
        if (file == null || Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            final JsonObject o = new JsonObject();
            o.addProperty("_comment", "Map item ids to group names, e.g. \"minecraft:stone\": \"Walls\". Built-in groups: "
                    + String.join(", ", BUILT_IN) + ". Re-read on the next schematic share/update or /cytra-syncmatica load.");
            o.add("overrides", new JsonObject());
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.debug("Could not write {}", file, e);
        }
    }
}
