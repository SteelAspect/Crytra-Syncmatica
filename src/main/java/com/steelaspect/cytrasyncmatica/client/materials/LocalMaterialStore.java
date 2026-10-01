package com.steelaspect.cytrasyncmatica.client.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local material counts for client-only mode and singleplayer, one JSON file per
 * schematic under {@code config/cytra-syncmatica/client/<server-or-world>/}.
 * Never touches the network.
 */
public final class LocalMaterialStore {
    private static final Logger LOGGER = LogManager.getLogger(LocalMaterialStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path folder;

    public LocalMaterialStore(final String worldOrServer) {
        folder = Path.of("config", Syncmatica.MOD_ID, "client", TrackedSchematic.sanitize(worldOrServer));
    }

    public Path folder() {
        return folder;
    }

    public Path fileFor(final String key) {
        return folder.resolve(key + ".json");
    }

    public MaterialList load(final String key) {
        final Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return new MaterialList();
        }
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return MaterialList.fromJson(o);
        } catch (final Exception e) {
            LOGGER.warn("Could not read {}", file, e);
            return new MaterialList();
        }
    }

    public void save(final String key, final MaterialList list) {
        final Path file = fileFor(key);
        try {
            Files.createDirectories(folder);
            Files.writeString(file, GSON.toJson(list.toJson()), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
