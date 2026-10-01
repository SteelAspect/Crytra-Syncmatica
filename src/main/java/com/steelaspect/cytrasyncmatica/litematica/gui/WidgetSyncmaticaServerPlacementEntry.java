package com.steelaspect.cytrasyncmatica.litematica.gui;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.LocalLitematicState;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.client.network.ClientCommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import com.steelaspect.cytrasyncmatica.litematica.gui.BaseButtonType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.network.PacketByteBuf;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

public class WidgetSyncmaticaServerPlacementEntry extends WidgetListEntryBase<ServerPlacement> {

    private final ServerPlacement placement;
    private final boolean isOdd;

    public WidgetSyncmaticaServerPlacementEntry(final int x, int y, final int width, final int height, final ServerPlacement entry,
                                                final int listIndex) {
        super(x, y, width, height, entry, listIndex);
        placement = entry;
        isOdd = (listIndex % 2 == 1);
        y += 1;

        int posX = x + width;
        int len;
        ButtonListener listener;
        String text;

        text = StringUtils.translate("cytra-syncmatica.gui.button.remove");
        len = getStringWidth(text) + 10;
        posX -= (len + 2);
        listener = new ButtonListener(ButtonListener.Type.REMOVE, this);
        addButton(new ButtonGeneric(posX, y, len, 20, text), listener);

        final ArrayList<IButtonType> multi = new ArrayList<>();
        multi.add(new BaseButtonType("cytra-syncmatica.gui.button.downloading"
                , () -> LitematicManager.getInstance().getActiveContext().getCommunicationManager().getDownloadState(placement)
                , null));
        multi.add(new BaseButtonType("cytra-syncmatica.gui.button.download"
                , () -> {
            final Context con = LitematicManager.getInstance().getActiveContext();
            final LocalLitematicState state = con.getFileStorage().getLocalState(placement);
            return !state.isLocalFileReady() && state.isReadyForDownload();
        }, new ButtonListener(ButtonListener.Type.DOWNLOAD, this)));
        multi.add(new BaseButtonType("cytra-syncmatica.gui.button.load",
                () -> !LitematicManager.getInstance().isRendered(placement),
                new ButtonListener(ButtonListener.Type.LOAD, this)));
        multi.add(new BaseButtonType("cytra-syncmatica.gui.button.unload",
                () -> LitematicManager.getInstance().isRendered(placement),
                new ButtonListener(ButtonListener.Type.UNLOAD, this)));

        final ButtonGeneric button = new MultiTypeButton(posX, y, true, multi);
        addButton(button, null);
    }

    @Override
    public void render(final GuiContext guiContext, final int mouseX, final int mouseY, final boolean selected) {
        final DrawContext drawContext = guiContext;


        if (selected || isMouseOver(mouseX, mouseY)) {
            RenderUtils.drawRect(guiContext, x, y, width, height, 0x70FFFFFF);
        } else if (isOdd) {
            RenderUtils.drawRect(guiContext, x, y, width, height, 0x20FFFFFF);
        } else {
            RenderUtils.drawRect(guiContext, x, y, width, height, 0x50FFFFFF);
        }

        final String schematicName = placement.getName();
        drawString(guiContext, x + 20, y + 7, 0xFFFFFFFF, schematicName);
        drawSubWidgets(guiContext, mouseX, mouseY);
    }

    private static class ButtonListener implements IButtonActionListener {

        Type type;
        WidgetSyncmaticaServerPlacementEntry placement;

        public ButtonListener(final Type type, final WidgetSyncmaticaServerPlacementEntry placement) {
            this.type = type;
            this.placement = placement;
        }

        @Override
        public void actionPerformedWithButton(final ButtonBase button, final int arg1) {
            if (type == null) {
                return;
            }
            button.setEnabled(false);
            type.onAction(placement);
        }

        public enum Type {
            LOAD() {
                @Override
                void onAction(final WidgetSyncmaticaServerPlacementEntry placement) {
                    LitematicManager.getInstance().renderSyncmatic(placement.placement);
                }
            },
            UNLOAD() {
                @Override
                void onAction(final WidgetSyncmaticaServerPlacementEntry placement) {
                    LitematicManager.getInstance().unrenderSyncmatic(placement.placement);
                }
            },
            DOWNLOAD() {
                @Override
                void onAction(final WidgetSyncmaticaServerPlacementEntry placement) {
                    final Context con = LitematicManager.getInstance().getActiveContext();
                    final ExchangeTarget server = ((ClientCommunicationManager) con.getCommunicationManager()).getServer();
                    if (con.getCommunicationManager().getDownloadState(placement.placement)) {
                        return;
                    }
                    try {
                        con.getCommunicationManager().download(placement.placement, server);
                    } catch (final NoSuchAlgorithmException | IOException e) {
                        e.printStackTrace();
                    }
                }
            },
            REMOVE() {
                @Override
                void onAction(final WidgetSyncmaticaServerPlacementEntry placement) {
                    final Context con = LitematicManager.getInstance().getActiveContext();
                    final ExchangeTarget server = ((ClientCommunicationManager) con.getCommunicationManager()).getServer();
                    final PacketByteBuf packetBuf = new PacketByteBuf(Unpooled.buffer());
                    packetBuf.writeUuid(placement.placement.getId());
                    server.sendPacket(PacketType.REMOVE_SYNCMATIC.toIdentifier(server.getProtocolFlavor()), packetBuf, LitematicManager.getInstance().getActiveContext());
                }
            };

            abstract void onAction(WidgetSyncmaticaServerPlacementEntry placement);
        }

    }

}
