package com.steelaspect.cytrasyncmatica.client.materials;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.projects.Project;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

import java.util.Locale;
import java.util.Objects;

/**
 * A schematic the tracker can show: a shared placement (connected mode) or a
 * local Litematica placement (client-only / singleplayer). {@link #key()} is
 * stable across sessions and safe as a file name.
 */
public final class TrackedSchematic {
    private final String key;
    private final String name;
    private final ServerPlacement server;
    private final SchematicPlacement local;
    private final Project project;

    private TrackedSchematic(final String key, final String name, final ServerPlacement server, final SchematicPlacement local) {
        this(key, name, server, local, null);
    }

    private TrackedSchematic(final String key, final String name, final ServerPlacement server, final SchematicPlacement local, final Project project) {
        this.key = key;
        this.name = name;
        this.server = server;
        this.local = local;
        this.project = project;
    }

    /** A project: its combined list is shown, edits are split over its members. */
    public static TrackedSchematic project(final Project project) {
        return new TrackedSchematic("project-" + project.getId(), project.getName(), null, null, project);
    }

    public boolean isProject() {
        return project != null;
    }

    public Project project() {
        return project;
    }

    public static TrackedSchematic shared(final ServerPlacement placement) {
        return new TrackedSchematic(placement.getId().toString(), placement.getName(), placement, null);
    }

    public static TrackedSchematic local(final SchematicPlacement placement) {
        return new TrackedSchematic("local-" + sanitize(placement.getName()), placement.getName(), null, placement);
    }

    public static String sanitize(final String s) {
        final String cleaned = (s == null ? "" : s).replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "schematic" : cleaned.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public boolean isShared() {
        return server != null;
    }

    public ServerPlacement server() {
        return server;
    }

    public SchematicPlacement local() {
        return local;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof TrackedSchematic t && t.key.equals(key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
