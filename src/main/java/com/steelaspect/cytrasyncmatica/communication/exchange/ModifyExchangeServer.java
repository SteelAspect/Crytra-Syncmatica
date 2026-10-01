package com.steelaspect.cytrasyncmatica.communication.exchange;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.UUID;

public class ModifyExchangeServer extends AbstractExchange {

    private final ServerPlacement placement;
    UUID placementId;

    public ModifyExchangeServer(final UUID placeId, final ExchangeTarget partner, final Context con) {
        super(partner, con);
        placementId = placeId;
        placement = con.getSyncmaticManager().getPlacement(placementId);
    }

    @Override
    public boolean checkPacket(final Identifier id, final PacketByteBuf packetBuf) {
        final PacketType type = PacketType.fromIdentifier(id);
        return type == PacketType.MODIFY_FINISH && checkUUID(packetBuf, placement.getId());
    }

    @Override
    public void handle(final Identifier id, final PacketByteBuf packetBuf) {
        packetBuf.readUuid();
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == PacketType.MODIFY_FINISH) {
            getContext().getCommunicationManager().receiveModificationData(placement, packetBuf, getPartner());

            final PlayerIdentifier identifier = getContext().getPlayerIdentifierProvider().createOrGet(
                    getPartner()
            );
            placement.setLastModifiedBy(identifier);
            placement.touchModified(System.currentTimeMillis());
            getContext().getSyncmaticManager().updateServerPlacement(placement);
            succeed();
        }
    }

    @Override
    public void init() {
        if (getPlacement() == null || getContext().getCommunicationManager().getModifier(placement) != null) {
            close(true);
        } else {
            accept();
        }
    }

    private void accept() {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeUuid(placement.getId());
        getPartner().sendPacket(PacketType.MODIFY_REQUEST_ACCEPT.toIdentifier(getPartner().getProtocolFlavor()), buf, getContext());
        getContext().getCommunicationManager().setModifier(placement, this);
    }

    @Override
    protected void sendCancelPacket() {
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeUuid(placementId);
        getPartner().sendPacket(PacketType.MODIFY_REQUEST_DENY.toIdentifier(getPartner().getProtocolFlavor()), buf, getContext());
    }

    public ServerPlacement getPlacement() {
        return placement;
    }

    @Override
    protected void onClose() {
        if (getContext().getCommunicationManager().getModifier(placement) == this) {
            getContext().getCommunicationManager().setModifier(placement, null);
        }
    }

}
