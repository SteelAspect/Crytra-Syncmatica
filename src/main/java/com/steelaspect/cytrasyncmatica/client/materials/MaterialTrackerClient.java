package com.steelaspect.cytrasyncmatica.client.materials;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.Feature;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.client.ClientConfigs;
import com.steelaspect.cytrasyncmatica.client.network.ClientCommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.FeatureSet;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.StringUtils;
import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The client's one view of material tracking, whatever the mode:
 * <ul>
 *   <li>connected: lists come from the server (requested on demand, kept fresh by
 *       update packets); edits are sent to the server;</li>
 *   <li>client-only / singleplayer: required counts come from Litematica's own
 *       material list, gathered counts from {@link LocalMaterialStore}; nothing is
 *       sent anywhere.</li>
 * </ul>
 * Everything here runs on the render thread.
 */
public final class MaterialTrackerClient {
    private static final MaterialTrackerClient INSTANCE = new MaterialTrackerClient();
    private static final int INVENTORY_POLL_TICKS = 10;
    private static final long LOCAL_RECOUNT_INTERVAL_MILLIS = 15_000L;

    private final Map<UUID, MaterialList> sharedLists = new HashMap<>();
    private final Map<UUID, String> sharedErrors = new HashMap<>();
    private final Set<UUID> requested = new HashSet<>();
    private final Map<String, MaterialList> localLists = new LinkedHashMap<>();
    private final Map<String, Long> localRecountAt = new HashMap<>();
    private final Set<String> localPending = new HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Map<String, Integer> inventory = Collections.emptyMap();
    private int inventoryTicks;
    private LocalMaterialStore localStore;
    private String localStoreKey;

    private MaterialTrackerClient() {
    }

    public static MaterialTrackerClient getInstance() {
        return INSTANCE;
    }

    // -- mode ----------------------------------------------------------------------

