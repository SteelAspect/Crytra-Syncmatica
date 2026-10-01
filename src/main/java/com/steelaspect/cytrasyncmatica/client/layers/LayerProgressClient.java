package com.steelaspect.cytrasyncmatica.client.layers;

import com.steelaspect.cytrasyncmatica.client.ClientConfigs;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build progress per layer, measured on the client by comparing Litematica's
 * schematic world with the real world inside the placement's box. Works in
 * every mode and needs nothing from the server; it only sees loaded chunks,
 * so unloaded parts count as "unknown" rather than missing. The walk is
 * spread over ticks (a few thousand positions each) and starts over when it
 * reaches the end, so the numbers refresh continuously.
 */
public final class LayerProgressClient {
    private static final LayerProgressClient INSTANCE = new LayerProgressClient();

    /** One world layer: schematic blocks, blocks already right, blocks in unloaded chunks. */
    public record Layer(int y, int expected, int placed, int unknown) {
        public boolean complete() {
            return expected > 0 && placed >= expected;
        }

        public double percent() {
            return expected == 0 ? 100.0 : Math.min(100.0, 100.0 * placed / expected);
        }
    }

    private TrackedSchematic target;
    private SchematicPlacement placement;
    private BlockPos min;
    private BlockPos max;
    private int cursorX;
    private int cursorY;
    private int cursorZ;
    private int[] expectedNow;
    private int[] placedNow;
    private int[] unknownNow;
    private List<Layer> published = Collections.emptyList();
    private boolean passComplete;
    private boolean followPlayer;
    private int followTicks;

    private LayerProgressClient() {
    }

    public static LayerProgressClient getInstance() {
        return INSTANCE;
    }

    public TrackedSchematic getTarget() {
        return target;
    }

    /** Which schematic to measure; projects are not measured (pick one of their members). */
    public void setTarget(final TrackedSchematic schematic) {
        if (schematic == null || schematic.isProject()) {
            target = null;
            placement = null;
            published = Collections.emptyList();
            return;
        }
        if (schematic.equals(target)) {
            return;
        }
        target = schematic;
        placement = null;
        published = Collections.emptyList();
        restart();
    }

    /** The Litematica placement behind the target, resolved lazily (shared schematics only once rendered). */
    private SchematicPlacement resolvePlacement() {
        if (target == null) {
            return null;
        }
        if (target.local() != null) {
            return target.local();
        }
        if (target.server() != null) {
            return LitematicManager.getInstance().schematicFromSyncmatic(target.server());
        }
        return null;
    }

    private void restart() {
        placement = resolvePlacement();
        if (placement == null) {
            min = null;
            max = null;
            return;
        }
        final Box box = placement.getEclosingBox();
        if (box == null || box.getPos1() == null || box.getPos2() == null) {
            min = null;
            max = null;
            return;
        }
        final BlockPos a = box.getPos1();
        final BlockPos b = box.getPos2();
        min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        final int h = max.getY() - min.getY() + 1;
        expectedNow = new int[h];
        placedNow = new int[h];
        unknownNow = new int[h];
        cursorX = min.getX();
        cursorY = min.getY();
        cursorZ = min.getZ();
        passComplete = false;
    }

