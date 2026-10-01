package com.steelaspect.cytrasyncmatica.util;

import com.steelaspect.cytrasyncmatica.ServerPosition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** Turns a stored dimension identifier back into a loaded world. */
public final class WorldResolver {

    private static final String END_DIMENSION_ID = "minecraft:the_end";

    private WorldResolver() {
    }

    /** @return the world for that dimension, or null when the server has none */
    public static ServerWorld resolve(final MinecraftServer server, final String dimensionId) {
        if (server == null || dimensionId == null) {
            return null;
        }
        if (ServerPosition.OVERWORLD_DIMENSION_ID.equals(dimensionId)) {
            return server.getOverworld();
        }
        if (ServerPosition.NETHER_DIMENSION_ID.equals(dimensionId)) {
            return server.getWorld(World.NETHER);
        }
        if (END_DIMENSION_ID.equals(dimensionId)) {
            return server.getWorld(World.END);
        }
        final RegistryKey<World> key = RegistryKey.of(
                RegistryKeys.WORLD,
                IdentifierUtil.require(dimensionId));
        return server.getWorld(key);
    }
}
