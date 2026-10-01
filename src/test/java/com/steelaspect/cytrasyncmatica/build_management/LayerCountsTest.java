package com.steelaspect.cytrasyncmatica.build_management;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LayerCountsTest {
    @TempDir
    Path worldFolder;

    private static final RegionBounds BOUNDS = new RegionBounds(new BlockPos(0, 64, 0), new BlockPos(31, 66, 31));

    @Test
    void layerTotalsFollowTheColumnsAndReplaceOnRecount() {
        final RegionScanCache cache = new RegionScanCache(BOUNDS);
        assertEquals(3, cache.layerCount());
        assertEquals(64, cache.minY());
        cache.record(0, 0, 6, new int[] {1, 2, 3});
        cache.record(1, 1, 4, new int[] {4, 0, 0});
        assertArrayEquals(new long[] {5, 2, 3}, cache.getLayerTotals());
        assertEquals(10L, cache.getTotal());
        cache.record(0, 0, 2, new int[] {0, 0, 2});
        assertArrayEquals(new long[] {4, 0, 2}, cache.getLayerTotals(), "the old column contribution is replaced");
        cache.record(1, 0, 3); // counted without layer data
        assertEquals(9L, cache.getTotal());
        assertArrayEquals(new long[] {4, 0, 2}, cache.getLayerTotals());
        assertNull(cache.getLayerCounts(RegionScanCache.packColumn(1, 0)));
        cache.record(5, 5, 9, new int[] {9, 9, 9}); // outside the box
        assertArrayEquals(new long[] {4, 0, 2}, cache.getLayerTotals());
        cache.setExpectedLayers(new long[] {10, 10});
        assertArrayEquals(new long[] {10, 10, 0}, cache.getExpectedLayers(), "padded to the box height");
    }

    @Test
    void layerCountsSurviveTheStore() {
        final UUID id = UUID.randomUUID();
        final BuildScanStore store = new BuildScanStore(worldFolder.toFile());
        final BuildRegionState saved = new BuildRegionState();
        saved.getOrCreate("roof", 100L);
        final RegionScanCache cache = new RegionScanCache(BOUNDS);
        cache.record(0, 0, 6, new int[] {1, 2, 3});
        cache.record(1, 1, 4);
        saved.get("roof").setScanCache(cache);
        saved.get("roof").recordScan(cache.getTotal(), 1L);
        store.save(id, saved);

        final BuildRegionState loaded = new BuildRegionState();
        loaded.getOrCreate("roof", 100L);
        store.load(id, loaded);
        final RegionScanCache back = loaded.get("roof").getScanCache();
        assertNotNull(back);
        assertEquals(10L, back.getTotal());
        assertArrayEquals(new long[] {1, 2, 3}, back.getLayerTotals());
        assertTrue(back.hasLayerData());
        assertNull(back.getLayerCounts(RegionScanCache.packColumn(1, 1)), "a column stored without layers stays without");
    }
}
