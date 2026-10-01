package com.steelaspect.cytrasyncmatica.client.network;

import com.steelaspect.cytrasyncmatica.communication.MessageType;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.RedirectFileStorage;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.communication.exchange.AbstractExchange;
import com.steelaspect.cytrasyncmatica.communication.exchange.UploadExchange;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import com.steelaspect.cytrasyncmatica.litematica.ScreenHelper;
import com.steelaspect.cytrasyncmatica.util.SyncmaticaUtil;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.Message;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class ShareLitematicExchange extends AbstractExchange {

    private static final Logger LOGGER = LogManager.getLogger(ShareLitematicExchange.class);

    private final SchematicPlacement schematicPlacement;
    private final ServerPlacement toShare;
    private final File toUpload;

    public ShareLitematicExchange(final SchematicPlacement schematicPlacement, final ExchangeTarget partner, final Context con) {
        this(schematicPlacement, partner, con, null);
    }

    public ShareLitematicExchange(final SchematicPlacement schematicPlacement, final ExchangeTarget partner, final Context con, final ServerPlacement p) {
        super(partner, con);
        this.schematicPlacement = schematicPlacement;
        toShare = p == null ? LitematicManager.getInstance().syncmaticFromSchematic(schematicPlacement) : p;
        final Path schematicPath = schematicPlacement.getSchematicFile();
        toUpload = schematicPath != null ? schematicPath.toFile() : null;
    }

    @Override
    public boolean checkPacket(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REQUEST_LITEMATIC
                || type == PacketType.REGISTER_METADATA
                || type == PacketType.CANCEL_SHARE) {
            return AbstractExchange.checkUUID(packetBuf, toShare.getId());
        }
        return false;
    }

    @Override
    public void handle(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REQUEST_LITEMATIC) {
            packetBuf.readUuid();
            final UploadExchange upload;
            try {
                upload = new UploadExchange(toShare, toUpload, getPartner(), getContext());
            } catch (final UploadExchange.TransferLimitExceededException tooLarge) {
                LOGGER.warn("Aborting share of '{}': {}", toShare.getName(), tooLarge.getMessage());
                showError("cytra-syncmatica.error.share_exceeds_size_limit",
                        SyncmaticaUtil.formatMegabytes(tooLarge.getFileBytes()),
                        SyncmaticaUtil.formatMegabytes(tooLarge.getLimitBytes()));
                close(false);
                return;
            } catch (final IOException e) {
                LOGGER.warn("Aborting share of '{}': litematic file is unavailable", toShare.getName(), e);
                showError("cytra-syncmatica.error.file_unavailable");
                close(false);
                return;
            }
            getManager().startExchange(upload);
            return;
        }
        if (type == PacketType.REGISTER_METADATA) {
            final RedirectFileStorage redirect = (RedirectFileStorage) getContext().getFileStorage();
            redirect.addRedirect(toUpload);
            LitematicManager.getInstance().renderSyncmatic(toShare, schematicPlacement, false);
            getContext().getSyncmaticManager().addPlacement(toShare);
            return;
        }
        if (type == PacketType.CANCEL_SHARE) {
            close(false);
        }
    }

    @Override
    public void init() {
        if (toShare == null) {
            close(false);
            return;
        }
        ((ClientCommunicationManager) getManager()).setSharingState(toShare, true);
        getContext().getSyncmaticManager().updateServerPlacement(toShare);
        getManager().sendMetaData(toShare, getPartner());
    }

    @Override
    public void onClose() {
        ((ClientCommunicationManager) getManager()).setSharingState(toShare, false);
    }

    private static void showError(final String messageKey, final Object... args) {
        ScreenHelper.ifPresent(s -> s.addMessage(Message.MessageType.ERROR, messageKey, args));
    }
}
