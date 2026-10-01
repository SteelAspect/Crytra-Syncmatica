package com.steelaspect.cytrasyncmatica.preview;

import com.steelaspect.cytrasyncmatica.build_management.RegionBlocks;
import com.steelaspect.cytrasyncmatica.build_management.RegionGeometry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Draws a schematic from above the way a map would: every column shows the
 * map colour of its highest block, lit by height (brighter the higher it
 * sits, darker where the column north of it is higher). Pure: the block
 * colour lookup is injected, so it runs in tests and off the server thread.
 */
public final class PreviewRenderer {
    public static final int MAX_SCALE = 16;

    private PreviewRenderer() {
    }

    /** ARGB colour of a block id; 0 for "draw nothing" (air). */
    @FunctionalInterface
    public interface BlockColorResolver {
        int colorOf(Identifier blockId);
    }

    public record Rendered(byte[] png, int width, int height, int blocksX, int blocksZ, int scale, int step) {
    }

    /**
     * @param maxPixels the image is at most this many pixels: small schematics
     *                  are scaled up (to {@link #MAX_SCALE} px per block), big
     *                  ones sample every {@code step}-th block
     */
    public static Rendered render(final Map<String, RegionGeometry> geometry, final Map<String, RegionBlocks> blocks,
                                  final long maxPixels, final BlockColorResolver colors) throws IOException {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final Map.Entry<String, RegionGeometry> g : geometry.entrySet()) {
            if (!blocks.containsKey(g.getKey())) {
                continue;
            }
            final BlockPos p = g.getValue().getPosition();
            final BlockPos s = g.getValue().getSize();
            final int x0 = s.getX() >= 0 ? p.getX() : p.getX() + s.getX() + 1;
            final int z0 = s.getZ() >= 0 ? p.getZ() : p.getZ() + s.getZ() + 1;
            minX = Math.min(minX, x0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x0 + Math.abs(s.getX()) - 1);
            maxZ = Math.max(maxZ, z0 + Math.abs(s.getZ()) - 1);
        }
        if (minX > maxX) {
            throw new IOException("the schematic has no regions to draw");
        }
        final int blocksX = maxX - minX + 1;
        final int blocksZ = maxZ - minZ + 1;
        final long area = (long) blocksX * blocksZ;
        final long pixels = Math.max(1024L, maxPixels);
        int step = 1;
        while ((area / ((long) step * step)) > pixels) {
            step++;
        }
        final int cellsX = (blocksX + step - 1) / step;
        final int cellsZ = (blocksZ + step - 1) / step;
        final int scale = step > 1 ? 1 : (int) Math.max(1, Math.min(MAX_SCALE, Math.floor(Math.sqrt((double) pixels / area))));

        // top block per column: colour and height
        final int[] top = new int[blocksX * blocksZ];
        final int[] height = new int[blocksX * blocksZ];
        java.util.Arrays.fill(height, Integer.MIN_VALUE);
        for (final Map.Entry<String, RegionGeometry> g : geometry.entrySet()) {
            final RegionBlocks rb = blocks.get(g.getKey());
            if (rb == null) {
                continue;
            }
            final BlockPos p = g.getValue().getPosition();
            final BlockPos s = g.getValue().getSize();
            final int x0 = s.getX() >= 0 ? p.getX() : p.getX() + s.getX() + 1;
            final int y0 = s.getY() >= 0 ? p.getY() : p.getY() + s.getY() + 1;
            final int z0 = s.getZ() >= 0 ? p.getZ() : p.getZ() + s.getZ() + 1;
            final Identifier[] palette = rb.getPalette();
            final int[] paletteColor = new int[palette.length];
            for (int i = 0; i < palette.length; i++) {
                paletteColor[i] = palette[i] == null ? 0 : colors.colorOf(palette[i]);
            }
            for (int lz = 0; lz < rb.getSizeZ(); lz++) {
                for (int lx = 0; lx < rb.getSizeX(); lx++) {
                    final int column = (z0 + lz - minZ) * blocksX + (x0 + lx - minX);
                    for (int ly = rb.getSizeY() - 1; ly >= 0; ly--) {
                        final int index = rb.paletteIndexAt(lx, ly, lz);
                        if (index < 0 || paletteColor[index] == 0) {
                            continue;
                        }
                        final int worldY = y0 + ly;
                        if (worldY > height[column]) {
                            height[column] = worldY;
                            top[column] = paletteColor[index];
                        }
                        break;
                    }
                }
            }
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (final int h : height) {
            if (h != Integer.MIN_VALUE) {
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
            }
        }
        final BufferedImage image = new BufferedImage(cellsX * scale, cellsZ * scale, BufferedImage.TYPE_INT_ARGB);
        for (int cz = 0; cz < cellsZ; cz++) {
            for (int cx = 0; cx < cellsX; cx++) {
                final int bx = Math.min(blocksX - 1, cx * step);
                final int bz = Math.min(blocksZ - 1, cz * step);
                final int column = bz * blocksX + bx;
                if (height[column] == Integer.MIN_VALUE) {
                    continue;
                }
                final int h = height[column];
                final int north = bz > 0 ? height[column - blocksX] : Integer.MIN_VALUE;
                double light = north == Integer.MIN_VALUE || north == h ? 0.86 : north > h ? 0.71 : 1.0;
                if (highest > lowest) {
                    light *= 0.75 + 0.25 * (h - lowest) / (double) (highest - lowest);
                }
                final int argb = shade(top[column], light);
                for (int py = 0; py < scale; py++) {
                    for (int px = 0; px < scale; px++) {
                        image.setRGB(cx * scale + px, cz * scale + py, argb);
                    }
                }
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer available");
        }
        return new Rendered(out.toByteArray(), image.getWidth(), image.getHeight(), blocksX, blocksZ, scale, step);
    }

    static int shade(final int rgb, final double light) {
        final int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xFF) * light));
        final int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xFF) * light));
        final int b = (int) Math.min(255, Math.round((rgb & 0xFF) * light));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The real lookup: vanilla map colours from the block registry (thread-safe reads). */
    public static final BlockColorResolver REGISTRY_COLORS = id -> {
        try {
            final net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (block == null || block == net.minecraft.block.Blocks.AIR) {
                return 0;
            }
            final net.minecraft.block.MapColor color = block.getDefaultMapColor();
            if (color == null || color == net.minecraft.block.MapColor.CLEAR) {
                return block.asItem() == net.minecraft.item.Items.AIR ? 0 : 0x808080;
            }
            return color.color;
        } catch (final RuntimeException | LinkageError e) {
            return 0x808080;
        }
    };
}
