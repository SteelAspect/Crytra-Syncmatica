package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.client.ClientConfigs;
import com.steelaspect.cytrasyncmatica.client.hotkey.SyncmaticaHotkeys;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.List;

public class GuiSyncmaticaSharedSettings extends GuiConfigsBase {

    private ConfigTab activeTab = ConfigTab.GENERAL;

    public GuiSyncmaticaSharedSettings() {
        super(10, 50, Syncmatica.MOD_ID, null, "cytra-syncmatica.gui.title.configs");
        useTitleHierarchy = false;
    }

    @Override
    public void initGui() {
        super.initGui();
        clearOptions();

        int x = 10;
        x += createTabButton(x, ConfigTab.GENERAL);
        createTabButton(x, ConfigTab.HOTKEYS);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        final List<? extends IConfigBase> configs = activeTab == ConfigTab.GENERAL
                ? ClientConfigs.General.OPTIONS
                : SyncmaticaHotkeys.getHotkeys();
        return ConfigOptionWrapper.createFor(configs);
    }

    @Override
    protected boolean useKeybindSearch() {
        return activeTab == ConfigTab.HOTKEYS;
    }

    private int createTabButton(final int x, final ConfigTab tab) {
        final ButtonGeneric button = new ButtonGeneric(x, 26, -1, 20, tab.getDisplayName());
        button.setEnabled(activeTab != tab);
        addButton(button, new TabButtonListener(tab));
        return button.getWidth() + 2;
    }

    private enum ConfigTab {
        GENERAL("cytra-syncmatica.gui.button.config.general"),
        HOTKEYS("cytra-syncmatica.gui.button.config.hotkeys");

        private final String translationKey;

        ConfigTab(final String translationKey) {
            this.translationKey = translationKey;
        }

        private String getDisplayName() {
            return StringUtils.translate(translationKey);
        }
    }

    private final class TabButtonListener implements IButtonActionListener {

        private final ConfigTab tab;

        private TabButtonListener(final ConfigTab tab) {
            this.tab = tab;
        }

        @Override
        public void actionPerformedWithButton(final ButtonBase button, final int mouseButton) {
            activeTab = tab;
            reCreateListWidget();
            initGui();
        }
    }
}
