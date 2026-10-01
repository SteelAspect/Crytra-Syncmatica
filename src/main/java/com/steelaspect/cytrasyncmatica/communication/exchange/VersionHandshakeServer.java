package com.steelaspect.cytrasyncmatica.communication.exchange;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.FeatureSet;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.communication.ProtocolFlavor;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import com.steelaspect.cytrasyncmatica.communication.exchange.FeatureExchange;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;

import java.util.Collection;

public class VersionHandshakeServer extends FeatureExchange {

    private String partnerVersion;
    private boolean awaitingFeatureSet;

    public VersionHandshakeServer(final ExchangeTarget partner, final Context con) {
        super(partner, con);
    }

    @Override
    public boolean checkPacket(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REGISTER_VERSION
                || type == PacketType.REVOLUTION
                || type == PacketType.FEATURE_REQUEST) {
            return true;
        }
        return type == PacketType.FEATURE && partnerVersion != null && awaitingFeatureSet;
    }

    @Override
    public void handle(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.REGISTER_VERSION) {
            partnerVersion = packetBuf.readString(ProtocolLimits.MAX_VERSION_LENGTH);
            if (!getContext().checkPartnerVersion(partnerVersion)) {
                LogManager.getLogger(VersionHandshakeServer.class).info("Denying cytra-syncmatica join due to outdated client with local version {} and client version {}", Syncmatica.getVersion(), partnerVersion);

                close(false);
                return;
            }
            final FeatureSet fs = FeatureSet.fromVersionString(partnerVersion);
            if (fs == null) {
                awaitingFeatureSet = true;
                requestFeatureSet();
            } else {
                getPartner().setFeatureSet(fs);
                onFeatureSetReceive();
            }
        } else if (type == PacketType.REVOLUTION) {
            getPartner().setProtocolFlavor(ProtocolFlavor.NEW);
        } else {
            super.handle(id, packetBuf);
        }

    }

    @Override
    public void onFeatureSetReceive() {
        if (partnerVersion == null) {
            close(false);
            return;
        }
        awaitingFeatureSet = false;
        LogManager.getLogger(VersionHandshakeServer.class).info("Cytra-Syncmatica client joining with local version {} and client version {}", Syncmatica.getVersion(), partnerVersion);
        sendInitialState();
        succeed();
    }

    protected void sendInitialState() {
        final Collection<ServerPlacement> placements = getContext().getSyncmaticManager().getAll();
        for (final ServerPlacement placement : placements) {
            getManager().sendMetaData(placement, getPartner());
        }
        final PacketByteBuf confirmationBuf = new PacketByteBuf(Unpooled.buffer());
        confirmationBuf.writeInt(0);
        getPartner().sendPacket(
                PacketType.CONFIRM_USER.toIdentifier(getPartner().getProtocolFlavor()),
                confirmationBuf,
                getContext()
        );
    }

    @Override
    public void init() {
        final PacketByteBuf newBuf = new PacketByteBuf(Unpooled.buffer());
        newBuf.writeString(Syncmatica.getVersion(), ProtocolLimits.MAX_VERSION_LENGTH);
        // Initial handshake always uses legacy Syncmatica channel for compatibility.
        getPartner().sendPacket(PacketType.REGISTER_VERSION.toIdentifier(ProtocolFlavor.LEGACY), newBuf, getContext());
    }
}
