package com.steelaspect.cytrasyncmatica.server;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dedicated-server entrypoint. Nothing here touches Litematica, MaLiLib or any
 * rendering class, so the jar loads on a server that has only Fabric API.
 * The bridge to the cytra-bridge bot is registered separately through the
 * {@code "cytra-link"} entrypoint and is only loaded when Cytra Link is present.
 */
public final class SyncmaticaServer implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("Cytra-Syncmatica");

    @Override
    public void onInitializeServer() {
        final boolean link = FabricLoader.getInstance().isModLoaded("cytra-link");
        LOGGER.info("Cytra-Syncmatica {} on a dedicated server; Discord bridge: {}", Syncmatica.getVersion(),
                link ? "available through Cytra Link" : "off (Cytra Link not installed)");
    }
}
