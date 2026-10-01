package com.steelaspect.cytrasyncmatica.communication;

import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;

/** A connected player, as the server sees it. */
public final class ServerExchangeTarget extends ExchangeTarget {
    private final ServerPlayNetworkHandler client;

    public ServerExchangeTarget(final ServerPlayNetworkHandler client) {
        super(client.player.getUuidAsString());
        this.client = client;
    }

    @Override
    protected void transmit(final SyncmaticaPayload payload) {
        client.sendPacket(new CustomPayloadS2CPacket(payload));
    }
}
