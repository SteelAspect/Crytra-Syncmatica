package com.steelaspect.cytrasyncmatica.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialEventListener;
import com.steelaspect.cytrasyncmatica.materials.MaterialGroups;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.materials.StackFormat;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side material tracking: one shared material list per shared schematic,
 * derived from the .litematic file, with one shared gathered count per item and
 * a record of who last changed it. State lives in
 * {@code <world>/cytra-syncmatica/materials/<placement id>.json} so normal
 * backups include it. All public methods run on the server thread; file IO and
 * schematic extraction run on one background thread.
 */
public class MaterialTrackingService extends AbstractService {
    private static final Logger LOGGER = LogManager.getLogger(MaterialTrackingService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final boolean ENABLED_DEFAULT = true;
    public static final int MAX_SCHEMATIC_BLOCKS_DEFAULT = 8_000_000;
    public static final int MAX_SCHEMATIC_BLOCKS_MIN = 1_000_000;
    public static final int MAX_SCHEMATIC_BLOCKS_MAX = 64_000_000;

    private boolean enabled = ENABLED_DEFAULT;
    private int maxSchematicBlocks = MAX_SCHEMATIC_BLOCKS_DEFAULT;

    private final Map<UUID, MaterialList> lists = new HashMap<>();
    private final Map<UUID, String> extractionErrors = new HashMap<>();
    private final Map<UUID, MaterialListExtractor.Stats> stats = new HashMap<>();
    private final List<MaterialEventListener> listeners = new CopyOnWriteArrayList<>();
    private ExecutorService worker;
    private boolean started;
    private MaterialListExtractor.BlockItemResolver resolver = MaterialListExtractor.REGISTRY_RESOLVER;

    /** Tests swap the registry lookup for a fixed mapping. */
    public void setResolver(final MaterialListExtractor.BlockItemResolver resolver) {
        this.resolver = resolver;
    }

    public enum Outcome { OK, NO_CHANGE, UNKNOWN_ITEM, NO_LIST, DISABLED }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxSchematicBlocks() {
        return maxSchematicBlocks;
    }

    public void addListener(final MaterialEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(final MaterialEventListener listener) {
        listeners.remove(listener);
    }

    // -- configuration -----------------------------------------------------------

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        final ConfigRegistry registry = new ConfigRegistry();
        registerConfigOptions(registry);
        registry.saveDefaults(getConfigKey(), configuration);
    }

    @Override
    public String getConfigKey() {
        return "materials";
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadBoolean("enabled", this::setEnabled);
        configuration.loadInteger("max_schematic_blocks", this::setMaxSchematicBlocks);
    }

    public void registerConfigOptions(final ConfigRegistry registry) {
        registry.add(ConfigOption.bool(getConfigKey(), "enabled", ENABLED_DEFAULT, () -> enabled, this::setEnabled));
        registry.add(ConfigOption.integer(getConfigKey(), "max_schematic_blocks", MAX_SCHEMATIC_BLOCKS_DEFAULT,
                MAX_SCHEMATIC_BLOCKS_MIN, MAX_SCHEMATIC_BLOCKS_MAX, () -> maxSchematicBlocks, this::setMaxSchematicBlocks));
    }

    private void setEnabled(final boolean value) {
        final boolean changed = enabled != value;
        enabled = value;
        if (changed && context != null) {
            context.serverFeaturesChanged();
        }
    }

    private void setMaxSchematicBlocks(final int value) {
        maxSchematicBlocks = Math.max(MAX_SCHEMATIC_BLOCKS_MIN, Math.min(MAX_SCHEMATIC_BLOCKS_MAX, value));
    }

    // -- lifecycle ---------------------------------------------------------------

    @Override
    public void startup() {
        worker = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "cytra-syncmatica-materials");
            t.setDaemon(true);
            return t;
        });
        started = true;
        MaterialGroups.ensureTemplate(groupsFile());
    }

    @Override
    public void shutdown() {
        started = false;
        if (worker != null) {
            // Let queued writes (lists, previews, projects) land before the folder is considered closed.
            worker.shutdown();
            try {
                worker.awaitTermination(10L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        lists.clear();
        extractionErrors.clear();
        stats.clear();
    }

    // -- placements --------------------------------------------------------------

    public void attachPlacement(final ServerPlacement placement) {
        if (placement == null) {
            return;
        }
        MaterialList list = lists.get(placement.getId());
        if (list == null) {
            list = loadFromDisk(placement.getId());
            lists.put(placement.getId(), list);
        }
        if (list.isEmpty()) {
            refreshPlacement(placement);
        }
    }

    public void detachPlacement(final ServerPlacement placement) {
        if (placement == null) {
            return;
        }
        lists.remove(placement.getId());
        extractionErrors.remove(placement.getId());
        stats.remove(placement.getId());
        final Path file = fileFor(placement.getId());
        runIo(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (final IOException e) {
                LOGGER.warn("Could not delete {}", file, e);
            }
        });
    }

    /** Re-derives the required counts from the stored schematic (on share, on update, on load). */
    public void refreshPlacement(final ServerPlacement placement) {
        if (!enabled || placement == null || context == null) {
            return;
        }
        final File litematic = context.getFileStorage().getLocalLitematic(placement);
        final long maxBlocks = maxSchematicBlocks;
        final UUID id = placement.getId();
        final MaterialListExtractor.BlockItemResolver r = resolver;
        final Path overridesFile = groupsFile();
        runIo(() -> {
            final MaterialListExtractor.Result result = MaterialListExtractor.extract(litematic, maxBlocks, 64L * 1024L * 1024L, r);
            final Map<String, String> overrides = MaterialGroups.loadOverrides(overridesFile);
            onServerThread(() -> applyExtraction(id, result, overrides));
        });
    }

    /** {@code config/cytra-syncmatica/groups.json}: per-item group overrides, re-read at every extraction. */
    public Path groupsFile() {
        final File cfg = context == null ? null : context.getConfigFolder();
        return cfg == null ? null : new File(cfg, MaterialGroups.FILE_NAME).toPath();
    }

    private void applyExtraction(final UUID id, final MaterialListExtractor.Result result, final Map<String, String> groupOverrides) {
        final ServerPlacement placement = context.getSyncmaticManager().getPlacement(id);
        if (placement == null) {
            return;
        }
        if (!result.ok()) {
            extractionErrors.put(id, result.error);
            LOGGER.warn("No material list for '{}': {}", placement.getName(), result.error);
            return;
        }
        extractionErrors.remove(id);
        final MaterialList list = lists.computeIfAbsent(id, k -> new MaterialList());
        list.applyRequirements(result.requirements);
        MaterialGroups.assign(list, groupOverrides);
        stats.put(id, result.stats);
        save(id);
        broadcastList(placement);
        for (final MaterialEventListener l : listeners) {
            l.onListCreated(placement, list);
        }
    }

    public MaterialList getList(final ServerPlacement placement) {
        return placement == null ? null : lists.get(placement.getId());
    }

    public MaterialList getList(final UUID placementId) {
        return lists.get(placementId);
    }

    public String getExtractionError(final UUID placementId) {
        return extractionErrors.get(placementId);
    }

    /** Size and block counts from the last extraction; {@link MaterialListExtractor.Stats#EMPTY} when unknown. */
    public MaterialListExtractor.Stats getStats(final UUID placementId) {
        return stats.getOrDefault(placementId, MaterialListExtractor.Stats.EMPTY);
    }

    public Collection<UUID> trackedPlacements() {
        return Collections.unmodifiableCollection(lists.keySet());
    }

    // -- edits -------------------------------------------------------------------

    /** Applies one edit; the caller has already checked the editor's permission for {@code op}. */
    public Outcome apply(final ServerPlacement placement, final String itemId, final MaterialOp op, final int amount,
                         final PlayerIdentifier editor) {
        if (!enabled) {
            return Outcome.DISABLED;
        }
        final MaterialList list = getList(placement);
        if (list == null) {
            return Outcome.NO_LIST;
        }
        final MaterialEntry entry = list.get(itemId);
        if (entry == null) {
            return Outcome.UNKNOWN_ITEM;
        }
        final int old = entry.getGathered();
        final int target;
        switch (op) {
            case ADD -> target = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) old + amount));
            case SET -> target = amount;
            case DONE -> target = entry.getRequired();
            case RESET -> target = 0;
            default -> target = old;
        }
        final boolean wasComplete = list.isComplete();
        final boolean groupWasComplete = list.isGroupComplete(entry.getGroup());
        final long now = System.currentTimeMillis();
        entry.setGathered(target, editor == null ? null : editor.uuid, editor == null ? "" : editor.getName(), now);
        if (entry.getGathered() == old) {
            return Outcome.NO_CHANGE;
        }
        save(placement.getId());
        if (context.getCommunicationManager() instanceof ServerCommunicationManager comms) {
            comms.broadcastMaterialUpdate(placement, entry);
        }
        for (final MaterialEventListener l : listeners) {
            l.onItemChanged(placement, entry, old, editor, op);
            if (entry.isComplete() && old < entry.getRequired()) {
                l.onItemCompleted(placement, entry, editor);
            }
        }
        if (!groupWasComplete && list.isGroupComplete(entry.getGroup())) {
            final MaterialList.GroupTotals group = list.group(entry.getGroup());
            for (final MaterialEventListener l : listeners) {
                l.onGroupCompleted(placement, group, editor);
            }
        }
        if (!wasComplete && list.isComplete()) {
            for (final MaterialEventListener l : listeners) {
                l.onSchematicCompleted(placement, list, editor);
            }
        }
        return Outcome.OK;
    }

    private void broadcastList(final ServerPlacement placement) {
        if (context.getCommunicationManager() instanceof ServerCommunicationManager comms) {
            comms.broadcastMaterialList(placement);
        }
    }

    // -- persistence -------------------------------------------------------------

    private Path storageFolder() {
        final File world = context.getWorldFolder();
        final File root = world != null ? world : context.getConfigFolder();
        return new File(new File(root, Syncmatica.MOD_ID), "materials").toPath();
    }

    private Path fileFor(final UUID id) {
        return storageFolder().resolve(id + ".json");
    }

    private MaterialList loadFromDisk(final UUID id) {
        final Path file = fileFor(id);
        if (!Files.isRegularFile(file)) {
            return new MaterialList();
        }
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("stats") && o.get("stats").isJsonObject()) {
                stats.put(id, MaterialListExtractor.Stats.fromJson(o.getAsJsonObject("stats")));
            }
            return MaterialList.fromJson(o);
        } catch (final Exception e) {
            LOGGER.warn("Could not read {}; starting a fresh list", file, e);
            return new MaterialList();
        }
    }

    private void save(final UUID id) {
        final MaterialList list = lists.get(id);
        if (list == null) {
            return;
        }
        final JsonObject o = list.toJson();
        final MaterialListExtractor.Stats s = stats.get(id);
        if (s != null) {
            o.add("stats", s.toJson());
        }
        final String json = GSON.toJson(o);
        final Path file = fileFor(id);
        runIo(() -> writeAtomically(file, json));
    }

    private static void writeAtomically(final Path file, final String text) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".new");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }

    // -- export ------------------------------------------------------------------

    /** Writes {@code <name>.csv} and {@code <name>.txt} into {@code folder}; completes with the two paths. */
    public CompletableFuture<List<Path>> export(final ServerPlacement placement, final Path folder) {
        final MaterialList list = getList(placement);
        if (list == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no material list for " + placement.getName()));
        }
        return exportList(placement.getName(), list, folder);
    }

    /** Same for any list (a project's combined list, for one). */
    public CompletableFuture<List<Path>> exportList(final String name, final MaterialList list, final Path folder) {
        final List<MaterialEntry> snapshot = list.copyEntries();
        final Map<String, Integer> stackSizes = new HashMap<>();
        for (final MaterialEntry e : snapshot) {
            stackSizes.put(e.getItemId(), stackSizeOf(e.getItemId()));
        }
        final CompletableFuture<List<Path>> future = new CompletableFuture<>();
        runIo(() -> {
            try {
                Files.createDirectories(folder);
                final String base = safeFileName(name);
                final Path csv = folder.resolve(base + ".csv");
                final Path txt = folder.resolve(base + ".txt");
                Files.writeString(csv, toCsv(snapshot), StandardCharsets.UTF_8);
                Files.writeString(txt, toText(name, snapshot, stackSizes), StandardCharsets.UTF_8);
                future.complete(List.of(csv, txt));
            } catch (final IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static String toCsv(final List<MaterialEntry> entries) {
        final StringBuilder sb = new StringBuilder("item,group,required,gathered,remaining,last_edited_by,last_edited_at\n");
        for (final MaterialEntry e : entries) {
            sb.append(e.getItemId()).append(',').append(csvEscape(e.getGroup())).append(',').append(e.getRequired()).append(',')
                    .append(e.getGathered()).append(',')
                    .append(e.getRemaining()).append(',').append(csvEscape(e.getEditorName())).append(',')
                    .append(e.getEditedAt()).append('\n');
        }
        return sb.toString();
    }

    public static String toText(final String title, final List<MaterialEntry> entries, final Map<String, Integer> stackSizes) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Materials for ").append(title).append('\n');
        long req = 0, got = 0;
        for (final MaterialEntry e : entries) {
            req += e.getRequired();
            got += Math.min(e.getGathered(), e.getRequired());
        }
        sb.append(String.format(java.util.Locale.ROOT, "%d / %d items gathered (%.1f%%)%n%n", got, req, req == 0 ? 100.0 : 100.0 * got / req));
        sb.append(String.format(java.util.Locale.ROOT, "%-40s %-14s %10s %10s %10s  %s%n", "item", "group", "required", "gathered", "remaining", "remaining (shulkers/stacks)"));
        for (final MaterialEntry e : entries) {
            final int stack = stackSizes.getOrDefault(e.getItemId(), 64);
            sb.append(String.format(java.util.Locale.ROOT, "%-40s %-14s %10d %10d %10d  %s%n", e.getItemId(), e.getGroup(), e.getRequired(),
                    e.getGathered(), e.getRemaining(), StackFormat.format(e.getRemaining(), stack)));
        }
        return sb.toString();
    }

    private static String csvEscape(final String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public static String safeFileName(final String name) {
        final String cleaned = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return cleaned.isEmpty() ? "schematic" : cleaned;
    }

    /** Max stack size of an item id, 64 when unknown. Registry reads are thread-safe. */
    public static int stackSizeOf(final String itemId) {
        try {
            final net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(itemId);
            if (id == null) {
                return 64;
            }
            return net.minecraft.registry.Registries.ITEM.getOptionalValue(id)
                    .map(net.minecraft.item.Item::getMaxCount).orElse(64);
        } catch (final RuntimeException | LinkageError e) {
            return 64;
        }
    }

    // -- threading ---------------------------------------------------------------

    /** Runs a small IO task on the material background thread (inline before startup). */
    public void runInBackground(final Runnable task) {
        runIo(task);
    }

    private void runIo(final Runnable task) {
        final ExecutorService w = worker;
        if (w == null || !started) {
            task.run();
            return;
        }
        w.execute(() -> {
            try {
                task.run();
            } catch (final RuntimeException e) {
                LOGGER.warn("Material background task failed", e);
            }
        });
    }

    private void onServerThread(final Runnable task) {
        final MinecraftServer server = context.getMinecraftServer();
        if (server == null) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    public static List<MaterialEntry> sortedCopy(final MaterialList list) {
        final List<MaterialEntry> out = new ArrayList<>(list.copyEntries());
        out.sort((a, b) -> Integer.compare(b.getRemaining(), a.getRemaining()));
        return out;
    }
}
