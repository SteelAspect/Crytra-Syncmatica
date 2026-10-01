package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.build_management.BuildRegion;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.Click;

/**
 * One shared schematic in the build management overview. Clicking the row opens
 * its regions.
 */
public class WidgetBuildPlacementEntry extends WidgetListEntryBase<ServerPlacement> {

    static final int CLAIMED_COLUMN_WIDTH = 70;
    static final int PROGRESS_COLUMN_WIDTH = 70;

    /**
     * Handed down rather than looked up from the client: which screen is current
     * is asked for differently across versions, and the list already knows who
     * owns it.
     */
    private final GuiBuildManagement parent;

    public WidgetBuildPlacementEntry(final int x, final int y, final int width, final int height,
                                     final ServerPlacement placement, final int listIndex,
                                     final GuiBuildManagement parent) {
        super(x, y, width, height, placement, listIndex);
        this.parent = parent;
    }

    @Override
    public void render(final GuiContext guiContext, final int mouseX, final int mouseY, final boolean selected) {
        // Bundling the version-specific draw calls into two local adapters keeps
        // the rest of this method free of preprocessor blocks.
        final RectDrawer rects = (rx, ry, rw, rh, color) -> {
            RenderUtils.drawRect(guiContext, rx, ry, rw, rh, color);
        };
        final TextDrawer texts = (text, tx, ty, color) -> {
            drawString(guiContext, tx, ty, color, text);
        };

        final ServerPlacement placement = getEntry();
        final boolean hovered = isMouseOver(mouseX, mouseY);
        rects.drawRect(x, y, width, height,
                hovered ? 0x40FFFFFF : (listIndex % 2 == 0 ? 0x20FFFFFF : 0x10FFFFFF));
        if (placement == null) {
            return;
        }

        final int progressColumnRight = x + width - 8;
        final int claimedColumnRight = progressColumnRight - PROGRESS_COLUMN_WIDTH;
        final int regionColumnRight = claimedColumnRight - CLAIMED_COLUMN_WIDTH;
        final int textY = y + 6;

        texts.drawString(placement.getName(), x + 6, textY, 0xFFFFFFFF);

        int regionCount = 0;
        int claimedCount = 0;
        // Weighted by block count rather than by region, so one huge region does
        // not read as the same progress as one tiny one.
        long required = 0L;
        long placed = 0L;
        boolean anyScanned = false;
        for (final BuildRegion region : placement.getBuildRegions().getRegions()) {
            regionCount++;
            if (region.isClaimed()) {
                claimedCount++;
            }
            required += region.getRequiredBlocks();
            if (region.isScanned()) {
                anyScanned = true;
                placed += region.getPlacedBlocks();
            }
        }

        final String regionText = Integer.toString(regionCount);
        texts.drawString(regionText, regionColumnRight - getStringWidth(regionText), textY,
                regionCount == 0 ? 0x80FFFFFF : 0xFFFFFFFF);

        final String claimedText = claimedCount + "/" + regionCount;
        texts.drawString(claimedText, claimedColumnRight - getStringWidth(claimedText), textY,
                claimedColor(regionCount, claimedCount));

        final String progressText = formatProgress(anyScanned, required, placed);
        texts.drawString(progressText, progressColumnRight - getStringWidth(progressText), textY,
                progressColor(anyScanned, required, placed));
    }

    private static String formatProgress(final boolean anyScanned, final long required, final long placed) {
        if (!anyScanned) {
            return StringUtils.translate("cytra-syncmatica.gui.label.build.status.unknown");
        }
        if (placed >= required) {
            return StringUtils.translate("cytra-syncmatica.gui.label.build.status.complete");
        }
        return StringUtils.translate("cytra-syncmatica.gui.label.build.status.percent", percentOf(required, placed));
    }

    private static int progressColor(final boolean anyScanned, final long required, final long placed) {
        if (!anyScanned) {
            return 0x80FFFFFF;
        }
        return placed >= required ? 0xFF80FF80 : 0xFFFFFF80;
    }

    private static int percentOf(final long required, final long placed) {
        if (required <= 0L) {
            return 100;
        }
        return (int) Math.min(100L, placed * 100L / required);
    }

    private static int claimedColor(final int regionCount, final int claimedCount) {
        if (regionCount == 0) {
            return 0x80FFFFFF;
        }
        return claimedCount == regionCount ? 0xFF80FF80 : 0xFFFFFF80;
    }

    @Override
    protected boolean onMouseClickedImpl(final Click click, final boolean isLeftClick) {
        return mouseClickedImpl((int) click.x(), (int) click.y(), click.button());
    }

    public boolean mouseClicked(final int mouseX, final int mouseY, final int mouseButton) {
        return mouseClickedImpl(mouseX, mouseY, mouseButton);
    }

    protected boolean mouseClickedImpl(final int mouseX, final int mouseY, final int mouseButton) {
        if (mouseButton != 0 || !isMouseOver(mouseX, mouseY) || getEntry() == null) {
            return false;
        }
        final GuiBuildRegions gui = new GuiBuildRegions(getEntry());
        gui.setParent(parent);
        GuiBase.openGui(gui);
        return true;
    }

    private interface RectDrawer {
        void drawRect(int x, int y, int width, int height, int color);
    }

    private interface TextDrawer {
        void drawString(String text, int x, int y, int color);
    }
}
