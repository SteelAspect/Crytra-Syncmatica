package com.steelaspect.cytrasyncmatica.litematica_mixin;

import com.steelaspect.cytrasyncmatica.client.gui.GuiMaterialTracker;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Team tracker" button to Litematica's own material list screen. */
@Mixin(GuiMaterialList.class)
public abstract class MixinGuiMaterialList extends GuiBase {

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    private void cytraSyncmatica$addTrackerButton(final CallbackInfo ci) {
        final String label = StringUtils.translate("cytra-syncmatica.gui.button.open_tracker");
        final int w = getStringWidth(label) + 10;
        final ButtonGeneric button = new ButtonGeneric(width - w - 12, 24, w, 20, label);
        addButton(button, (b, m) -> {
            final MaterialListBase list = ((GuiMaterialList) (Object) this).getMaterialList();
            TrackedSchematic target = null;
            if (list instanceof MaterialListPlacement && MaterialTrackerClient.getInstance().currentMode().isLocal()) {
                for (final TrackedSchematic t : MaterialTrackerClient.getInstance().availableSchematics()) {
                    if (t.local() != null && t.local().getMaterialList() == list) {
                        target = t;
                        break;
                    }
                }
            }
            final GuiMaterialTracker gui = new GuiMaterialTracker(target);
            gui.setParent(this);
            GuiBase.openGui(gui);
        });
    }
}
