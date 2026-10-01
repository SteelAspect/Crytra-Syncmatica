package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.List;
import java.util.function.Consumer;

/** A simple list of buttons, one per schematic the tracker can show. */
public class GuiSchematicSelect extends GuiBase {
    private final Consumer<TrackedSchematic> onSelect;

    public GuiSchematicSelect(final Consumer<TrackedSchematic> onSelect) {
        this.onSelect = onSelect;
        title = StringUtils.translate("cytra-syncmatica.gui.title.schematic_select");
    }

    @Override
    public void initGui() {
        super.initGui();
        final List<TrackedSchematic> all = MaterialTrackerClient.getInstance().availableSchematics();
        int y = 30;
        int x = 10;
        final int w = Math.max(120, Math.min(width - 20, 260));
        for (final TrackedSchematic t : all) {
            if (y > height - 50) {
                y = 30;
                x += w + 6;
            }
            addButton(new ButtonGeneric(x, y, w, 20, t.name()), (b, m) -> {
                onSelect.accept(t);
                closeGui(true);
            });
            y += 22;
        }
        final String back = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int bw = getStringWidth(back) + 20;
        addButton(new ButtonGeneric(width - bw - 10, height - 26, bw, 20, back), (b, m) -> closeGui(true));
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        if (MaterialTrackerClient.getInstance().availableSchematics().isEmpty()) {
            drawStringWithShadow(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.no_schematics"), 10, 30, 0xFFFFFFFF);
        }
    }
}
