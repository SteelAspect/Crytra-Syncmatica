package com.steelaspect.cytrasyncmatica.client.network;

import com.steelaspect.cytrasyncmatica.communication.CommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import com.steelaspect.cytrasyncmatica.communication.FeatureSet;
import com.steelaspect.cytrasyncmatica.communication.MessageCodec;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialWire;
import com.steelaspect.cytrasyncmatica.Feature;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.exchange.DownloadExchange;
import com.steelaspect.cytrasyncmatica.communication.exchange.Exchange;
import com.steelaspect.cytrasyncmatica.communication.MessageType;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import com.steelaspect.cytrasyncmatica.litematica.ScreenHelper;
import com.steelaspect.cytrasyncmatica.mixin_actor.ActorClientPlayNetworkHandler;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

public class ClientCommunicationManager extends CommunicationManager {

    private final ExchangeTarget server;
    private final Collection<ServerPlacement> sharing;

    public ClientCommunicationManager(final ExchangeTarget server) {
        super();
        this.server = server;
        broadcastTargets.add(server);
        sharing = new HashSet<>();
    }

    public ExchangeTarget getServer() {
        return server;
    }

    @Override
    protected void handle(final ExchangeTarget source, final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REGISTER_METADATA) {
            final ServerPlacement placement = receiveMetaData(packetBuf, source);
            if (context.getSyncmaticManager().getPlacement(placement.getId()) == null
                    && context.getSyncmaticManager().getAll().size() >= ProtocolLimits.MAX_SERVER_PLACEMENTS) {
                return;
            }
            context.getSyncmaticManager().addPlacement(placement);
            return;
        }
        if (type == PacketType.REMOVE_SYNCMATIC) {
            final UUID placementId = packetBuf.readUuid();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
            if (placement != null) {
                final Exchange modifier = getModifier(placement);
                if (modifier != null) {
                    modifier.close(false);
                    notifyClose(modifier);
                }
                context.getSyncmaticManager().removePlacement(placement);
                if (LitematicManager.getInstance().isRendered(placement)) {
                    LitematicManager.getInstance().unrenderSyncmatic(placement);
                }
            }
            return;
        }
        if (type == PacketType.MODIFY) {
            final UUID placementId = packetBuf.readUuid();
            final ServerPlacement toModify = context.getSyncmaticManager().getPlacement(placementId);
            if (getModifier(toModify) != null) {
                // A local modification session owns this placement's pose until it
                // concludes. The server still holds the pre-modification origin,
                // so applying its broadcast mid-edit would snap the placement back.
                return;
            }
            receiveModificationData(toModify, packetBuf, source);
            final FeatureSet featureSet = source.getFeatureSet();
            final boolean hasCoreEx = featureSet != null && featureSet.hasFeature(Feature.CORE_EX);
            final boolean hasTimestamps = hasCoreEx && supportsTimestamps(source);
            if (hasCoreEx) {
                final PlayerIdentifier lastModifiedBy = context.getPlayerIdentifierProvider().createOrGet(
                        packetBuf.readUuid(),
                        packetBuf.readString(ProtocolLimits.MAX_PLAYER_NAME_LENGTH)
                );
                if (toModify != null) {
                    toModify.setLastModifiedBy(lastModifiedBy);
                }
                if (hasTimestamps && packetBuf.readableBytes() >= Long.BYTES) {
                    final long ts = packetBuf.readLong();
                    if (toModify != null) {
                        toModify.setLastModifiedAtMillis(ts);
                    }
                } else if (hasTimestamps && packetBuf.readableBytes() > 0) {
                    packetBuf.skipBytes(Math.min(packetBuf.readableBytes(), Long.BYTES));
                }
            }
            if (toModify != null) {
                LitematicManager.getInstance().updateRendered(toModify);
                context.getSyncmaticManager().updateServerPlacement(toModify);
            } else {

            }
            return;
        }
        if (type == PacketType.MESSAGE) {
            final Message.MessageType guiType = mapMessageType(MessageCodec.readType(packetBuf));
            final String text = MessageCodec.readIdentifier(packetBuf);
            final String detail = MessageCodec.readDetail(packetBuf);
            if (detail.isEmpty()) {
                ScreenHelper.ifPresent(s -> s.addMessage(guiType, text));
            } else if (StringUtils.translate(text).split("%s", -1).length == 2) {
                // The server packs the message value(s) into one detail string,
                // so a single-placeholder key gets its value formatted in instead
                // of appended in parentheses after a raw "%s".
                ScreenHelper.ifPresent(s -> s.addMessage(guiType, text, detail));
            } else {
                ScreenHelper.ifPresent(s -> s.addMessage(
                        guiType,
                        "cytra-syncmatica.message.detail_format",
                        StringUtils.translate(text),
                        detail
                ));
            }
            return;
        }
        if (type == PacketType.MATERIAL_LIST) {
            final UUID placementId = packetBuf.readUuid();
            final String error = packetBuf.readString(ProtocolLimits.MAX_MESSAGE_LENGTH);
            final MaterialList list = MaterialWire.readList(packetBuf);
            MaterialTrackerClient.getInstance().onServerList(placementId, list, error);
            return;
        }
        if (type == PacketType.MATERIAL_UPDATE) {
            final UUID placementId = packetBuf.readUuid();
            MaterialTrackerClient.getInstance().onServerUpdate(placementId, MaterialWire.readEntry(packetBuf));
            return;
        }
        if (type == PacketType.PROJECT_LIST) {
            final int n = ProtocolLimits.requireCount(packetBuf.readVarInt(), com.steelaspect.cytrasyncmatica.projects.Project.MAX_PROJECTS, "project count");
            final java.util.List<com.steelaspect.cytrasyncmatica.projects.Project> projects = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                projects.add(com.steelaspect.cytrasyncmatica.projects.Project.read(packetBuf));
            }
            com.steelaspect.cytrasyncmatica.client.materials.ClientProjects.getInstance().onServerList(projects);
            return;
        }
        if (type == PacketType.REGISTER_VERSION) {
            LitematicManager.clear();
            Syncmatica.restartClient();
            ActorClientPlayNetworkHandler.getInstance().packetEvent(id, packetBuf);
            return;
        }
    }

    @Override
    protected void handleExchange(final Exchange exchange) {
        if (exchange instanceof DownloadExchange && exchange.isSuccessful()) {
            LitematicManager.getInstance().renderSyncmatic(((DownloadExchange) exchange).getPlacement());
        }
    }

    @Override
    public void setDownloadState(final ServerPlacement syncmatic, final boolean state) {
        downloadState.put(syncmatic.getHash(), state);
        if (state || LitematicManager.getInstance().isRendered(syncmatic)) {
            context.getSyncmaticManager().updateServerPlacement(syncmatic);
        }
    }

    public void setSharingState(final ServerPlacement placement, final boolean state) {
        if (state) {
            sharing.add(placement);
        } else {
            sharing.remove(placement);
        }
    }

    public boolean getSharingState(final ServerPlacement placement) {
        return sharing.contains(placement);
    }

    @Override
    public void setContext(final Context con) {
        super.setContext(con);
        final VersionHandshakeClient hi = new VersionHandshakeClient(server, context);
        startExchangeUnchecked(hi);
    }

    private Message.MessageType mapMessageType(final MessageType m) {
        switch (m) {
            case SUCCESS:
                return Message.MessageType.SUCCESS;
            case WARNING:
                return Message.MessageType.WARNING;
            case ERROR:
                return Message.MessageType.ERROR;
            default:
                return Message.MessageType.INFO;
        }
    }
}
