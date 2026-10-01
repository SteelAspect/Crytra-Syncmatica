package com.steelaspect.cytrasyncmatica.materials;

import com.steelaspect.cytrasyncmatica.schematic.LitematicNbt;
import com.steelaspect.cytrasyncmatica.schematic.PackedBlockStateArray;
import com.steelaspect.cytrasyncmatica.util.IdentifierUtil;
import com.steelaspect.cytrasyncmatica.util.NbtHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Derives the material list (item id -> count) from a .litematic file on the
 * server, the way Litematica's own material list does on the client: each
 * block state maps to the item that places it; the upper half of doors and the
 * head of beds do not count; a double slab counts twice. Runs off the server
 * thread; only registries are touched.
 */
public final class MaterialListExtractor {
    private static final Logger LOGGER = LogManager.getLogger(MaterialListExtractor.class);
    private static final long DEFAULT_MAX_NBT_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_ENTRIES = 2_048;

    private MaterialListExtractor() {
    }

    public static final class Result {
        public final Map<String, Integer> requirements;
        public final long totalBlocks;
        public final String error;

        Result(final Map<String, Integer> requirements, final long totalBlocks, final String error) {
            this.requirements = requirements;
            this.totalBlocks = totalBlocks;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Maps a block id (as written in the palette) to the item id that places it, or null for "not an item". */
    @FunctionalInterface
    public interface BlockItemResolver {
        String itemFor(String blockId);
    }

    /** The real mapping through the block and item registries. */
    public static final BlockItemResolver REGISTRY_RESOLVER = blockName -> {
        final Optional<Identifier> blockId = IdentifierUtil.tryParse(blockName);
        if (blockId.isEmpty()) {
            return null;
        }
        final Block block = Registries.BLOCK.getOptionalValue(blockId.get()).orElse(Blocks.AIR);
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
            return null;
        }
        final Item item = block.asItem();
        if (item == Items.AIR) {
            return fluidBucket(blockName);
        }
        return Registries.ITEM.getId(item).toString();
    };

    public static Result extract(final File litematicFile, final long maxBlocks) {
        return extract(litematicFile, maxBlocks, DEFAULT_MAX_NBT_BYTES, REGISTRY_RESOLVER);
    }

    public static Result extract(final File litematicFile, final long maxBlocks, final long maxNbtBytes) {
        return extract(litematicFile, maxBlocks, maxNbtBytes, REGISTRY_RESOLVER);
    }

    public static Result extract(final File litematicFile, final long maxBlocks, final long maxNbtBytes,
                                 final BlockItemResolver resolver) {
        final Map<String, Long> totals = new LinkedHashMap<>();
        if (litematicFile == null || !litematicFile.isFile()) {
            return new Result(Map.of(), 0, "file missing");
        }
        long blocks = 0;
        try {
            final NbtCompound regions = NbtHelper.getCompound(LitematicNbt.readRoot(litematicFile, maxNbtBytes), "Regions");
            if (regions == null) {
                return new Result(Map.of(), 0, "no regions");
            }
            for (final String regionName : regions.getKeys()) {
                blocks = accumulateRegion(NbtHelper.getCompound(regions, regionName), totals, blocks, maxBlocks, resolver);
            }
        } catch (final LimitExceeded e) {
            return new Result(Map.of(), 0, "schematic exceeds the block limit of " + maxBlocks);
        } catch (final Exception e) {
            LOGGER.warn("Failed to read materials from {}", litematicFile, e);
            return new Result(Map.of(), 0, "could not read the schematic: " + e.getMessage());
        }
        // sorted by item id for a stable order; counts clamped to int
        final Map<String, Integer> out = new TreeMap<>();
        for (final Map.Entry<String, Long> e : totals.entrySet()) {
            out.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, e.getValue()));
        }
        return new Result(out, blocks, null);
    }

    private static long accumulateRegion(final NbtCompound region, final Map<String, Long> totals,
                                         final long processed, final long maxBlocks, final BlockItemResolver resolver) {
        if (region == null) {
            return processed;
        }
        final int[] size = LitematicNbt.resolveSize(region);
        if (size == null) {
            return processed;
        }
        final long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact((long) size[0], size[1]), size[2]);
        } catch (final ArithmeticException e) {
            throw new LimitExceeded();
        }
        if (volume <= 0) {
            return processed;
        }
        if (processed > maxBlocks - volume) {
            throw new LimitExceeded();
        }
        final NbtList paletteData = NbtHelper.getList(region, "BlockStatePalette");
        if (paletteData == null || paletteData.isEmpty()) {
            return processed + volume;
        }
        final List<PaletteItem> palette = new ArrayList<>(paletteData.size());
        for (int i = 0; i < paletteData.size(); i++) {
            palette.add(resolve(NbtHelper.getCompound(paletteData, i), resolver));
        }
        final long[] blockStates = LitematicNbt.resolveBlockStates(region);
        if (palette.size() == 1) {
            merge(totals, palette.get(0), volume);
        } else if (blockStates.length != 0) {
            final PackedBlockStateArray packed = new PackedBlockStateArray(blockStates, palette.size());
            for (long index = 0; index < volume; index++) {
                final int p = packed.get(index);
                if (p >= 0 && p < palette.size()) {
                    merge(totals, palette.get(p), 1L);
                }
            }
        }
        return processed + volume;
    }

    private record PaletteItem(String itemId, int perBlock) {
    }

    private static PaletteItem resolve(final NbtCompound entry, final BlockItemResolver resolver) {
        if (entry == null) {
            return null;
        }
        final String name = NbtHelper.getString(entry, "Name");
        if (name.isEmpty()) {
            return null;
        }
        int perBlock = 1;
        final NbtCompound props = NbtHelper.getCompound(entry, "Properties");
        if (props != null) {
            if ("upper".equals(NbtHelper.getString(props, "half")) && name.contains("door")) {
                return null; // a door item places both halves
            }
            if ("upper".equals(NbtHelper.getString(props, "half")) && isTallPlant(name)) {
                return null;
            }
            if ("head".equals(NbtHelper.getString(props, "part"))) {
                return null; // a bed item places both parts
            }
            if ("double".equals(NbtHelper.getString(props, "type")) && name.endsWith("_slab")) {
                perBlock = 2;
            }
        }
        final String itemId = resolver.itemFor(name);
        return itemId == null ? null : new PaletteItem(itemId, perBlock);
    }

    private static boolean isTallPlant(final String name) {
        return name.endsWith("sunflower") || name.endsWith("lilac") || name.endsWith("rose_bush")
                || name.endsWith("peony") || name.endsWith("tall_grass") || name.endsWith("large_fern")
                || name.endsWith("pitcher_plant") || name.endsWith("small_dripleaf");
    }

    private static String fluidBucket(final String blockName) {
        if (blockName.endsWith(":water")) {
            return "minecraft:water_bucket";
        }
        if (blockName.endsWith(":lava")) {
            return "minecraft:lava_bucket";
        }
        return null;
    }

    private static void merge(final Map<String, Long> totals, final PaletteItem item, final long count) {
        if (item == null || count <= 0) {
            return;
        }
        if (!totals.containsKey(item.itemId()) && totals.size() >= MAX_ENTRIES) {
            return;
        }
        totals.merge(item.itemId(), count * item.perBlock(), Long::sum);
    }

    private static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
