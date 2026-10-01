package com.steelaspect.cytrasyncmatica.client.network;

import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.SyncmaticaPayload;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;

/** The server, as the client sees it. Client-only: uses MaLiLib for the world/server name. */
public final class ClientExchangeTarget extends ExchangeTarget {
    private final ClientPlayNetworkHandler server;

    public ClientExchangeTarget(final ClientPlayNetworkHandler server) {
        super(StringUtils.getWorldOrServerName());
        this.server = server;
    }

    @Override
    protected void transmit(final SyncmaticaPayload payload) {
        server.sendPacket(new CustomPayloadC2SPacket(payload));
    }
}
