package com.steelaspect.cytrasyncmatica.litematica_mixin;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.litematica.gui.ButtonListenerChangeMenu;
import com.steelaspect.cytrasyncmatica.litematica.gui.MainMenuButtonType;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public class MixinGuiMainMenu extends GuiBase {

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    public void initGui(final CallbackInfo ci) {
        final int width = getButtonWidth();
        final int x = 52 + 2 * width;
        final String versionLabel =
                StringUtils.translate("cytra-syncmatica.gui.label.version", Syncmatica.getVersion());
        addWidget(new WidgetLabel(x, 10, width, 10, 0xFFFFFFFF, versionLabel));

        int y = 30;
        createChangeMenuButton(x, y, width, MainMenuButtonType.VIEW_SYNCMATICS);
        y += 22;
        createChangeMenuButton(x, y, width, MainMenuButtonType.BUILD_MANAGEMENT);
        y += 22;
        createChangeMenuButton(x, y, width, MainMenuButtonType.SHARED_SETTINGS);
    }

    private ButtonGeneric createChangeMenuButton(final int x, final int y, final int width, final MainMenuButtonType type) {
        final ButtonGeneric button = new ButtonGeneric(x, y, width, 20, type.getTranslatedKey(), type.getIcon());
        button.setEnabled(true);
        addButton(button, new ButtonListenerChangeMenu(type, this));
        return button;
    }

    private int getButtonWidth() {
        int width = 0;

        for (final MainMenuButtonType type : MainMenuButtonType.values()) {
            width = Math.max(width, getStringWidth(type.getTranslatedKey()) + 30);
        }

        for (final SelectionMode mode : SelectionMode.values()) {
            final String label = StringUtils.translate("litematica.gui.button.area_selection_mode", mode.getDisplayName());
            width = Math.max(width, getStringWidth(label) + 10);
        }

        return width;
    }
}
