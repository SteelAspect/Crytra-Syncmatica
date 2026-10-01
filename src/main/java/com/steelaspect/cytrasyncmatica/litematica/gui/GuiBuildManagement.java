package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.litematica.ScreenHelper;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * Entry point of build management: the shared schematics a server knows about,
 * with how much of each is already spoken for.
 *
 * <p>Picking one opens {@link GuiBuildRegions}. Splitting the two apart keeps a
 * server with many shared schematics readable, and mirrors how the material
 * screens are reached.
 */
public class GuiBuildManagement extends GuiListBase<ServerPlacement, WidgetBuildPlacementEntry, WidgetListBuildPlacements> {

    private static final int TOP_BAR_HEIGHT = 24;

    public GuiBuildManagement() {
        super(12, TOP_BAR_HEIGHT);
        title = StringUtils.translate("cytra-syncmatica.gui.title.build_management");
        ScreenHelper.ifPresent(helper -> helper.setCurrentGui(this));
    }

    @Override
    public void initGui() {
        super.initGui();
        final String closeLabel = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int closeWidth = getStringWidth(closeLabel) + 20;
        final ButtonGeneric closeButton =
                new ButtonGeneric(width - closeWidth - 10, height - 26, closeWidth, 20, closeLabel);
        addButton(closeButton, (button, mouseButton) -> closeGui(true));
    }

    @Override
    protected WidgetListBuildPlacements createListWidget(final int listX, final int listY) {
        return new WidgetListBuildPlacements(listX, listY, getBrowserWidth(), getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserHeight() {
        return height - TOP_BAR_HEIGHT - 30;
    }

    @Override
    protected int getBrowserWidth() {
        return width - 20;
    }
}
