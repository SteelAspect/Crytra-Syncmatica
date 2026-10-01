package com.steelaspect.cytrasyncmatica.mixin;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.FileStorage;
import com.steelaspect.cytrasyncmatica.SyncmaticManager;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
    @Inject(method = "startServer", at = @At("RETURN"))
    private static <S extends MinecraftServer> void initSyncmatica(final Function<Thread, S> serverFactory, final CallbackInfoReturnable<S> ci) {
        final MinecraftServer returnValue = ci.getReturnValue();
        final Context context = Syncmatica.initServer(
                new ServerCommunicationManager(),
                new FileStorage(),
                new SyncmaticManager(),
                !returnValue.isDedicated(),
                returnValue.getSavePath(WorldSavePath.ROOT).toFile()
        );
        context.attachMinecraftServer(returnValue);
        context.startup();
    }

    @Inject(method = "shutdown", at = @At("TAIL"))
    public void shutdownSyncmatica(final CallbackInfo ci) {
        Syncmatica.shutdown();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tickSyncmatica(final CallbackInfo ci) {
        final Context context = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (context != null && context.getBuildService() != null) {
            context.getBuildService().tick((MinecraftServer) (Object) this);
        }
        if (context != null && context.getBridge() != null) {
            context.getBridge().tick();
        }
        if (context != null && context.getSyncmaticManager() != null) {
            context.getSyncmaticManager().tickServer();
        }
        if (context != null && context.getCommunicationManager() != null) {
            context.getCommunicationManager().tick();
        }
    }
}
