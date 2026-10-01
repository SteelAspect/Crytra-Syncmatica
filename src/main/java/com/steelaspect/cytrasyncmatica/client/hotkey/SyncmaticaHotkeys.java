package com.steelaspect.cytrasyncmatica.client.hotkey;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central registry for all Syncmatica hotkeys using malilib's ConfigHotkey.
 */
public final class SyncmaticaHotkeys {

    /**
     * Custom keybind settings with orderSensitive=false to allow any key order in combinations.
     * This fixes the issue where CTRL+F wouldn't work because key press order was unpredictable.
     */
    private static final KeybindSettings HOTKEY_SETTINGS = KeybindSettings.create(
            KeybindSettings.Context.INGAME,
            KeyAction.PRESS,
            false,  // allowExtraKeys
            false,  // orderSensitive - allow any key order in combinations
            false,  // exclusive
            true    // cancel
    );

    /**
     * Hotkey to open the team material tracker.
     * Default is empty (unassigned).
     */
    public static final ConfigHotkey OPEN_MATERIAL_TRACKER = new ConfigHotkey(
            "openMaterialTracker",
            "",
            HOTKEY_SETTINGS,
            "cytra-syncmatica.hotkey.open_material_tracker.comment",
            "cytra-syncmatica.gui.label.hotkey.material_tracker"
    ) {
        @Override
        public String getConfigGuiDisplayName() {
            return getPrettyName();
        }
    };

    public static final ConfigHotkey TOGGLE_MATERIAL_HUD = new ConfigHotkey(
            "toggleMaterialHud",
            "",
            HOTKEY_SETTINGS,
            "cytra-syncmatica.hotkey.toggle_material_hud.comment",
            "cytra-syncmatica.gui.label.hotkey.material_hud"
    ) {
        @Override
        public String getConfigGuiDisplayName() {
            return getPrettyName();
        }
    };

    public static final ConfigHotkey OPEN_BUILD_MANAGEMENT = new ConfigHotkey(
            "openBuildManagement",
            "",
            HOTKEY_SETTINGS,
            "cytra-syncmatica.hotkey.open_build_management.comment",
            "cytra-syncmatica.gui.label.hotkey.build_management"
    ) {
        @Override
        public String getConfigGuiDisplayName() {
            return getPrettyName();
        }
    };

    public static final ConfigHotkey OPEN_LAYER_PROGRESS = new ConfigHotkey(
            "openLayerProgress",
            "",
            HOTKEY_SETTINGS,
            "cytra-syncmatica.hotkey.open_layer_progress.comment",
            "cytra-syncmatica.gui.label.hotkey.layer_progress"
    ) {
        @Override
        public String getConfigGuiDisplayName() {
            return getPrettyName();
        }
    };

    private static final List<ConfigHotkey> HOTKEYS = Collections.unmodifiableList(
            Arrays.asList(OPEN_MATERIAL_TRACKER, TOGGLE_MATERIAL_HUD, OPEN_BUILD_MANAGEMENT, OPEN_LAYER_PROGRESS)
    );

    private SyncmaticaHotkeys() {
        // Utility class, no instantiation
    }

    /**
     * Returns a list of all registered hotkeys.
     *
     * @return immutable list of all ConfigHotkey instances
     */
    public static List<ConfigHotkey> getHotkeys() {
        return HOTKEYS;
    }
}
