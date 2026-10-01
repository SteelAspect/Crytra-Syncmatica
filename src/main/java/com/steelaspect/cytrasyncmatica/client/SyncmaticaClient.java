package com.steelaspect.cytrasyncmatica.client;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.litematica.BuildClaimWarning;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint. Only client-side classes are reachable from here; the
 * dedicated server never loads this class.
 */
public class SyncmaticaClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        requireClientDependencies();
        ClientConfigs.INSTANCE.load();
        BuildClaimWarning.register();
        ClientTickEvents.END_CLIENT_TICK.register(SyncmaticaClient::handleClientTick);

        // Hotkeys and the config screen are registered once MaLiLib is ready.
        InitializationHandler.getInstance().registerInitializationHandler(new SyncmaticaInitHandler());
    }

    /**
     * Litematica and MaLiLib are client-only mods, and Fabric cannot scope a hard
     * dependency to one side, so they are declared as "recommends" and enforced
     * here before any of their classes is touched.
     */
    private static void requireClientDependencies() {
        final FabricLoader loader = FabricLoader.getInstance();
        final StringBuilder missing = new StringBuilder();
        for (final String id : new String[] {"litematica", "malilib"}) {
            if (!loader.isModLoaded(id)) {
                missing.append(missing.length() == 0 ? "" : ", ").append(id);
            }
        }
        if (missing.length() > 0) {
            throw new IllegalStateException("Cytra-Syncmatica needs " + missing
                    + " on the client (Minecraft 1.21.11 builds). Install them, or remove Cytra-Syncmatica from this client.");
        }
    }

    private static void handleClientTick(final MinecraftClient client) {
        final Context clientContext = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        if (clientContext != null && clientContext.getCommunicationManager() != null) {
            clientContext.getCommunicationManager().tick();
        }
    }
}
