package com.steelaspect.cytrasyncmatica.client;

import com.steelaspect.cytrasyncmatica.client.hotkey.HotkeyCallbackOpenGui;
import com.steelaspect.cytrasyncmatica.client.hotkey.SyncmaticaHotkeyProvider;
import com.steelaspect.cytrasyncmatica.client.hotkey.SyncmaticaHotkeys;
import com.steelaspect.cytrasyncmatica.client.gui.GuiMaterialTracker;
import com.steelaspect.cytrasyncmatica.litematica.gui.GuiBuildManagement;
import com.steelaspect.cytrasyncmatica.litematica.gui.GuiSyncmaticaSharedSettings;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

/**
 * Initialization handler for Syncmatica client-side features.
 * Registers hotkeys with malilib at the correct initialization time.
 */
public final class SyncmaticaInitHandler implements IInitializationHandler {

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(
                com.steelaspect.cytrasyncmatica.Syncmatica.MOD_ID, ClientConfigs.INSTANCE);
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(
                com.steelaspect.cytrasyncmatica.Syncmatica.MOD_ID,
                "Cytra-Syncmatica",
                GuiSyncmaticaSharedSettings::new));

        // Register keybind provider with malilib
        SyncmaticaHotkeyProvider.init();

        // Set up hotkey callbacks
        SyncmaticaHotkeys.OPEN_BUILD_MANAGEMENT.getKeybind()
                .setCallback(new HotkeyCallbackOpenGui(GuiBuildManagement::new));
        SyncmaticaHotkeys.OPEN_MATERIAL_TRACKER.getKeybind()
                .setCallback(new HotkeyCallbackOpenGui(() -> new GuiMaterialTracker(null)));
        SyncmaticaHotkeys.TOGGLE_MATERIAL_HUD.getKeybind()
                .setCallback((action, key) -> {
                    if (action != fi.dy.masa.malilib.hotkeys.KeyAction.PRESS) {
                        return false;
                    }
                    ClientConfigs.General.HUD_ENABLED.setBooleanValue(!ClientConfigs.General.HUD_ENABLED.getBooleanValue());
                    return true;
                });
    }
}
