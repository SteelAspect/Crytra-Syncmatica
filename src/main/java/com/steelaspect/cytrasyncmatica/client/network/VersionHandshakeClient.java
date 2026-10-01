package com.steelaspect.cytrasyncmatica.client.network;

import com.steelaspect.cytrasyncmatica.communication.ProtocolFlavor;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.FeatureSet;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import com.steelaspect.cytrasyncmatica.communication.exchange.FeatureExchange;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;

public class VersionHandshakeClient extends FeatureExchange {

    private String partnerVersion;

    public VersionHandshakeClient(final ExchangeTarget partner, final Context con) {
        super(partner, con);
    }

    @Override
    public boolean checkPacket(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        return type == PacketType.CONFIRM_USER
                || type == PacketType.REGISTER_VERSION
                || super.checkPacket(id, packetBuf);
    }

    @Override
    public void handle(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REGISTER_VERSION) {
            // Announce Reforged capability on the new namespace as early as possible.
            final PacketByteBuf revolutionBuf = new PacketByteBuf(Unpooled.buffer());
            getPartner().sendPacket(
                    PacketType.REVOLUTION.toIdentifier(
                            com.steelaspect.cytrasyncmatica.communication.ProtocolFlavor.NEW
                    ),
                    revolutionBuf,
                    getContext()
            );
            final String version = packetBuf.readString(ProtocolLimits.MAX_VERSION_LENGTH);
            if (!getContext().checkPartnerVersion(version)) {

                LogManager.getLogger(VersionHandshakeClient.class).info("Denying cytra-syncmatica join due to outdated server with local version {} and server version {}", Syncmatica.getVersion(), version);
                close(false);
            } else {
                partnerVersion = version;
                final FeatureSet fs = FeatureSet.fromVersionString(version);
                if (fs == null) {
                    requestFeatureSet();
                } else {
                    getPartner().setFeatureSet(fs);
                    onFeatureSetReceive();
                }
            }
        } else if (type == PacketType.CONFIRM_USER) {
            final int placementCount = ProtocolLimits.requireCount(
                    packetBuf.readInt(),
                    ProtocolLimits.MAX_SERVER_PLACEMENTS,
                    "placement count"
            );
            for (int i = 0; i < placementCount; i++) {
                final ServerPlacement p = getManager().receiveMetaData(packetBuf, getPartner());
                getContext().getSyncmaticManager().addPlacement(p);
            }
             LogManager.getLogger(VersionHandshakeClient.class).info("Joining cytra-syncmatica server with local version {} and server version {}", Syncmatica.getVersion(), partnerVersion);
            LitematicManager.getInstance().commitLoad();
            getContext().startup();
            succeed();
        } else {
            super.handle(id, packetBuf);
        }
    }

    @Override
    public void onFeatureSetReceive() {
        final PacketByteBuf versionBuf = new PacketByteBuf(Unpooled.buffer());
        versionBuf.writeString(Syncmatica.getVersion(), ProtocolLimits.MAX_VERSION_LENGTH);
        // Reply on legacy Syncmatica channel for maximum compatibility.
        getPartner().sendPacket(
                PacketType.REGISTER_VERSION.toIdentifier(
                        com.steelaspect.cytrasyncmatica.communication.ProtocolFlavor.LEGACY
                ),
                versionBuf,
                getContext()
        );
    }

    @Override
    public void init() {

    }

}
