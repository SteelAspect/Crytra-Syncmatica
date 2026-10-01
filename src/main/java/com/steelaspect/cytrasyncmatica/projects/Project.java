package com.steelaspect.cytrasyncmatica.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A named set of shared schematics tracked together: one combined material
 * list, one progress number. Members are placement ids (connected mode) or
 * local schematic keys (client-only mode); both are stored as strings.
 */
public final class Project {
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_MEMBERS = 64;
    public static final int MAX_PROJECTS = 256;

    private final UUID id;
    private String name;
    private final List<String> members = new ArrayList<>();
    private String createdBy;
    private long createdAt;

    public Project(final UUID id, final String name, final String createdBy, final long createdAt) {
        this.id = id;
        this.name = cleanName(name);
        this.createdBy = createdBy == null ? "" : createdBy;
        this.createdAt = createdAt;
    }

    public static String cleanName(final String name) {
        final String t = (name == null ? "" : name).trim().replaceAll("\\s+", " ");
        return t.length() > MAX_NAME_LENGTH ? t.substring(0, MAX_NAME_LENGTH) : t;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = cleanName(name);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** Member keys in the order they were added. */
    public List<String> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public boolean hasMember(final String key) {
        return members.contains(key);
    }

    public boolean addMember(final String key) {
        if (key == null || key.isEmpty() || members.contains(key) || members.size() >= MAX_MEMBERS) {
            return false;
        }
        members.add(key);
        return true;
    }

    public boolean removeMember(final String key) {
        return members.remove(key);
    }

    public Project copy() {
        final Project p = new Project(id, name, createdBy, createdAt);
        p.members.addAll(members);
        return p;
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("name", name);
        o.addProperty("created_by", createdBy);
        o.addProperty("created_at", createdAt);
        final JsonArray m = new JsonArray();
        for (final String s : members) {
            m.add(s);
        }
        o.add("members", m);
        return o;
    }

    public static Project fromJson(final JsonObject o) {
        if (o == null || !o.has("id") || !o.has("name")) {
            return null;
        }
        final UUID id;
        try {
            id = UUID.fromString(o.get("id").getAsString());
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final Project p = new Project(id, o.get("name").getAsString(),
                o.has("created_by") ? o.get("created_by").getAsString() : "",
                o.has("created_at") ? o.get("created_at").getAsLong() : 0L);
        if (o.has("members") && o.get("members").isJsonArray()) {
            for (final JsonElement e : o.getAsJsonArray("members")) {
                if (e.isJsonPrimitive()) {
                    p.addMember(e.getAsString());
                }
            }
        }
        return p.name.isEmpty() ? null : p;
    }

    public void write(final PacketByteBuf buf) {
        buf.writeUuid(id);
        buf.writeString(name, MAX_NAME_LENGTH);
        buf.writeString(createdBy, ProtocolLimits.MAX_PLAYER_NAME_LENGTH);
        buf.writeLong(createdAt);
        buf.writeVarInt(members.size());
        for (final String m : members) {
            buf.writeString(m, ProtocolLimits.MAX_FILE_NAME_LENGTH);
        }
    }

    public static Project read(final PacketByteBuf buf) {
        final Project p = new Project(buf.readUuid(), buf.readString(MAX_NAME_LENGTH),
                buf.readString(ProtocolLimits.MAX_PLAYER_NAME_LENGTH), buf.readLong());
        final int n = ProtocolLimits.requireCount(buf.readVarInt(), MAX_MEMBERS, "project members");
        for (int i = 0; i < n; i++) {
            p.addMember(buf.readString(ProtocolLimits.MAX_FILE_NAME_LENGTH));
        }
        return p;
    }
}
