package com.steelaspect.cytrasyncmatica.client;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.client.hotkey.SyncmaticaHotkeys;
import com.steelaspect.cytrasyncmatica.litematica.ClaimedRegionVisibility;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ClientConfigs implements IConfigHandler {

    public static final ClientConfigs INSTANCE = new ClientConfigs();

    private static final Logger LOGGER = LogManager.getLogger(ClientConfigs.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIRECTORY = Path.of("config", Syncmatica.MOD_ID);
    private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve("client.json");

    private ClientConfigs() {
        General.FOLLOW_CLAIMS.setValueChangeCallback(config -> {
            ClaimedRegionVisibility.getInstance().refresh();
            save();
        });
        General.WARN_ON_FOREIGN_PLACEMENT.setValueChangeCallback(config -> save());
    }

    @Override
    public void load() {
        JsonObject root = null;
        if (Files.isRegularFile(CONFIG_FILE)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonObject.class);
            } catch (final IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read {}", CONFIG_FILE, exception);
            }
        }

        if (root != null) {
            ConfigUtils.readConfigBase(root, "General", General.OPTIONS);
            ConfigUtils.readConfigBase(root, "Hotkeys", SyncmaticaHotkeys.getHotkeys());
        }
        if (!Files.isRegularFile(CONFIG_FILE)) {
            save();
        }
    }

    @Override
    public void save() {
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            final JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "General", General.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hotkeys", SyncmaticaHotkeys.getHotkeys());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (final IOException exception) {
            LOGGER.warn("Failed to write {}", CONFIG_FILE, exception);
        }
    }

    public static final class General {

        public static final ConfigBoolean FOLLOW_CLAIMS = new ConfigBoolean(
                "followClaims", false,
                "cytra-syncmatica.config.comment.follow_claims",
                "cytra-syncmatica.config.name.follow_claims") {
            @Override
            public String getConfigGuiDisplayName() {
                return getPrettyName();
            }
        };
        public static final ConfigBoolean WARN_ON_FOREIGN_PLACEMENT = new ConfigBoolean(
                "warnOnForeignPlacement", true,
                "cytra-syncmatica.config.comment.warn_on_foreign_placement",
                "cytra-syncmatica.config.name.warn_on_foreign_placement") {
            @Override
            public String getConfigGuiDisplayName() {
                return getPrettyName();
            }
        };

        public static final List<IConfigBase> OPTIONS = ImmutableList.of(
                FOLLOW_CLAIMS,
                WARN_ON_FOREIGN_PLACEMENT
        );

        private General() {
        }
    }
}
