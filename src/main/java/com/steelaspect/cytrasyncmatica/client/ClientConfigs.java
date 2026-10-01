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
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
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
        General.HUD_ENABLED.setValueChangeCallback(config -> save());
        General.HUD_SCALE.setValueChangeCallback(config -> save());
        General.HUD_X.setValueChangeCallback(config -> save());
        General.HUD_Y.setValueChangeCallback(config -> save());
        General.HUD_MAX_ROWS.setValueChangeCallback(config -> save());
        General.HUD_LAYER_LINE.setValueChangeCallback(config -> save());
        General.LAYER_SCAN_PER_TICK.setValueChangeCallback(config -> save());
        General.AUTO_COUNT_CONTAINERS.setValueChangeCallback(config -> save());
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

        public static final ConfigBoolean HUD_ENABLED = new ConfigBoolean(
                "hudEnabled", true,
                "cytra-syncmatica.config.comment.hud_enabled",
                "cytra-syncmatica.config.name.hud_enabled");
        public static final ConfigDouble HUD_SCALE = new ConfigDouble(
                "hudScale", 1.0d, 0.5d, 2.0d, true,
                "cytra-syncmatica.config.comment.hud_scale",
                "cytra-syncmatica.config.name.hud_scale");
        public static final ConfigInteger HUD_X = new ConfigInteger(
                "hudX", 4, 0, 4000,
                "cytra-syncmatica.config.comment.hud_x",
                "cytra-syncmatica.config.name.hud_x");
        public static final ConfigInteger HUD_Y = new ConfigInteger(
                "hudY", 4, 0, 4000,
                "cytra-syncmatica.config.comment.hud_y",
                "cytra-syncmatica.config.name.hud_y");
        public static final ConfigInteger HUD_MAX_ROWS = new ConfigInteger(
                "hudMaxRows", 12, 1, 40,
                "cytra-syncmatica.config.comment.hud_max_rows",
                "cytra-syncmatica.config.name.hud_max_rows");
        public static final ConfigBoolean HUD_LAYER_LINE = new ConfigBoolean(
                "hudLayerLine", true,
                "cytra-syncmatica.config.comment.hud_layer_line",
                "cytra-syncmatica.config.name.hud_layer_line");
        public static final ConfigInteger LAYER_SCAN_PER_TICK = new ConfigInteger(
                "layerScanPerTick", 4096, 256, 65536,
                "cytra-syncmatica.config.comment.layer_scan_per_tick",
                "cytra-syncmatica.config.name.layer_scan_per_tick");
        /** Whether the auto-count also looks at the container the player has open. */
        public static final ConfigBoolean AUTO_COUNT_CONTAINERS = new ConfigBoolean(
                "autoCountOpenContainers", true,
                "cytra-syncmatica.config.comment.auto_count_containers",
                "cytra-syncmatica.config.name.auto_count_containers");

        public static final List<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_ENABLED,
                HUD_SCALE,
                HUD_X,
                HUD_Y,
                HUD_MAX_ROWS,
                HUD_LAYER_LINE,
                LAYER_SCAN_PER_TICK,
                AUTO_COUNT_CONTAINERS,
                FOLLOW_CLAIMS,
                WARN_ON_FOREIGN_PLACEMENT
        );

        private General() {
        }
    }
}
