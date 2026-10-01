package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.Feature;
import com.steelaspect.cytrasyncmatica.client.network.ClientCommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.client.network.ShareLitematicExchange;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;

public class ButtonListenerShare implements IButtonActionListener {

    private final SchematicPlacement schematicPlacement;
    private final GuiBase messageDisplay;

    public ButtonListenerShare(final SchematicPlacement placement, final GuiBase messageDisplay) {
        schematicPlacement = placement;
        this.messageDisplay = messageDisplay;
    }

    @Override
    public void actionPerformedWithButton(final ButtonBase button, final int mouseButton) {
        if (LitematicManager.getInstance().isSyncmatic(schematicPlacement)) {
            return;
        }
        if (!GuiBase.isShiftDown()) {
            messageDisplay.addMessage(Message.MessageType.ERROR, "cytra-syncmatica.error.share_without_shift");
            return;
        }
        button.setEnabled(false);
        final Context con = LitematicManager.getInstance().getActiveContext();
        final ExchangeTarget server = ((ClientCommunicationManager) con.getCommunicationManager()).getServer();
        if (!server.getFeatureSet().hasFeature(Feature.CORE_EX) && schematicPlacement.isRegionPlacementModified()) {
            messageDisplay.addMessage(Message.MessageType.ERROR, "cytra-syncmatica.error.share_modified_subregions");
            return;
        }
        final ShareLitematicExchange ex = new ShareLitematicExchange(schematicPlacement, server, con);
        con.getCommunicationManager().startExchange(ex);
    }

}
