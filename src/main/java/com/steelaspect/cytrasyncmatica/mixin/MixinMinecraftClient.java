package com.steelaspect.cytrasyncmatica.mixin;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.litematica.ClaimedRegionVisibility;
import com.steelaspect.cytrasyncmatica.litematica.LitematicManager;
import com.steelaspect.cytrasyncmatica.litematica.ScreenHelper;
import com.steelaspect.cytrasyncmatica.mixin_actor.ActorClientPlayNetworkHandler;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void shutdownSyncmatica(final CallbackInfo ci) {
        ScreenHelper.close();
        com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient.getInstance().reset();
        com.steelaspect.cytrasyncmatica.client.hud.MaterialHud.getInstance().reset();
        com.steelaspect.cytrasyncmatica.client.layers.LayerProgressClient.getInstance().reset();
        ClaimedRegionVisibility.getInstance().reset();
        Syncmatica.shutdown();
        LitematicManager.clear();
        ActorClientPlayNetworkHandler.getInstance().reset();
    }
}
