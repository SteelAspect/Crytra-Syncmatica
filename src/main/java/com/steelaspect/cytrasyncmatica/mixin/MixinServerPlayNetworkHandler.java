package com.steelaspect.cytrasyncmatica.mixin;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.ServerExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.SyncmaticaPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class MixinServerPlayNetworkHandler {

    @Shadow
    public ServerPlayerEntity player;
    @Unique
    private ExchangeTarget exTarget = null;
    @Unique
    private ServerCommunicationManager comManager = null;

    @Inject(method = "<init>", at = @At("TAIL"))
    public void onConnect(final MinecraftServer server, final ClientConnection connection, final ServerPlayerEntity player, final net.minecraft.server.network.ConnectedClientData clientData, final CallbackInfo ci) {
        if (connection == null) {
            return;
        }
        operateComms(sm -> sm.onPlayerJoin(getExchangeTarget(), player));
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    public void onDisconnected(final DisconnectionInfo info, final CallbackInfo ci) {
        operateComms(sm -> sm.onPlayerLeave(getExchangeTarget()));
    }

    @Inject(method = "onCustomPayload", at = @At("HEAD"))
    public void onCustomPayload(final CustomPayloadC2SPacket packet, final CallbackInfo ci) {
        NetworkThreadUtils.forceMainThread(packet, (ServerPlayNetworkHandler) (Object) this, player.getEntityWorld());
        if (packet.payload() instanceof SyncmaticaPayload syncPayload) {
            operateComms(sm -> sm.onPacket(getExchangeTarget(), syncPayload.id(), syncPayload.asPacketByteBuf()));
        }
    }

    private ExchangeTarget getExchangeTarget() {
        if (exTarget == null) {
            exTarget = new ServerExchangeTarget((ServerPlayNetworkHandler) (Object) this);
        }
        return exTarget;
    }

    private void operateComms(final Consumer<ServerCommunicationManager> operation) {
        if (comManager == null) {
            final Context con = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
            if (con != null) {
                comManager = (ServerCommunicationManager) con.getCommunicationManager();
            }
        }
        if (comManager != null) {
            operation.accept(comManager);
        }
    }
}
