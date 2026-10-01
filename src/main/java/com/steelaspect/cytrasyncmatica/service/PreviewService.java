package com.steelaspect.cytrasyncmatica.service;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.build_management.RegionBlocks;
import com.steelaspect.cytrasyncmatica.build_management.RegionLayoutExtractor;
import com.steelaspect.cytrasyncmatica.materials.MaterialEventListener;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.preview.PreviewRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Top-down preview images of shared schematics. Rendered on the material
 * background thread whenever a material list is (re)built (share, update,
 * load), written to {@code <world>/cytra-syncmatica/previews/<id>.png} and
 * kept in a bounded memory cache for the bridge. Nothing here touches the
 * server thread except the bookkeeping.
 */
public class PreviewService extends AbstractService implements MaterialEventListener {
    private static final Logger LOGGER = LogManager.getLogger(PreviewService.class);
    public static final boolean ENABLED_DEFAULT = true;
    public static final int MAX_PIXELS_DEFAULT = 1_048_576;
    public static final int MAX_PIXELS_MIN = 65_536;
    public static final int MAX_PIXELS_MAX = 16_777_216;
    /** Cytra Link allows 8 MB per frame; base64 adds a third, so the PNG stays under this. */
    public static final int MAX_PNG_BYTES = 5_500_000;
    private static final long MEMORY_BUDGET = 48L * 1024L * 1024L;

    /** What is known about one placement's preview. */
    public record Info(int width, int height, int blocksX, int blocksZ, int scale, int step, int bytes, long generatedAt) {
        public com.google.gson.JsonObject toJson() {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("available", true);
            o.addProperty("width", width);
            o.addProperty("height", height);
            o.addProperty("blocks_x", blocksX);
            o.addProperty("blocks_z", blocksZ);
            o.addProperty("scale", scale);
            o.addProperty("step", step);
            o.addProperty("bytes", bytes);
            o.addProperty("generated_at", generatedAt);
            return o;
        }

        public static Info fromJson(final com.google.gson.JsonObject o) {
            try {
                return new Info(o.get("width").getAsInt(), o.get("height").getAsInt(), o.get("blocks_x").getAsInt(),
                        o.get("blocks_z").getAsInt(), o.get("scale").getAsInt(), o.get("step").getAsInt(),
                        o.get("bytes").getAsInt(), o.get("generated_at").getAsLong());
            } catch (final RuntimeException e) {
                return null;
            }
        }
    }

    private boolean enabled = ENABLED_DEFAULT;
    private int maxPixels = MAX_PIXELS_DEFAULT;
    private final Map<UUID, Info> infos = new HashMap<>();
    private final Map<UUID, byte[]> memory = new LinkedHashMap<>(16, 0.75f, true);
    private long memoryBytes;
    private final Map<UUID, String> errors = new HashMap<>();
    private PreviewRenderer.BlockColorResolver colors = PreviewRenderer.REGISTRY_COLORS;

    /** Tests swap the registry lookup for a fixed mapping. */
    public void setColorResolver(final PreviewRenderer.BlockColorResolver resolver) {
        colors = resolver;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // -- configuration -------------------------------------------------------------

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        final ConfigRegistry registry = new ConfigRegistry();
        registerConfigOptions(registry);
        registry.saveDefaults(getConfigKey(), configuration);
    }

    @Override
    public String getConfigKey() {
        return "preview";
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadBoolean("enabled", v -> enabled = v);
        configuration.loadInteger("max_pixels", this::setMaxPixels);
    }

    public void registerConfigOptions(final ConfigRegistry registry) {
        registry.add(ConfigOption.bool(getConfigKey(), "enabled", ENABLED_DEFAULT, () -> enabled, v -> enabled = v));
        registry.add(ConfigOption.integer(getConfigKey(), "max_pixels", MAX_PIXELS_DEFAULT, MAX_PIXELS_MIN, MAX_PIXELS_MAX,
                () -> maxPixels, this::setMaxPixels));
    }

    private void setMaxPixels(final int value) {
        maxPixels = Math.max(MAX_PIXELS_MIN, Math.min(MAX_PIXELS_MAX, value));
    }

    // -- lifecycle -------------------------------------------------------------------

    @Override
    public void startup() {
        if (context.getMaterialTracking() != null) {
            context.getMaterialTracking().addListener(this);
        }
    }

    @Override
    public void shutdown() {
        if (context != null && context.getMaterialTracking() != null) {
            context.getMaterialTracking().removeListener(this);
        }
        infos.clear();
        memory.clear();
        memoryBytes = 0;
        errors.clear();
    }

    // -- rendering -------------------------------------------------------------------

    /** A list was (re)built from the file: the picture may have changed too. */
    @Override
    public void onListCreated(final ServerPlacement placement, final MaterialList list) {
        refresh(placement);
    }

