package com.steelaspect.cytrasyncmatica.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.CombinedList;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialEventListener;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.projects.Project;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side projects: named sets of shared schematics with one combined
 * material list. Stored in {@code <world>/cytra-syncmatica/projects.json}.
 * All methods run on the server thread; the file is written on the material
 * service's background thread.
 */
public class ProjectService implements MaterialEventListener {
    private static final Logger LOGGER = LogManager.getLogger(ProjectService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Server-side hooks (the Discord bridge). */
    public interface Listener {
        /** {@code action} is one of created, renamed, member_added, member_removed, deleted. */
        default void onProjectChanged(Project project, String action, PlayerIdentifier by) {
        }

        default void onProjectCompleted(Project project, PlayerIdentifier editor) {
        }
    }

    public enum Outcome { OK, NO_CHANGE, UNKNOWN_ITEM, NO_LIST, DISABLED }

    private Context context;
    private final Map<UUID, Project> projects = new LinkedHashMap<>();
    private final Map<UUID, Boolean> completeState = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean started;

    public void setContext(final Context context) {
        this.context = context;
    }

    public void addListener(final Listener l) {
        listeners.add(l);
    }

    public void removeListener(final Listener l) {
        listeners.remove(l);
    }

    // -- lifecycle ---------------------------------------------------------------

    public void startup() {
        projects.clear();
        completeState.clear();
        load();
        if (context.getMaterialTracking() != null) {
            context.getMaterialTracking().addListener(this);
        }
        started = true;
    }

    public void shutdown() {
        started = false;
        if (context.getMaterialTracking() != null) {
            context.getMaterialTracking().removeListener(this);
        }
        projects.clear();
        completeState.clear();
    }

    // -- queries -----------------------------------------------------------------

    public List<Project> all() {
        final List<Project> out = new ArrayList<>(projects.values());
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Collections.unmodifiableList(out);
    }

    public Project get(final UUID id) {
        return id == null ? null : projects.get(id);
    }

    /** Exact name match first (case-insensitive), then a unique prefix. */
    public Optional<Project> findByName(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String n = name.trim();
        Project prefix = null;
        int prefixHits = 0;
        for (final Project p : projects.values()) {
            if (p.getName().equalsIgnoreCase(n)) {
                return Optional.of(p);
            }
            if (p.getName().toLowerCase().startsWith(n.toLowerCase())) {
                prefix = p;
                prefixHits++;
            }
        }
        return prefixHits == 1 ? Optional.of(prefix) : Optional.empty();
    }

    /** Projects that contain this placement. */
    public List<Project> containing(final UUID placementId) {
        final List<Project> out = new ArrayList<>();
        final String key = placementId.toString();
        for (final Project p : projects.values()) {
            if (p.hasMember(key)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Member placements that still exist, in project order. */
    public List<ServerPlacement> members(final Project project) {
        final List<ServerPlacement> out = new ArrayList<>();
        for (final String key : project.getMembers()) {
            try {
                final ServerPlacement p = context.getSyncmaticManager().getPlacement(UUID.fromString(key));
                if (p != null) {
                    out.add(p);
                }
            } catch (final IllegalArgumentException ignored) {
                // not a placement id
            }
        }
        return out;
    }

    public CombinedList.Combined combined(final Project project) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        final List<CombinedList.Source> sources = new ArrayList<>();
        for (final ServerPlacement p : members(project)) {
            sources.add(new CombinedList.Source(p.getId().toString(), p.getName(), materials == null ? null : materials.getList(p)));
        }
        return CombinedList.combine(sources);
    }

    public boolean isComplete(final Project project) {
        final CombinedList.Combined c = combined(project);
        return c.missingLists() == 0 && !c.list().isEmpty() && c.list().isComplete();
    }

    // -- changes (caller has checked the manage permission) ------------------------

    public Project create(final String name, final PlayerIdentifier by) {
        final String clean = Project.cleanName(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("project name is empty");
        }
        for (final Project p : projects.values()) {
            if (p.getName().equalsIgnoreCase(clean)) {
                throw new IllegalArgumentException("a project named '" + p.getName() + "' already exists");
            }
        }
        if (projects.size() >= Project.MAX_PROJECTS) {
            throw new IllegalArgumentException("too many projects (max " + Project.MAX_PROJECTS + ")");
        }
        final Project p = new Project(UUID.randomUUID(), clean, by == null ? "" : by.getName(), System.currentTimeMillis());
        projects.put(p.getId(), p);
        changed(p, "created", by);
        return p;
    }

    public void rename(final Project project, final String name, final PlayerIdentifier by) {
        final String clean = Project.cleanName(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("project name is empty");
        }
        for (final Project p : projects.values()) {
            if (p != project && p.getName().equalsIgnoreCase(clean)) {
                throw new IllegalArgumentException("a project named '" + p.getName() + "' already exists");
            }
        }
        project.setName(clean);
        changed(project, "renamed", by);
    }

    public boolean delete(final Project project, final PlayerIdentifier by) {
        if (projects.remove(project.getId()) == null) {
            return false;
        }
        completeState.remove(project.getId());
        changed(project, "deleted", by);
        return true;
    }

    public boolean addMember(final Project project, final ServerPlacement placement, final PlayerIdentifier by) {
        if (!project.addMember(placement.getId().toString())) {
            return false;
        }
        completeState.remove(project.getId());
        changed(project, "member_added", by);
        return true;
    }

    public boolean removeMember(final Project project, final ServerPlacement placement, final PlayerIdentifier by) {
        if (!project.removeMember(placement.getId().toString())) {
            return false;
        }
        completeState.remove(project.getId());
        changed(project, "member_removed", by);
        return true;
    }

    /** A shared schematic went away: drop it from every project. */
    public void onPlacementRemoved(final UUID placementId) {
        for (final Project p : containing(placementId)) {
            p.removeMember(placementId.toString());
            completeState.remove(p.getId());
            changed(p, "member_removed", null);
        }
    }

    private void changed(final Project project, final String action, final PlayerIdentifier by) {
        save();
        if (context.getCommunicationManager() instanceof ServerCommunicationManager comms) {
            comms.broadcastProjects();
        }
        for (final Listener l : listeners) {
            l.onProjectChanged(project, action, by);
        }
    }

    // -- combined edits ------------------------------------------------------------

    /** Applies one edit to the combined item, filling the first schematic with something left first. */
    public Outcome apply(final Project project, final String itemId, final MaterialOp op, final int amount, final PlayerIdentifier editor) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        if (materials == null || !materials.isEnabled()) {
            return Outcome.DISABLED;
        }
        final CombinedList.Combined c = combined(project);
        if (c.list().isEmpty() && c.missingLists() > 0) {
            return Outcome.NO_LIST;
        }
        final List<CombinedList.Part> parts = c.breakdown().get(itemId);
        if (parts == null) {
            return Outcome.UNKNOWN_ITEM;
        }
        final Map<String, Integer> targets = CombinedList.distribute(parts, op, amount);
        boolean any = false;
        for (final Map.Entry<String, Integer> t : targets.entrySet()) {
            final ServerPlacement p = context.getSyncmaticManager().getPlacement(UUID.fromString(t.getKey()));
            if (p == null) {
                continue;
            }
            if (materials.apply(p, itemId, MaterialOp.SET, t.getValue(), editor) == MaterialTrackingService.Outcome.OK) {
                any = true;
            }
        }
        return any ? Outcome.OK : Outcome.NO_CHANGE;
    }

    // -- completion (MaterialEventListener) -----------------------------------------

    @Override
    public void onItemChanged(final ServerPlacement placement, final MaterialEntry entry, final int oldGathered,
                              final PlayerIdentifier editor, final MaterialOp op) {
        for (final Project p : containing(placement.getId())) {
            final boolean now = isComplete(p);
            final Boolean before = completeState.put(p.getId(), now);
            if (before != null && !before && now) {
                for (final Listener l : listeners) {
                    l.onProjectCompleted(p, editor);
                }
            }
        }
    }

    @Override
    public void onListCreated(final ServerPlacement placement, final MaterialList list) {
        for (final Project p : containing(placement.getId())) {
            completeState.put(p.getId(), isComplete(p));
        }
    }

    // -- persistence -----------------------------------------------------------------

    private Path file() {
        final File world = context.getWorldFolder();
        final File root = world != null ? world : context.getConfigFolder();
        return new File(new File(root, Syncmatica.MOD_ID), "projects.json").toPath();
    }

    private void load() {
        final Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("projects") && o.get("projects").isJsonArray()) {
                for (final JsonElement e : o.getAsJsonArray("projects")) {
                    final Project p = e.isJsonObject() ? Project.fromJson(e.getAsJsonObject()) : null;
                    if (p != null) {
                        projects.put(p.getId(), p);
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}; starting without projects", f, e);
        }
    }

    private void save() {
        final JsonObject o = new JsonObject();
        final JsonArray arr = new JsonArray();
        for (final Project p : projects.values()) {
            arr.add(p.toJson());
        }
        o.add("projects", arr);
        final String json = GSON.toJson(o);
        final Path f = file();
        final Runnable write = () -> {
            try {
                Files.createDirectories(f.getParent());
                final Path tmp = f.resolveSibling(f.getFileName() + ".new");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                LOGGER.warn("Could not write {}", f, e);
            }
        };
        if (started && context.getMaterialTracking() != null) {
            context.getMaterialTracking().runInBackground(write);
        } else {
            write.run();
        }
    }
}
