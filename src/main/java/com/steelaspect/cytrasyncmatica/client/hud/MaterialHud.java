package com.steelaspect.cytrasyncmatica.client.hud;

import com.steelaspect.cytrasyncmatica.client.ClientConfigs;
import com.steelaspect.cytrasyncmatica.client.gui.WidgetMaterialTrackerEntry;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerPreferences;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.StackFormat;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pinned items as a small overlay. What is pinned is a local choice
 * ({@link MaterialTrackerPreferences}); position and scale come from the client
 * config. Rebuilt from the tracker's lists at most every few ticks.
 */
public final class MaterialHud implements HudRenderCallback {
    private static final MaterialHud INSTANCE = new MaterialHud();
    private static final int ROW_HEIGHT = 12;
    private static final int PADDING = 4;
    private static final int REBUILD_TICKS = 10;

    private record Row(ItemStack stack, String name, String remaining, int inventory, boolean complete, boolean header) {
    }

    private final List<Row> rows = new ArrayList<>();
    private int ticks;
    private boolean registered;

    private MaterialHud() {
    }

    public static MaterialHud getInstance() {
        return INSTANCE;
    }

    public void register() {
        if (!registered) {
            registered = true;
            HudRenderCallback.EVENT.register(this);
        }
    }

    public void tick() {
        if (++ticks < REBUILD_TICKS) {
            return;
        }
        ticks = 0;
        rebuild();
    }

    public void reset() {
        rows.clear();
    }

    private void rebuild() {
        rows.clear();
        if (!ClientConfigs.General.HUD_ENABLED.getBooleanValue()) {
            return;
        }
        final MaterialTrackerClient tracker = MaterialTrackerClient.getInstance();
        final Map<String, Set<String>> pinned = MaterialTrackerPreferences.allPinned();
        if (pinned.isEmpty()) {
            return;
        }
        final int maxRows = ClientConfigs.General.HUD_MAX_ROWS.getIntegerValue();
        for (final TrackedSchematic schematic : tracker.availableSchematics()) {
            final Set<String> items = pinned.get(schematic.key());
            if (items == null || items.isEmpty()) {
                continue;
            }
            final MaterialList list = tracker.getList(schematic);
            if (list == null) {
                continue;
            }
            boolean headerAdded = false;
            for (final String itemId : items) {
                final MaterialEntry e = list.get(itemId);
                if (e == null) {
                    continue;
                }
                if (!headerAdded) {
                    rows.add(new Row(ItemStack.EMPTY, schematic.name(), "", 0, false, true));
                    headerAdded = true;
                }
                final ItemStack stack = WidgetMaterialTrackerEntry.stackOf(itemId);
                final String remaining = e.getRemaining() == 0 ? "✓"
                        : StackFormat.format(e.getRemaining(), stack.isEmpty() ? 64 : stack.getMaxCount());
                rows.add(new Row(stack, WidgetMaterialTrackerEntry.displayName(itemId), remaining,
                        tracker.inInventory(itemId), e.isComplete(), false));
                if (rows.size() >= maxRows) {
                    return;
                }
            }
        }
    }

    @Override
    public void onHudRender(final DrawContext context, final RenderTickCounter tickCounter) {
        if (rows.isEmpty()) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.currentScreen != null) {
            return;
        }
        final TextRenderer font = client.textRenderer;
        final float scale = (float) ClientConfigs.General.HUD_SCALE.getDoubleValue();
        int contentWidth = 0;
        for (final Row r : rows) {
            final int w = r.header ? font.getWidth(r.name) : 20 + font.getWidth(r.name) + 8 + font.getWidth(r.remaining + "  inv " + r.inventory);
            contentWidth = Math.max(contentWidth, w);
        }
        final int boxW = contentWidth + PADDING * 2;
        final int boxH = rows.size() * ROW_HEIGHT + PADDING * 2;
        final int x = ClientConfigs.General.HUD_X.getIntegerValue();
        final int y = ClientConfigs.General.HUD_Y.getIntegerValue();

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);
        context.fill(0, 0, boxW, boxH, 0x80000000);
        int rowY = PADDING;
        for (final Row r : rows) {
            if (r.header) {
                context.drawText(font, r.name, PADDING, rowY + 2, 0xFFFFD080, true);
            } else {
                if (!r.stack.isEmpty()) {
                    context.drawItem(r.stack, PADDING, rowY - 2);
                }
                context.drawText(font, r.name, PADDING + 20, rowY + 2, r.complete ? 0xFF80FF80 : 0xFFFFFFFF, true);
                final String right = r.remaining + "  inv " + r.inventory;
                context.drawText(font, right, boxW - PADDING - font.getWidth(right), rowY + 2, r.complete ? 0xFF80FF80 : 0xFFFFC080, true);
            }
            rowY += ROW_HEIGHT;
        }
        context.getMatrices().popMatrix();
    }
}
