package com.steelaspect.cytrasyncmatica.build_management;

import com.steelaspect.cytrasyncmatica.schematic.PackedBlockStateArray;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The decoded block layout of one sub-region, kept as identifiers so decoding
 * stays free of the block registry and can run off the server thread.
 */
public final class RegionBlocks {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Identifier[] palette;
    private final PackedBlockStateArray states;
    private RegionColumnHeights columnHeights;
    private int[] layerCounts;

    public RegionBlocks(final BlockPos absoluteSize, final Identifier[] palette,
                        final PackedBlockStateArray states) {
        sizeX = absoluteSize.getX();
        sizeY = absoluteSize.getY();
        sizeZ = absoluteSize.getZ();
        this.palette = palette;
        this.states = states;
    }

    /**
     * Indexes where each schematic column starts and ends.
     *
     * <p>Walks the whole region, so it belongs on the decoding thread next to
     * the decode itself, and is deliberately not done lazily on first use.
     */
    public void measureColumnHeights() {
        columnHeights = RegionColumnHeights.measure(this);
    }

    /**
     * @return the column index, or null when it was never measured or the region
     *         was too wide to index. A caller without one has to walk the full
     *         height of the region.
     */
    public RegionColumnHeights getColumnHeights() {
        return columnHeights;
    }

    /**
     * Counts the positions each schematic layer (local Y) fills with a
     * non-air block. Walks the whole region, so it runs on the decoding thread
     * next to {@link #measureColumnHeights()}.
     */
    public void measureLayerCounts() {
        final boolean[] solid = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            final Identifier id = palette[i];
            solid[i] = id != null && !isAir(id);
        }
        final int[] counts = new int[sizeY];
        for (int y = 0; y < sizeY; y++) {
            int n = 0;
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    final int index = paletteIndexAt(x, y, z);
                    if (index >= 0 && solid[index]) {
                        n++;
                    }
                }
            }
            counts[y] = n;
        }
        layerCounts = counts;
    }

    private static boolean isAir(final Identifier id) {
        final String path = id.getPath();
        return "minecraft".equals(id.getNamespace()) && ("air".equals(path) || "cave_air".equals(path) || "void_air".equals(path));
    }

    /** @return non-air positions per local Y, or null when never measured */
    public int[] getLayerCounts() {
        return layerCounts;
    }

    /** @return roughly what this region costs to keep decoded, for cache budgeting */
    public long getStoredBytes() {
        return states.sizeInBytes() + (columnHeights == null ? 0L : columnHeights.getStoredBytes());
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public Identifier[] getPalette() {
        return palette;
    }

    public long getVolume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    /**
     * @return index into {@link #getPalette()} for a schematic-local position, or
     *         -1 when the position is outside the region or the data is short
     */
    public int paletteIndexAt(final int x, final int y, final int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return -1;
        }
        if (palette.length == 1) {
            return 0;
        }
        final int index = states.get(PackedBlockStateArray.indexOf(x, y, z, sizeX, sizeZ));
        return index >= 0 && index < palette.length ? index : -1;
    }
}
