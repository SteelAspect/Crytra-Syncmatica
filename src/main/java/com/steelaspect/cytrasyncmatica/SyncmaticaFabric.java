package com.steelaspect.cytrasyncmatica;

import com.steelaspect.cytrasyncmatica.command.SyncmaticaCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.steelaspect.cytrasyncmatica.communication.SyncmaticaPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class SyncmaticaFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        warnAboutUpstream();
        registerPayloads();
        registerCommands();
    }

    /**
     * fabric.mod.json already declares "breaks" on both upstream ids, so the loader
     * normally refuses to start with them. This is the belt to that suspender.
     */
    private static void warnAboutUpstream() {
        final FabricLoader loader = FabricLoader.getInstance();
        for (final String id : new String[] {Syncmatica.UPSTREAM_MOD_ID, "syncmatica"}) {
            if (loader.isModLoaded(id)) {
                LogManager.getLogger("Cytra-Syncmatica").error(
                        "'{}' is installed next to Cytra-Syncmatica. They use different network channels and data folders and cannot coexist: remove one of them.", id);
            }
        }
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> SyncmaticaCommand.register(dispatcher));
    }

    private static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(SyncmaticaPayload.PACKET_ID, SyncmaticaPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SyncmaticaPayload.PACKET_ID, SyncmaticaPayload.CODEC);
    }
}