    /** Called every client tick. */
    public void tick() {
        final MinecraftClient mc = MinecraftClient.getInstance();
        if (target == null || mc.world == null || mc.player == null) {
            return;
        }
        if (followPlayer && ++followTicks >= 5) {
            followTicks = 0;
            final LayerRange range = DataManager.getRenderLayerRange();
            final int y = mc.player.getBlockPos().getY();
            if (range.getLayerMode() != LayerMode.SINGLE_LAYER) {
                range.setLayerMode(LayerMode.SINGLE_LAYER);
            }
            if (range.getLayerSingle() != y) {
                range.setLayerSingle(y);
            }
        }
        if (placement == null || min == null) {
            restart();
            if (placement == null || min == null) {
                return;
            }
        }
        final WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }
        final ClientWorld world = mc.world;
        int budget = Math.max(256, ClientConfigs.General.LAYER_SCAN_PER_TICK.getIntegerValue());
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        while (budget-- > 0) {
            pos.set(cursorX, cursorY, cursorZ);
            final BlockState expected = schematicWorld.getBlockState(pos);
            if (!expected.isAir()) {
                final int layer = cursorY - min.getY();
                expectedNow[layer]++;
                if (!world.isChunkLoaded(cursorX >> 4, cursorZ >> 4)) {
                    unknownNow[layer]++;
                } else if (world.getBlockState(pos).getBlock() == expected.getBlock()) {
                    placedNow[layer]++;
                }
            }
            if (++cursorX > max.getX()) {
                cursorX = min.getX();
                if (++cursorZ > max.getZ()) {
                    cursorZ = min.getZ();
                    if (++cursorY > max.getY()) {
                        publish();
                        restart();
                        return;
                    }
                }
            }
        }
    }

    private void publish() {
        final List<Layer> out = new ArrayList<>();
        for (int i = 0; i < expectedNow.length; i++) {
            if (expectedNow[i] > 0) {
                out.add(new Layer(min.getY() + i, expectedNow[i], placedNow[i], unknownNow[i]));
            }
        }
        published = Collections.unmodifiableList(out);
        passComplete = true;
    }

    /** Layers with at least one schematic block, lowest first; empty until the first pass finished. */
    public List<Layer> layers() {
        return published;
    }

    public boolean hasData() {
        return passComplete && !published.isEmpty();
    }

    public Layer layerAt(final int y) {
        for (final Layer l : published) {
            if (l.y() == y) {
                return l;
            }
        }
        return null;
    }

    /** Whole-build numbers over every measured layer: {expected, placed, unknown}. */
    public long[] totals() {
        long e = 0;
        long p = 0;
        long u = 0;
        for (final Layer l : published) {
            e += l.expected();
            p += l.placed();
            u += l.unknown();
        }
        return new long[] {e, p, u};
    }

    public int completeLayers() {
        int n = 0;
        for (final Layer l : published) {
            if (l.complete()) {
                n++;
            }
        }
        return n;
    }

    /** The layer the player is looking at: Litematica's single layer when set, else the player's feet. */
    public int currentLayer() {
        final LayerRange range = DataManager.getRenderLayerRange();
        if (range.getLayerMode() == LayerMode.SINGLE_LAYER) {
            return range.getLayerSingle();
        }
        final MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? 0 : mc.player.getBlockPos().getY();
    }

    /** Point Litematica's render layer at one Y. */
    public void showLayer(final int y) {
        followPlayer = false;
        final LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(LayerMode.SINGLE_LAYER);
        range.setLayerSingle(y);
    }

    public void showAllLayers() {
        followPlayer = false;
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }

    public boolean isFollowPlayer() {
        return followPlayer;
    }

    public void setFollowPlayer(final boolean follow) {
        followPlayer = follow;
    }

    /** "Layer 64: 92% · Build: 61%" for the HUD, or null without data. */
    public String hudLine() {
        if (target == null || !hasData()) {
            return null;
        }
        final long[] t = totals();
        final int y = currentLayer();
        final Layer l = layerAt(y);
        final String layerText = l == null ? "Layer " + y + ": –" : String.format(java.util.Locale.ROOT, "Layer %d: %.0f%%", y, l.percent());
        final double build = t[0] == 0 ? 100.0 : 100.0 * t[1] / t[0];
        return String.format(java.util.Locale.ROOT, "%s · Build: %.0f%% (%d/%d layers)", layerText, build, completeLayers(), published.size());
    }

    /** On disconnect. */
    public void reset() {
        target = null;
        placement = null;
        min = null;
        max = null;
        published = Collections.emptyList();
        passComplete = false;
        followPlayer = false;
    }
}
