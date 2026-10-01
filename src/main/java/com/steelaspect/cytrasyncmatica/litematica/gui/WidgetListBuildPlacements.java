package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import com.steelaspect.cytrasyncmatica.util.NaturalOrderComparator;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.util.math.MatrixStack;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class WidgetListBuildPlacements extends WidgetListBase<ServerPlacement, WidgetBuildPlacementEntry> {

    private static final int HEADER_HEIGHT = 18;

    private final GuiBuildManagement parent;

    public WidgetListBuildPlacements(final int x, final int y, final int width, final int height,
                                     final GuiBuildManagement parent) {
        super(x, y, width, height, null);
        browserEntryHeight = 20;
        browserEntryWidth = width - 8;
        browserEntriesOffsetY = HEADER_HEIGHT;
        this.parent = parent;
    }

    @Override
    public void setSize(final int width, final int height) {
        super.setSize(width, height);
        browserEntryWidth = width - 8;
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        RenderUtils.drawRect(guiContext, posX, posY, browserWidth, browserEntriesOffsetY, 0x30000000);
        drawHeaderRow((text, tx, ty, color) -> drawString(guiContext, text, tx, ty, color));
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);
    }

    /**
     * Column offsets mirror {@link WidgetBuildPlacementEntry} so the header stays
     * lined up with the rows underneath it.
     */
    private void drawHeaderRow(final TextDrawer drawer) {
        final int textColor = 0xFFFFFFFF;
        final int textY = posY + 6;
        final int progressColumnRight = posX + browserEntryWidth - 8;
        final int claimedColumnRight = progressColumnRight - WidgetBuildPlacementEntry.PROGRESS_COLUMN_WIDTH;
        final int regionColumnRight = claimedColumnRight - WidgetBuildPlacementEntry.CLAIMED_COLUMN_WIDTH;

        drawer.drawString(StringUtils.translate("cytra-syncmatica.gui.label.build.column.schematic"),
                posX + 6, textY, textColor);
        final String regionLabel = StringUtils.translate("cytra-syncmatica.gui.label.build.column.regions");
        drawer.drawString(regionLabel, regionColumnRight - getStringWidth(regionLabel), textY, textColor);
        final String claimedLabel = StringUtils.translate("cytra-syncmatica.gui.label.build.column.claimed");
        drawer.drawString(claimedLabel, claimedColumnRight - getStringWidth(claimedLabel), textY, textColor);
        final String progressLabel = StringUtils.translate("cytra-syncmatica.gui.label.build.column.progress");
        drawer.drawString(progressLabel, progressColumnRight - getStringWidth(progressLabel), textY, textColor);
    }

    private interface TextDrawer {
        void drawString(String text, int x, int y, int color);
    }

    @Override
    protected Collection<ServerPlacement> getAllEntries() {
        final Context context = LitematicManager.getInstance().getActiveContext();
        if (context == null || context.getSyncmaticManager() == null) {
            return Collections.emptyList();
        }
        final List<ServerPlacement> snapshot = new ArrayList<>(context.getSyncmaticManager().getAll());
        snapshot.sort((left, right) -> NaturalOrderComparator.INSTANCE.compare(left.getName(), right.getName()));
        return snapshot;
    }

    @Override
    protected WidgetBuildPlacementEntry createListEntryWidget(final int x, final int y, final int listIndex,
                                                              final boolean isOdd, final ServerPlacement entry) {
        return new WidgetBuildPlacementEntry(x, y, browserEntryWidth, browserEntryHeight, entry, listIndex, parent);
    }

    @Override
    protected List<String> getEntryStringsForFilter(final ServerPlacement entry) {
        final List<String> filter = new ArrayList<>(1);
        filter.add(entry.getName().toLowerCase());
        return filter;
    }
}
