package com.steelaspect.cytrasyncmatica.client.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.client.network.ClientCommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.PacketType;
import com.steelaspect.cytrasyncmatica.projects.Project;
import fi.dy.masa.malilib.util.StringUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The client's projects: in connected mode a copy of the server's list (kept
 * fresh by {@code project_list} packets, edits sent as {@code project_manage});
 * otherwise a local file {@code config/cytra-syncmatica/client/<world-or-server>/projects.json}
 * whose members are local schematic keys. Render thread only.
 */
public final class ClientProjects {
    private static final Logger LOGGER = LogManager.getLogger(ClientProjects.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ClientProjects INSTANCE = new ClientProjects();

    private final Map<UUID, Project> server = new LinkedHashMap<>();
    private final Map<UUID, Project> local = new LinkedHashMap<>();
    private boolean serverRequested;
    private boolean serverReceived;
    private String localKey;

    private ClientProjects() {
    }

    public static ClientProjects getInstance() {
        return INSTANCE;
    }

    private boolean connected() {
        return MaterialTrackerClient.getInstance().currentMode() == MaterialMode.CONNECTED;
    }

    public List<Project> all() {
        if (connected()) {
            if (!serverRequested) {
                requestFromServer();
            }
            final List<Project> out = new ArrayList<>(server.values());
            out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return out;
        }
        loadLocal();
        final List<Project> out = new ArrayList<>(local.values());
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    public Project get(final UUID id) {
        return connected() ? server.get(id) : local.get(id);
    }

    public boolean isServerListKnown() {
        return !connected() || serverReceived;
    }

    // -- edits: sent to the server, or applied to the local file ------------------------

    /** Returns an error key, or null when the request went out / applied. */
    public String create(final String name) {
        final String clean = Project.cleanName(name);
        if (clean.isEmpty()) {
            return "cytra-syncmatica.error.project.rejected";
        }
        if (connected()) {
            sendManage(0, clean, null, null);
            return null;
        }
        loadLocal();
        for (final Project p : local.values()) {
            if (p.getName().equalsIgnoreCase(clean)) {
                return "cytra-syncmatica.error.project.exists";
            }
        }
        final MinecraftClient mc = MinecraftClient.getInstance();
        final Project p = new Project(UUID.randomUUID(), clean, mc.player == null ? "" : mc.player.getGameProfile().name(), System.currentTimeMillis());
        local.put(p.getId(), p);
        saveLocal();
        return null;
    }

    public void delete(final Project project) {
        if (connected()) {
            sendManage(1, project.getName(), project.getId(), null);
            return;
        }
        local.remove(project.getId());
        saveLocal();
    }

    public void addMember(final Project project, final TrackedSchematic schematic) {
        if (connected()) {
            if (schematic.isShared()) {
                sendManage(2, project.getName(), project.getId(), schematic.server().getId());
            }
            return;
        }
        final Project p = local.get(project.getId());
        if (p != null && p.addMember(schematic.key())) {
            saveLocal();
        }
    }

    public void removeMember(final Project project, final TrackedSchematic schematic) {
        if (connected()) {
            if (schematic.isShared()) {
                sendManage(3, project.getName(), project.getId(), schematic.server().getId());
            }
            return;
        }
        final Project p = local.get(project.getId());
        if (p != null && p.removeMember(schematic.key())) {
            saveLocal();
        }
    }

    private static final UUID NIL = new UUID(0L, 0L);

    private void sendManage(final int action, final String name, final UUID projectId, final UUID placementId) {
        final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        if (context == null || !(context.getCommunicationManager() instanceof ClientCommunicationManager comms)) {
            return;
        }
        final ExchangeTarget target = comms.getServer();
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(action);
        buf.writeString(name, Project.MAX_NAME_LENGTH);
        buf.writeUuid(projectId == null ? NIL : projectId);
        buf.writeUuid(placementId == null ? NIL : placementId);
        target.sendPacket(PacketType.PROJECT_MANAGE.toIdentifier(target.getProtocolFlavor()), buf, context);
    }

    // -- server list ------------------------------------------------------------------

    public void requestFromServer() {
        final Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        if (context == null || !(context.getCommunicationManager() instanceof ClientCommunicationManager comms)) {
            return;
        }
        serverRequested = true;
        final ExchangeTarget target = comms.getServer();
        target.sendPacket(PacketType.PROJECT_REQUEST.toIdentifier(target.getProtocolFlavor()), new PacketByteBuf(Unpooled.buffer()), context);
    }

    /** Called by the network layer. */
    public void onServerList(final List<Project> projects) {
        server.clear();
        for (final Project p : projects) {
            server.put(p.getId(), p);
        }
        serverReceived = true;
        MaterialTrackerClient.getInstance().notifyChanged();
    }

    // -- local file -------------------------------------------------------------------

    private Path localFile() {
        final String name = StringUtils.getWorldOrServerName();
        final String key = name == null || name.isEmpty() ? "unknown" : name;
        return Path.of("config", Syncmatica.MOD_ID, "client", TrackedSchematic.sanitize(key), "projects.json");
    }

    private void loadLocal() {
        final Path f = localFile();
        if (f.toString().equals(localKey)) {
            return;
        }
        localKey = f.toString();
        local.clear();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("projects") && o.get("projects").isJsonArray()) {
                for (final JsonElement e : o.getAsJsonArray("projects")) {
                    final Project p = e.isJsonObject() ? Project.fromJson(e.getAsJsonObject()) : null;
                    if (p != null) {
                        local.put(p.getId(), p);
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", f, e);
        }
    }

    private void saveLocal() {
        final Path f = localFile();
        final JsonObject o = new JsonObject();
        final JsonArray arr = new JsonArray();
        for (final Project p : local.values()) {
            arr.add(p.toJson());
        }
        o.add("projects", arr);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.warn("Could not write {}", f, e);
        }
        MaterialTrackerClient.getInstance().notifyChanged();
    }

    /** On disconnect. */
    public void reset() {
        server.clear();
        serverRequested = false;
        serverReceived = false;
        local.clear();
        localKey = null;
    }

    public List<Project> unmodifiable() {
        return Collections.unmodifiableList(all());
    }
}
