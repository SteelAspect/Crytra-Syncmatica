package com.steelaspect.cytrasyncmatica.communication;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.communication.exchange.Exchange;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One peer of the protocol: a player seen from the server, or the server seen
 * from the client. The common code only knows how to hand a packet to it; the
 * actual network handler lives in {@link ServerExchangeTarget} (server side) and
 * {@code client.network.ClientExchangeTarget} (client side), so no class in the
 * common packages references Minecraft client code.
 */
public abstract class ExchangeTarget {
    private static final Logger LOGGER = LogManager.getLogger(ExchangeTarget.class);
    private final String persistentName;
    private final List<Exchange> ongoingExchanges = new ArrayList<>();
    private FeatureSet features;
    private ProtocolFlavor protocolFlavor = ProtocolFlavor.NEW;

    protected ExchangeTarget(final String persistentName) {
        this.persistentName = persistentName;
    }

    public void sendPacket(final Identifier id, final PacketByteBuf packetBuf, final Context context) {
        context.getDebugService().logSendPacket(id, persistentName);
        if (packetBuf == null || packetBuf.readableBytes() > ProtocolLimits.MAX_PACKET_BYTES) {
            LOGGER.warn("Refusing to send oversized Cytra-Syncmatica packet {} to {}", id, persistentName);
            return;
        }
        try {
            transmit(new SyncmaticaPayload(id, packetBuf));
        } catch (final Exception e) {
            // Fake players / NPCs spawned by other mods have no usable connection.
            LOGGER.debug("Failed to send packet to {}: {}", persistentName, e.getMessage());
        }
    }

    /** Hands a built payload to the underlying network handler. */
    protected abstract void transmit(SyncmaticaPayload payload);

    public FeatureSet getFeatureSet() {
        return features;
    }

    public void setFeatureSet(final FeatureSet f) {
        features = f;
    }

    public ProtocolFlavor getProtocolFlavor() {
        return protocolFlavor;
    }

    public void setProtocolFlavor(final ProtocolFlavor flavor) {
        protocolFlavor = flavor;
    }

    public Collection<Exchange> getExchanges() {
        return ongoingExchanges;
    }

    public String getPersistentName() {
        return persistentName;
    }
}
