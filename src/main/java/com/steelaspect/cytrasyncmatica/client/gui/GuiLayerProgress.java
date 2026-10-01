package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.layers.LayerProgressClient;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Build progress per layer; click a row to show that layer in Litematica. */
public class GuiLayerProgress extends GuiListBase<LayerProgressClient.Layer, GuiLayerProgress.LayerWidget, GuiLayerProgress.LayerList> {
    private static final int TOP_BAR_Y = 20;
    private static final int LIST_TOP = 60;

    private TrackedSchematic schematic;

    public GuiLayerProgress(final TrackedSchematic schematic) {
        super(12, LIST_TOP);
        this.schematic = schematic != null ? schematic : MaterialTrackerClient.getInstance().defaultSchematic();
        if (this.schematic != null && this.schematic.isProject()) {
            final List<TrackedSchematic> members = MaterialTrackerClient.getInstance().members(this.schematic.project());
            this.schematic = members.isEmpty() ? null : members.get(0);
        }
        LayerProgressClient.getInstance().setTarget(this.schematic);
        updateTitle();
    }

    private void updateTitle() {
        title = StringUtils.translate("cytra-syncmatica.gui.title.layer_progress") + (schematic == null ? "" : ": " + schematic.name());
    }

    @Override
    public void initGui() {
        super.initGui();
        int x = 10;
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.schematic_select"), (b, m) -> {
            final List<TrackedSchematic> candidates = MaterialTrackerClient.getInstance().schematicsOnly();
            final GuiSchematicSelect gui = new GuiSchematicSelect(candidates, StringUtils.translate("cytra-syncmatica.gui.title.schematic_select"), selected -> {
                schematic = selected;
                LayerProgressClient.getInstance().setTarget(selected);
                updateTitle();
            });
            gui.setParent(this);
            openGui(gui);
        });
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.all_layers"), (b, m) -> LayerProgressClient.getInstance().showAllLayers());
        x = addTopButton(x, followLabel(), (b, m) -> {
            LayerProgressClient.getInstance().setFollowPlayer(!LayerProgressClient.getInstance().isFollowPlayer());
            b.setDisplayString(followLabel());
        });
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.next_incomplete"), (b, m) -> {
            for (final LayerProgressClient.Layer l : LayerProgressClient.getInstance().layers()) {
                if (!l.complete()) {
                    LayerProgressClient.getInstance().showLayer(l.y());
                    break;
                }
            }
        });
        final String back = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int backWidth = getStringWidth(back) + 20;
        addButton(new ButtonGeneric(width - backWidth - 10, TOP_BAR_Y, backWidth, 20, back), (b, m) -> closeGui(true));
    }

    private int addTopButton(final int x, final String label, final fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        final int w = getStringWidth(label) + 14;
        addButton(new ButtonGeneric(x, TOP_BAR_Y, w, 20, label), listener);
        return x + w + 4;
    }

    private String followLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.follow_player",
                StringUtils.translate(LayerProgressClient.getInstance().isFollowPlayer() ? "cytra-syncmatica.gui.label.toggle_on" : "cytra-syncmatica.gui.label.toggle_off"));
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);
        final LayerProgressClient layers = LayerProgressClient.getInstance();
        final String status;
        if (schematic == null) {
            status = StringUtils.translate("cytra-syncmatica.gui.label.no_schematics");
        } else if (!layers.hasData()) {
            status = StringUtils.translate("cytra-syncmatica.gui.label.layers_scanning");
        } else {
            final long[] t = layers.totals();
            status = StringUtils.translate("cytra-syncmatica.gui.label.layers_summary", layers.completeLayers(), layers.layers().size(),
                    t[0] == 0 ? 100.0 : 100.0 * t[1] / t[0], t[1], t[0], t[2]);
        }
        drawStringWithShadow(guiContext, status, 10, 46, 0xFFC0C0C0);
    }

    @Override
    protected LayerList createListWidget(final int listX, final int listY) {
        return new LayerList(listX, listY, getBrowserWidth(), getBrowserHeight());
    }

    @Override
    protected int getBrowserHeight() {
        return height - LIST_TOP - 10;
    }

    @Override
    protected int getBrowserWidth() {
        return width - 20;
    }

    public static class LayerList extends WidgetListBase<LayerProgressClient.Layer, LayerWidget> {
        private int refreshTicks;

        LayerList(final int x, final int y, final int width, final int height) {
            super(x, y, width, height, null);
            browserEntryHeight = 16;
            browserEntriesOffsetY = 2;
            browserEntryWidth = width - 8;
        }

        @Override
        public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
            if (++refreshTicks >= 40) {
                refreshTicks = 0;
                refreshEntries();
            }
            super.drawContents(guiContext, mouseX, mouseY, partialTicks);
        }

        @Override
        protected Collection<LayerProgressClient.Layer> getAllEntries() {
            final List<LayerProgressClient.Layer> out = new ArrayList<>(LayerProgressClient.getInstance().layers());
            java.util.Collections.reverse(out); // top layer first, like a build
            return out;
        }

        @Override
        protected LayerWidget createListEntryWidget(final int x, final int y, final int listIndex, final boolean isOdd, final LayerProgressClient.Layer entry) {
            return new LayerWidget(x, y, browserEntryWidth, browserEntryHeight, entry, listIndex);
        }

        @Override
        protected List<String> getEntryStringsForFilter(final LayerProgressClient.Layer entry) {
            return List.of(Integer.toString(entry.y()));
        }
    }

    public static class LayerWidget extends WidgetListEntryBase<LayerProgressClient.Layer> {
        private final LayerProgressClient.Layer layer;
        private final boolean odd;

        LayerWidget(final int x, final int y, final int width, final int height, final LayerProgressClient.Layer layer, final int listIndex) {
            super(x, y, width, height, layer, listIndex);
            this.layer = layer;
            odd = listIndex % 2 == 1;
        }

        @Override
        protected boolean onMouseClickedImpl(final net.minecraft.client.gui.Click click, final boolean doubleClick) {
            LayerProgressClient.getInstance().showLayer(layer.y());
            return true;
        }

        @Override
        public void render(final GuiContext guiContext, final int mouseX, final int mouseY, final boolean selected) {
            final boolean current = LayerProgressClient.getInstance().currentLayer() == layer.y();
            RenderUtils.drawRect(guiContext, x, y, width, height, layer.complete() ? 0x3040C040 : odd ? 0x20FFFFFF : 0x40FFFFFF);
            if (current) {
                RenderUtils.drawOutline(guiContext, x, y, width, height, 0xFFFFD080);
            }
            drawString(guiContext, x + 4, y + 4, 0xFFFFFFFF, "Y " + layer.y());
            final int barX = x + 60;
            final int barW = Math.max(40, width / 2);
            RenderUtils.drawRect(guiContext, barX, y + 4, barW, 8, 0x60000000);
            RenderUtils.drawRect(guiContext, barX, y + 4, (int) (barW * layer.percent() / 100.0), 8, layer.complete() ? 0xFF60D060 : 0xFFE0A040);
            final String text = String.format(java.util.Locale.ROOT, "%.0f%%  %d / %d%s", layer.percent(), layer.placed(), layer.expected(),
                    layer.unknown() > 0 ? "  (" + layer.unknown() + " unloaded)" : "");
            drawString(guiContext, x + width - 4 - getStringWidth(text), y + 4, layer.complete() ? 0xFF80FF80 : 0xFFFFC080, text);
        }
    }
}