    /**
     * A placement came back at startup: pick up the picture rendered last time
     * (PNG + sidecar JSON in the world folder) or render it now.
     */
    public void attachPlacement(final ServerPlacement placement) {
        if (!enabled || placement == null || context == null || context.getMaterialTracking() == null) {
            return;
        }
        final UUID id = placement.getId();
        final Path png = fileFor(id);
        final Path meta = metaFor(id);
        context.getMaterialTracking().runInBackground(() -> {
            Info info = null;
            if (Files.isRegularFile(png) && Files.isRegularFile(meta)) {
                try {
                    info = Info.fromJson(com.google.gson.JsonParser.parseString(Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
                } catch (final IOException | RuntimeException e) {
                    info = null;
                }
            }
            final Info found = info;
            onServerThread(() -> {
                if (found != null) {
                    infos.putIfAbsent(id, found);
                } else if (!infos.containsKey(id)) {
                    refresh(placement);
                }
            });
        });
    }

    public void refresh(final ServerPlacement placement) {
        if (!enabled || placement == null || context == null || context.getMaterialTracking() == null) {
            return;
        }
        final File litematic = context.getFileStorage().getLocalLitematic(placement);
        final UUID id = placement.getId();
        final long pixels = maxPixels;
        final PreviewRenderer.BlockColorResolver resolver = colors;
        final Path file = fileFor(id);
        context.getMaterialTracking().runInBackground(() -> {
            try {
                final PreviewRenderer.Rendered r = renderFile(litematic, pixels, resolver);
                writeAtomically(file, r.png());
                final Info info = new Info(r.width(), r.height(), r.blocksX(), r.blocksZ(), r.scale(), r.step(), r.png().length, System.currentTimeMillis());
                writeAtomically(metaFor(id), info.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                onServerThread(() -> {
                    errors.remove(id);
                    infos.put(id, info);
                    remember(id, r.png());
                });
            } catch (final IOException | RuntimeException e) {
                LOGGER.warn("No preview for '{}': {}", placement.getName(), e.toString());
                onServerThread(() -> errors.put(id, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    /** Renders, shrinking the pixel budget until the PNG fits the bridge's frame limit. */
    static PreviewRenderer.Rendered renderFile(final File litematic, final long pixels, final PreviewRenderer.BlockColorResolver resolver) throws IOException {
        final long maxNbt = 64L * 1024L * 1024L;
        final RegionLayoutExtractor.RegionLayout layout = RegionLayoutExtractor.extractLayout(litematic, maxNbt);
        final Map<String, RegionBlocks> blocks = RegionLayoutExtractor.extractRegionBlocks(litematic, maxNbt);
        if (layout.isEmpty() || blocks.isEmpty()) {
            throw new IOException("could not read the schematic regions");
        }
        long budget = pixels;
        PreviewRenderer.Rendered r = PreviewRenderer.render(layout.getGeometry(), blocks, budget, resolver);
        while (r.png().length > MAX_PNG_BYTES && budget > MAX_PIXELS_MIN) {
            budget /= 2;
            r = PreviewRenderer.render(layout.getGeometry(), blocks, budget, resolver);
        }
        return r;
    }

    private void remember(final UUID id, final byte[] png) {
        final byte[] old = memory.remove(id);
        if (old != null) {
            memoryBytes -= old.length;
        }
        memory.put(id, png);
        memoryBytes += png.length;
        final java.util.Iterator<Map.Entry<UUID, byte[]>> it = memory.entrySet().iterator();
        while (memoryBytes > MEMORY_BUDGET && it.hasNext()) {
            final Map.Entry<UUID, byte[]> e = it.next();
            if (e.getKey().equals(id)) {
                continue;
            }
            memoryBytes -= e.getValue().length;
            it.remove();
        }
    }

    public void forget(final UUID id) {
        infos.remove(id);
        errors.remove(id);
        final byte[] old = memory.remove(id);
        if (old != null) {
            memoryBytes -= old.length;
        }
        final Path file = fileFor(id);
        if (context != null && context.getMaterialTracking() != null) {
            final Path meta = metaFor(id);
            context.getMaterialTracking().runInBackground(() -> {
                try {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(meta);
                } catch (final IOException ignored) {
                    // best effort
                }
            });
        }
    }

    // -- queries -----------------------------------------------------------------------

    /** null while no preview exists (disabled, still rendering, or failed; see {@link #getError}). */
    public Info getInfo(final UUID placementId) {
        return infos.get(placementId);
    }

    public String getError(final UUID placementId) {
        return errors.get(placementId);
    }

    /** The PNG bytes: from memory when cached, else read from the world folder on the background thread. */
    public CompletableFuture<byte[]> getPng(final UUID placementId) {
        final byte[] cached = memory.get(placementId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (!infos.containsKey(placementId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("no preview yet"));
        }
        final Path file = fileFor(placementId);
        final CompletableFuture<byte[]> out = new CompletableFuture<>();
        context.getMaterialTracking().runInBackground(() -> {
            try {
                final byte[] bytes = Files.readAllBytes(file);
                onServerThread(() -> {
                    remember(placementId, bytes);
                    out.complete(bytes);
                });
            } catch (final IOException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    // -- files ---------------------------------------------------------------------------

    private Path storageFolder() {
        final File world = context.getWorldFolder();
        final File root = world != null ? world : context.getConfigFolder();
        return new File(new File(root, Syncmatica.MOD_ID), "previews").toPath();
    }

    public Path fileFor(final UUID id) {
        return storageFolder().resolve(id + ".png");
    }

    private Path metaFor(final UUID id) {
        return storageFolder().resolve(id + ".json");
    }

    private static void writeAtomically(final Path file, final byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".new");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void onServerThread(final Runnable task) {
        final net.minecraft.server.MinecraftServer server = context.getMinecraftServer();
        if (server == null) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
