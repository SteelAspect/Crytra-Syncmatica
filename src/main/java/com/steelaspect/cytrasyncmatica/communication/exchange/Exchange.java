package com.steelaspect.cytrasyncmatica.communication.exchange;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public interface Exchange {

    ExchangeTarget getPartner();

    Context getContext();

    boolean checkPacket(Identifier id, PacketByteBuf packetBuf);

    void handle(Identifier id, PacketByteBuf packetBuf);

    boolean isFinished();

    boolean isSuccessful();

    void markActivity();

    boolean isTimedOut(long nowMillis);

    void close(boolean notifyPartner);

    void init();

}
