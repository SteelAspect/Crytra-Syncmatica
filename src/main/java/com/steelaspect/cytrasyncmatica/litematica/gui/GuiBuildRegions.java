package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.build_management.BuildRegion;
import com.steelaspect.cytrasyncmatica.client.BuildVisibilityPreferences;
import com.steelaspect.cytrasyncmatica.client.BuildWarningPreferences;
import com.steelaspect.cytrasyncmatica.litematica.ClaimedRegionVisibility;
import com.steelaspect.cytrasyncmatica.litematica.ScreenHelper;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.function.BooleanSupplier;

/**
 * The sub-regions of one shared schematic, so players can divide the build
 * between themselves.
 */
public class GuiBuildRegions extends GuiListBase<BuildRegion, WidgetBuildRegionEntry, WidgetListBuildRegions> {

    /** Leaves room for the title (y = 10) above the control bar, as on the material screen. */
    private static final int TOP_BAR_Y = 22;
    private static final int TOP_BAR_HEIGHT = 46;

    private final ServerPlacement placement;
    private boolean emptyReported;

    public GuiBuildRegions(final ServerPlacement placement) {
        super(12, TOP_BAR_HEIGHT);
        this.placement = placement;
        title = StringUtils.translate("cytra-syncmatica.gui.title.build_management") + ": " + placement.getName();
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

        addClientPreferenceBar();
        reportEmptyRegionList();
    }

    /**
     * Both switches are the player's own rather than the server's, and both act
     * on the claims listed below them, so they belong on this screen instead of
     * only in a config file.
     */
    private void addClientPreferenceBar() {
        int x = 10;
        x += addToggleButton(x, "cytra-syncmatica.gui.button.build.follow_claims",
                BuildVisibilityPreferences::isFollowClaimsEnabled,
                () -> {
                    BuildVisibilityPreferences.setFollowClaimsEnabled(
                            !BuildVisibilityPreferences.isFollowClaimsEnabled());
                    ClaimedRegionVisibility.getInstance().refresh();
                }) + 6;
        addToggleButton(x, "cytra-syncmatica.gui.button.build.warn_foreign",
                BuildWarningPreferences::isEnabled,
                () -> BuildWarningPreferences.setEnabled(!BuildWarningPreferences.isEnabled()));
    }

    /**
     * @return the width the button took, so the next one can start after it
     */
    private int addToggleButton(final int x, final String labelKey,
                                final BooleanSupplier state, final Runnable onToggle) {
        // Sized for both labels so the button does not resize as it is clicked.
        final int buttonWidth = Math.max(
                getStringWidth(toggleLabel(labelKey, true)),
                getStringWidth(toggleLabel(labelKey, false))) + 20;
        final ButtonGeneric button =
                new ButtonGeneric(x, TOP_BAR_Y, buttonWidth, 20, toggleLabel(labelKey, state.getAsBoolean()));
        addButton(button, (clicked, mouseButton) -> {
            onToggle.run();
            clicked.setDisplayString(toggleLabel(labelKey, state.getAsBoolean()));
        });
        return buttonWidth;
    }

    private static String toggleLabel(final String labelKey, final boolean enabled) {
        final String state = StringUtils.translate(enabled
                ? "cytra-syncmatica.gui.label.toggle_on"
                : "cytra-syncmatica.gui.label.toggle_off");
        return StringUtils.translate(labelKey, state);
    }

    /**
     * An empty list looks the same whether the schematic has no regions or the
     * server never sent any, so say once that nothing is claimable yet.
     */
    private void reportEmptyRegionList() {
        if (emptyReported || !placement.getBuildRegions().isEmpty()) {
            return;
        }
        emptyReported = true;
        addMessage(Message.MessageType.INFO, "cytra-syncmatica.gui.label.build.empty");
    }

    @Override
    protected WidgetListBuildRegions createListWidget(final int listX, final int listY) {
        return new WidgetListBuildRegions(listX, listY, getBrowserWidth(), getBrowserHeight(), placement);
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
