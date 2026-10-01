package com.steelaspect.cytrasyncmatica.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.steelaspect.cytrasyncmatica.build_management.RegionBlocks;
import com.steelaspect.cytrasyncmatica.build_management.RegionGeometry;
import com.steelaspect.cytrasyncmatica.schematic.PackedBlockStateArray;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

final class PreviewRendererTest {
    private static final Identifier AIR = Identifier.of("minecraft", "air");
    private static final Identifier STONE = Identifier.of("minecraft", "stone");
    private static final Identifier GLASS = Identifier.of("minecraft", "glass");

    /** 4x2x2: stone floor everywhere except column (3,1); one glass block on top at (1,1,0). */
    private static RegionBlocks region() {
        final Identifier[] palette = {AIR, STONE, GLASS};
        final int sizeX = 4;
        final int sizeZ = 2;
        final int[] states = new int[16];
        for (int i = 0; i < 8; i++) {
            states[i] = 1; // floor y=0
        }
        states[(int) PackedBlockStateArray.indexOf(3, 0, 1, sizeX, sizeZ)] = 0;
        states[(int) PackedBlockStateArray.indexOf(1, 1, 0, sizeX, sizeZ)] = 2;
        final int bits = PackedBlockStateArray.bitsForPalette(palette.length);
        final long[] packed = new long[(states.length * bits + 63) / 64];
        for (int i = 0; i < states.length; i++) {
            final long bitIndex = (long) i * bits;
            final int longIndex = (int) (bitIndex >> 6);
            final int offset = (int) (bitIndex & 63);
            packed[longIndex] |= ((long) states[i]) << offset;
            if (offset + bits > 64) {
                packed[longIndex + 1] |= ((long) states[i]) >>> (64 - offset);
            }
        }
        return new RegionBlocks(new BlockPos(sizeX, 2, sizeZ), palette, new PackedBlockStateArray(packed, palette.length));
    }

    private static int colors(final Identifier id) {
        return id.equals(STONE) ? 0x707070 : id.equals(GLASS) ? 0xA0E0FF : 0;
    }

    @Test
    void drawsTopBlocksScaledUpWithTransparentEmptyColumns() throws Exception {
        final Map<String, RegionGeometry> geometry = Map.of("main", new RegionGeometry(new BlockPos(0, 0, 0), new BlockPos(4, 2, 2)));
        final PreviewRenderer.Rendered r = PreviewRenderer.render(geometry, Map.of("main", region()), 1_048_576, PreviewRendererTest::colors);
        assertEquals(16, r.scale(), "a tiny schematic is scaled to the maximum");
        assertEquals(1, r.step());
        assertEquals(4 * 16, r.width());
        assertEquals(2 * 16, r.height());
        final BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.png()));
        assertEquals(r.width(), img.getWidth());
        final int glass = img.getRGB(1 * 16 + 3, 0 * 16 + 3);
        final int stone = img.getRGB(0 * 16 + 3, 0 * 16 + 3);
        final int empty = img.getRGB(3 * 16 + 3, 1 * 16 + 3);
        assertEquals(0, (empty >>> 24), "empty column is transparent");
        assertEquals(0xFF, (glass >>> 24));
        assertNotEquals(stone, glass, "the glass on top wins over the stone below it");
        assertTrue(((glass >> 16) & 0xFF) <= 0xA0, "shading never brightens beyond the base colour");
    }

    @Test
    void bigSchematicsAreSampledToFitThePixelBudget() throws Exception {
        final Map<String, RegionGeometry> geometry = Map.of("main", new RegionGeometry(new BlockPos(0, 0, 0), new BlockPos(4, 2, 2)));
        final PreviewRenderer.Rendered r = PreviewRenderer.render(geometry, Map.of("main", region()), 4, PreviewRendererTest::colors);
        assertTrue(r.step() >= 1 && r.scale() >= 1);
        assertTrue((long) r.width() * r.height() <= 1024L * PreviewRenderer.MAX_SCALE * PreviewRenderer.MAX_SCALE, "the floor of 1024 pixels applies");
    }
}