    public MaterialMode currentMode() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.isIntegratedServerRunning() || client.isInSingleplayer()) {
            return MaterialMode.SINGLEPLAYER;
        }
        final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        if (context != null && context.isStarted()
                && context.getCommunicationManager() instanceof ClientCommunicationManager comms) {
            final FeatureSet features = comms.getServer().getFeatureSet();
            if (features != null && features.hasFeature(Feature.MATERIAL_TRACKING)) {
                return MaterialMode.CONNECTED;
            }
        }
        return MaterialMode.CLIENT_ONLY;
    }

    /** Short status line for the screen: the mode plus whether the Discord bridge can be on. */
    public String describeMode() {
        final MaterialMode mode = currentMode();
        final String modeText = StringUtils.translate(mode.getLabelKey());
        final String discord = mode == MaterialMode.CONNECTED
                ? StringUtils.translate("cytra-syncmatica.gui.label.discord.server_decides")
                : StringUtils.translate("cytra-syncmatica.gui.label.discord.off");
        return modeText + " · " + discord;
    }

    public boolean isLitematicaPresent() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    // -- schematics ----------------------------------------------------------------

    public List<TrackedSchematic> availableSchematics() {
        final List<TrackedSchematic> out = new ArrayList<>();
        if (currentMode() == MaterialMode.CONNECTED) {
            final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
            if (context != null) {
                for (final ServerPlacement p : context.getSyncmaticManager().getAll()) {
                    out.add(TrackedSchematic.shared(p));
                }
            }
            out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return out;
        }
        for (final SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            out.add(TrackedSchematic.local(p));
        }
        return out;
    }

    public TrackedSchematic find(final String key) {
        for (final TrackedSchematic t : availableSchematics()) {
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** The schematic the player last looked at, else Litematica's selected placement, else the first one. */
    public TrackedSchematic defaultSchematic() {
        final TrackedSchematic last = find(MaterialTrackerPreferences.getLastSchematicKey());
        if (last != null) {
            return last;
        }
        final List<TrackedSchematic> all = availableSchematics();
        if (currentMode() != MaterialMode.CONNECTED) {
            final SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            if (selected != null) {
                for (final TrackedSchematic t : all) {
                    if (t.local() == selected) {
                        return t;
                    }
                }
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    // -- lists ---------------------------------------------------------------------

    /** null while the list is still being fetched or counted. */
    public MaterialList getList(final TrackedSchematic schematic) {
        if (schematic == null) {
            return null;
        }
        if (schematic.isShared()) {
            final UUID id = schematic.server().getId();
            final MaterialList list = sharedLists.get(id);
            if (list == null && requested.add(id)) {
                requestFromServer(id);
            }
            return list;
        }
        return localList(schematic);
    }

    /** The reason a shared list is empty, if the server told us one. */
    public String getError(final TrackedSchematic schematic) {
        if (schematic == null || !schematic.isShared()) {
            return null;
        }
        return sharedErrors.get(schematic.server().getId());
    }

    public boolean isCounting(final TrackedSchematic schematic) {
        return schematic != null && !schematic.isShared() && localPending.contains(schematic.key());
    }

    public void refresh(final TrackedSchematic schematic) {
        if (schematic == null) {
            return;
        }
        if (schematic.isShared()) {
            requestFromServer(schematic.server().getId());
        } else {
            localRecountAt.remove(schematic.key());
            localList(schematic);
        }
    }

    private void requestFromServer(final UUID placementId) {
        final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        if (context == null || !(context.getCommunicationManager() instanceof ClientCommunicationManager comms)) {
            return;
        }
        final ExchangeTarget server = comms.getServer();
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeUuid(placementId);
        server.sendPacket(PacketType.MATERIAL_REQUEST.toIdentifier(server.getProtocolFlavor()), buf, context);
    }

    /** Called by the network layer when the server sends a whole list. */
    public void onServerList(final UUID placementId, final MaterialList list, final String error) {
        sharedLists.put(placementId, list);
        if (error == null || error.isEmpty()) {
            sharedErrors.remove(placementId);
        } else {
            sharedErrors.put(placementId, error);
        }
        requested.remove(placementId);
        notifyListeners();
    }

    /** Called by the network layer when one entry changed on the server. */
    public void onServerUpdate(final UUID placementId, final MaterialEntry entry) {
        final MaterialList list = sharedLists.get(placementId);
        if (list == null) {
            return;
        }
        list.put(entry);
        notifyListeners();
    }

    private MaterialList localList(final TrackedSchematic schematic) {
        final SchematicPlacement placement = schematic.local();
        if (placement == null) {
            return null;
        }
        final MaterialListBase litematica = placement.getMaterialList();
        final List<MaterialListEntry> entries = litematica == null ? List.of() : litematica.getMaterialsAll();
        final long now = System.currentTimeMillis();
        final Long nextRecount = localRecountAt.get(schematic.key());
        if (litematica != null && (entries.isEmpty() || nextRecount == null || now >= nextRecount) && !localPending.contains(schematic.key())) {
            localPending.add(schematic.key());
            localRecountAt.put(schematic.key(), now + LOCAL_RECOUNT_INTERVAL_MILLIS);
            litematica.setCompletionListener(() -> {
                localPending.remove(schematic.key());
                rebuildLocal(schematic, litematica.getMaterialsAll());
                notifyListeners();
            });
            litematica.reCreateMaterialList();
        }
        MaterialList list = localLists.get(schematic.key());
        if (list == null && !entries.isEmpty()) {
            list = rebuildLocal(schematic, entries);
        }
        return list;
    }

    private MaterialList rebuildLocal(final TrackedSchematic schematic, final List<MaterialListEntry> entries) {
        final Map<String, Integer> required = new LinkedHashMap<>();
        for (final MaterialListEntry e : entries) {
            final ItemStack stack = e.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            required.merge(Registries.ITEM.getId(stack.getItem()).toString(), e.getCountTotal(), Integer::sum);
        }
        MaterialList list = localLists.get(schematic.key());
        if (list == null) {
            list = store().load(schematic.key());
            localLists.put(schematic.key(), list);
        }
        list.applyRequirements(required);
        store().save(schematic.key(), list);
        return list;
    }

    private LocalMaterialStore store() {
        final String name = StringUtils.getWorldOrServerName();
        final String key = name == null || name.isEmpty() ? "unknown" : name;
        if (localStore == null || !key.equals(localStoreKey)) {
            localStore = new LocalMaterialStore(key);
            localStoreKey = key;
            localLists.clear();
        }
        return localStore;
    }

    // -- edits ---------------------------------------------------------------------

    public void edit(final TrackedSchematic schematic, final String itemId, final MaterialOp op, final int amount) {
        if (schematic == null) {
            return;
        }
        if (schematic.isShared()) {
            final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
            if (context == null || !(context.getCommunicationManager() instanceof ClientCommunicationManager comms)) {
                return;
            }
            final ExchangeTarget server = comms.getServer();
            final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeUuid(schematic.server().getId());
            buf.writeString(itemId, ProtocolLimits.MAX_ITEM_ID_LENGTH);
            buf.writeByte(op.ordinal());
            buf.writeInt(amount);
            server.sendPacket(PacketType.MATERIAL_EDIT.toIdentifier(server.getProtocolFlavor()), buf, context);
            return;
        }
        final MaterialList list = localLists.get(schematic.key());
        if (list == null) {
            return;
        }
        final MaterialEntry entry = list.get(itemId);
        if (entry == null) {
            return;
        }
        final int old = entry.getGathered();
        final int target = switch (op) {
            case ADD -> (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) old + amount));
            case SET -> amount;
            case DONE -> entry.getRequired();
            case RESET -> 0;
        };
        final MinecraftClient client = MinecraftClient.getInstance();
        final UUID self = client.player == null ? null : client.player.getUuid();
        final String selfName = client.player == null ? "" : client.player.getGameProfile().name();
        entry.setGathered(target, self, selfName, System.currentTimeMillis());
        store().save(schematic.key(), list);
        notifyListeners();
    }

    /** "Add from inventory": gathered += min(in inventory, remaining) for every item of the list. */
    public int addFromInventory(final TrackedSchematic schematic) {
        final MaterialList list = getList(schematic);
        if (list == null) {
            return 0;
        }
        final Map<String, Integer> inv = InventoryCounter.count(ClientConfigs.General.AUTO_COUNT_CONTAINERS.getBooleanValue());
        int changed = 0;
        for (final MaterialEntry e : list.copyEntries()) {
            final int have = inv.getOrDefault(e.getItemId(), 0);
            final int add = Math.min(have, e.getRemaining());
            if (add > 0) {
                edit(schematic, e.getItemId(), MaterialOp.ADD, add);
                changed++;
            }
        }
        return changed;
    }

    // -- inventory -----------------------------------------------------------------

    public int inInventory(final String itemId) {
        return inventory.getOrDefault(itemId, 0);
    }

    public void tick() {
        if (!MaterialTrackerPreferences.isAutoCount()) {
            if (!inventory.isEmpty()) {
                inventory = Collections.emptyMap();
                notifyListeners();
            }
            return;
        }
        if (++inventoryTicks < INVENTORY_POLL_TICKS) {
            return;
        }
        inventoryTicks = 0;
        final Map<String, Integer> fresh = InventoryCounter.count(ClientConfigs.General.AUTO_COUNT_CONTAINERS.getBooleanValue());
        if (!fresh.equals(inventory)) {
            inventory = fresh;
            notifyListeners();
        }
    }

    // -- listeners / lifecycle ---------------------------------------------------------

    public void addListener(final Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(final Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (final Runnable r : listeners) {
            try {
                r.run();
            } catch (final RuntimeException ignored) {
                // a closed screen must never break the others
            }
        }
    }

    /** On disconnect: drop everything that belonged to the old world or server. */
    public void reset() {
        sharedLists.clear();
        sharedErrors.clear();
        requested.clear();
        localLists.clear();
        localRecountAt.clear();
        localPending.clear();
        inventory = Collections.emptyMap();
        localStore = null;
        localStoreKey = null;
    }
}
