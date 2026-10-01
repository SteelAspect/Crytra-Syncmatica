package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerPreferences;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class WidgetListMaterialTracker extends WidgetListBase<MaterialEntry, WidgetMaterialTrackerEntry> {
    private final GuiMaterialTracker parent;

    public WidgetListMaterialTracker(final int x, final int y, final int width, final int height, final GuiMaterialTracker parent) {
        super(x, y, width, height, null);
        this.parent = parent;
        browserEntryHeight = WidgetMaterialTrackerEntry.HEIGHT;
        browserEntriesOffsetY = 14;
        browserEntryWidth = width - 8;
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        RenderUtils.drawRect(guiContext, posX, posY, browserWidth, browserEntriesOffsetY, 0x30000000);
        final int c = 0xFFFFFFFF;
        drawString(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.column.item"), posX + WidgetMaterialTrackerEntry.NAME_X, posY + 3, c);
        final int right = posX + browserEntryWidth - 4;
        drawRight(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.column.remaining"), right - WidgetMaterialTrackerEntry.REMAINING_RIGHT, posY + 3, c);
        drawRight(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.column.inventory"), right - WidgetMaterialTrackerEntry.INVENTORY_RIGHT, posY + 3, c);
        drawRight(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.column.gathered"), right - WidgetMaterialTrackerEntry.GATHERED_RIGHT, posY + 3, c);
        drawRight(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.column.required"), right - WidgetMaterialTrackerEntry.REQUIRED_RIGHT, posY + 3, c);
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);
    }

    private void drawRight(final GuiContext ctx, final String text, final int rightX, final int y, final int color) {
        drawString(ctx, text, rightX - getStringWidth(text), y, color);
    }

    @Override
    protected Collection<MaterialEntry> getAllEntries() {
        final MaterialList list = MaterialTrackerClient.getInstance().getList(parent.getSchematic());
        if (list == null) {
            return List.of();
        }
        final List<MaterialEntry> entries = new ArrayList<>(list.copyEntries());
        if (MaterialTrackerPreferences.isHideCompleted()) {
            entries.removeIf(MaterialEntry::isComplete);
        }
        final Comparator<MaterialEntry> byName = Comparator.comparing(e -> WidgetMaterialTrackerEntry.displayName(e.getItemId()), String.CASE_INSENSITIVE_ORDER);
        if (MaterialTrackerPreferences.getSortMode() == MaterialTrackerPreferences.SortMode.NAME) {
            entries.sort(byName);
        } else {
            entries.sort(Comparator.comparingInt(MaterialEntry::getRemaining).reversed().thenComparing(byName));
        }
        return entries;
    }

    @Override
    protected WidgetMaterialTrackerEntry createListEntryWidget(final int x, final int y, final int listIndex, final boolean isOdd, final MaterialEntry entry) {
        return new WidgetMaterialTrackerEntry(x, y, browserEntryWidth, browserEntryHeight, entry, listIndex, parent.getSchematic());
    }

    @Override
    protected List<String> getEntryStringsForFilter(final MaterialEntry entry) {
        final List<String> filter = new ArrayList<>(2);
        filter.add(entry.getItemId().toLowerCase());
        filter.add(WidgetMaterialTrackerEntry.displayName(entry.getItemId()).toLowerCase());
        return filter;
    }
}
