package com.steelaspect.cytrasyncmatica.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.bridge.BridgeJson;
import com.steelaspect.cytrasyncmatica.bridge.BridgeSink;
import com.steelaspect.cytrasyncmatica.bridge.BridgeSinkRegistry;
import com.steelaspect.cytrasyncmatica.bridge.LinkCodes;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialEventListener;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the Discord bridge needs that does not touch Cytra Link classes:
 * the {@code bridge} config section, event batching (item changes are grouped
 * per schematic and sent at most every N seconds), an in-memory queue for the
 * time no bot is connected (drop-oldest, logged once) and the resync that
 * follows a bot attaching. The actual transport is a {@link BridgeSink} that
 * {@code bridge.CytraLinkBridge} registers when Cytra Link is present; with no
 * sink, events are queued up to the limit and otherwise ignored.
 *
 * <p>All methods run on the server thread except {@link #onBotConnected()} and
 * {@link #flushIfDue()} callers, which hand over to it.
 */
public class BridgeService extends AbstractService implements MaterialEventListener {
    private static final Logger LOGGER = LogManager.getLogger(BridgeService.class);
    public static final int PROTOCOL_VERSION = 1;
    public static final boolean ENABLED_DEFAULT = true;
    public static final int BATCH_SECONDS_DEFAULT = 5;
    public static final int QUEUE_LIMIT_DEFAULT = 500;
    public static final boolean HIDE_COORDINATES_DEFAULT = false;
    public static final int TOP_REMAINING = 5;

    private boolean enabled = ENABLED_DEFAULT;
    private int batchSeconds = BATCH_SECONDS_DEFAULT;
    private int queueLimit = QUEUE_LIMIT_DEFAULT;
    private boolean hideCoordinates = HIDE_COORDINATES_DEFAULT;

    private final LinkCodes linkCodes = new LinkCodes();
    private final Deque<Event> queue = new ArrayDeque<>();
    private boolean queueOverflowLogged;
    /** placement id -> item id -> pending change; flushed per schematic once the batch window passes. */
    private final Map<UUID, Batch> batches = new LinkedHashMap<>();

    private record Event(String type, JsonObject payload) {
    }

    private static final class Batch {
        final ServerPlacement placement;
        final long startedAt;
        final Map<String, JsonObject> changes = new LinkedHashMap<>();

        Batch(final ServerPlacement placement, final long startedAt) {
            this.placement = placement;
            this.startedAt = startedAt;
        }
    }

    // -- configuration -----------------------------------------------------------

    @Override
    public String getConfigKey() {
        return "bridge";
    }

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        final ConfigRegistry registry = new ConfigRegistry();
        registerConfigOptions(registry);
        registry.saveDefaults(getConfigKey(), configuration);
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadBoolean("enabled", v -> enabled = v);
        configuration.loadInteger("batch_seconds", v -> batchSeconds = Math.max(0, Math.min(600, v)));
        configuration.loadInteger("queue_limit", v -> queueLimit = Math.max(0, Math.min(100_000, v)));
        configuration.loadBoolean("hide_coordinates", v -> hideCoordinates = v);
    }

    public void registerConfigOptions(final ConfigRegistry registry) {
        registry.add(ConfigOption.bool(getConfigKey(), "enabled", ENABLED_DEFAULT, () -> enabled, v -> enabled = v));
        registry.add(ConfigOption.integer(getConfigKey(), "batch_seconds", BATCH_SECONDS_DEFAULT, 0, 600, () -> batchSeconds, v -> batchSeconds = v));
        registry.add(ConfigOption.integer(getConfigKey(), "queue_limit", QUEUE_LIMIT_DEFAULT, 0, 100_000, () -> queueLimit, v -> queueLimit = v));
        registry.add(ConfigOption.bool(getConfigKey(), "hide_coordinates", HIDE_COORDINATES_DEFAULT, () -> hideCoordinates, v -> hideCoordinates = v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHideCoordinates() {
        return hideCoordinates;
    }

    public int getBatchSeconds() {
        return batchSeconds;
    }

    public int getQueueLimit() {
        return queueLimit;
    }

    public LinkCodes getLinkCodes() {
        return linkCodes;
    }

    // -- lifecycle ---------------------------------------------------------------

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
        queue.clear();
        batches.clear();
    }

    private BridgeSink sink() {
        return BridgeSinkRegistry.get();
    }

    /** Whether events can leave the server at all (Cytra Link present and the section enabled). */
    public boolean isAvailable() {
        return enabled && sink() != null;
    }

    public boolean anyBotConnected() {
        final BridgeSink s = sink();
        return s != null && s.anyBotConnected();
    }

    // -- publishing --------------------------------------------------------------

    /** Sends now if a bot is connected, else queues (bounded, drop-oldest). Server thread. */
    public void publish(final String type, final JsonObject payload) {
        if (!enabled) {
            return;
        }
        final BridgeSink s = sink();
        if (s != null && s.anyBotConnected()) {
            s.publish(type, payload);
            return;
        }
        if (queueLimit <= 0) {
            return;
        }
        while (queue.size() >= queueLimit) {
            queue.pollFirst();
            if (!queueOverflowLogged) {
                queueOverflowLogged = true;
                LOGGER.warn("Bridge event queue is full ({}); dropping the oldest events until a bot connects", queueLimit);
            }
        }
        queue.addLast(new Event(type, payload));
    }

    /** Called (on the server thread) when a bot attached: drain the queue, then ask the bot to refresh. */
    public void onBotConnected() {
        flushBatches(true);
        final BridgeSink s = sink();
        if (s == null) {
            return;
        }
        int sent = 0;
        Event e;
        while ((e = queue.pollFirst()) != null) {
            s.publish(e.type(), e.payload());
            sent++;
        }
        queueOverflowLogged = false;
        final JsonObject resync = new JsonObject();
        resync.addProperty("queued_events_sent", sent);
        resync.addProperty("schematics", context.getSyncmaticManager().getAll().size());
        s.publish("resync", resync);
    }

    public int queuedEvents() {
        return queue.size();
    }

    // -- schematic lifecycle (called from the sharing code) ---------------------------

    public void onSchematicShared(final ServerPlacement placement) {
        publish("schematic_shared", schematicPayload(placement));
    }

    public void onSchematicUpdated(final ServerPlacement placement) {
        publish("schematic_updated", schematicPayload(placement));
    }

    public void onSchematicRemoved(final ServerPlacement placement) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", placement.getId().toString());
        o.addProperty("name", placement.getName());
        publish("schematic_removed", o);
    }

    public JsonObject schematicPayload(final ServerPlacement placement) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        final JsonObject o = new JsonObject();
        o.add("schematic", BridgeJson.schematic(placement,
                materials == null ? null : materials.getList(placement),
                materials == null ? null : materials.getStats(placement.getId()), hideCoordinates));
        return o;
    }

    // -- material events (MaterialEventListener, server thread) ------------------------

    @Override
    public void onListCreated(final ServerPlacement placement, final MaterialList list) {
        final JsonObject o = schematicPayload(placement);
        o.add("top_remaining", BridgeJson.topRemaining(list, TOP_REMAINING));
        publish("list_created", o);
    }

    @Override
    public void onItemChanged(final ServerPlacement placement, final MaterialEntry entry, final int oldGathered,
                              final PlayerIdentifier editor, final MaterialOp op) {
        final Batch batch = batches.computeIfAbsent(placement.getId(), id -> new Batch(placement, System.currentTimeMillis()));
        final JsonObject change = BridgeJson.entry(entry);
        final JsonObject previous = batch.changes.get(entry.getItemId());
        change.addProperty("old", previous != null && previous.has("old") ? previous.get("old").getAsInt() : oldGathered);
        change.addProperty("new", entry.getGathered());
        change.addProperty("op", op.wireName());
        change.add("editor", BridgeJson.player(editor));
        batch.changes.put(entry.getItemId(), change);
        if (batchSeconds <= 0) {
            flushBatches(true);
        }
    }

    @Override
    public void onItemCompleted(final ServerPlacement placement, final MaterialEntry entry, final PlayerIdentifier editor) {
        final JsonObject o = new JsonObject();
        o.addProperty("schematic_id", placement.getId().toString());
        o.addProperty("schematic", placement.getName());
        o.add("item", BridgeJson.entry(entry));
        o.add("editor", BridgeJson.player(editor));
        publish("item_completed", o);
    }

    @Override
    public void onSchematicCompleted(final ServerPlacement placement, final MaterialList list, final PlayerIdentifier editor) {
        flushBatches(true);
        final JsonObject o = schematicPayload(placement);
        o.add("editor", BridgeJson.player(editor));
        publish("schematic_completed", o);
    }

    /** Server tick: sends batches whose window has passed. */
    public void tick() {
        if (!batches.isEmpty()) {
            flushBatches(false);
        }
    }

    private void flushBatches(final boolean force) {
        if (batches.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final List<UUID> done = new ArrayList<>();
        for (final Map.Entry<UUID, Batch> e : batches.entrySet()) {
            final Batch b = e.getValue();
            if (!force && now - b.startedAt < batchSeconds * 1000L) {
                continue;
            }
            final MaterialTrackingService materials = context.getMaterialTracking();
            final MaterialList list = materials == null ? null : materials.getList(b.placement);
            final JsonObject o = new JsonObject();
            o.addProperty("schematic_id", b.placement.getId().toString());
            o.addProperty("schematic", b.placement.getName());
            final JsonArray changes = new JsonArray();
            b.changes.values().forEach(changes::add);
            o.add("changes", changes);
            o.add("materials", BridgeJson.summary(list));
            o.add("top_remaining", BridgeJson.topRemaining(list, TOP_REMAINING));
            publish("item_changed", o);
            done.add(e.getKey());
        }
        done.forEach(batches::remove);
    }
}
