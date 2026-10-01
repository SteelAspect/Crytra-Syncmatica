package com.steelaspect.cytrasyncmatica.materials;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * One item of a schematic's material list: how many the build needs and how
 * many the team has gathered, plus who last touched the gathered count. There
 * is exactly one shared gathered count per item per schematic; the editor
 * fields are history, never a lock.
 */
public final class MaterialEntry {
    public static final String UNKNOWN_EDITOR = "";

    private final String itemId;
    private int required;
    private int gathered;
    private UUID editorUuid;
    private String editorName = UNKNOWN_EDITOR;
    private long editedAt;

    public MaterialEntry(final String itemId, final int required) {
        this.itemId = itemId;
        this.required = Math.max(0, required);
    }

    public String getItemId() {
        return itemId;
    }

    public int getRequired() {
        return required;
    }

    public void setRequired(final int required) {
        this.required = Math.max(0, required);
    }

    public int getGathered() {
        return gathered;
    }

    /** Clamped to [0, required]; the caller records who did it. */
    public void setGathered(final int gathered, final UUID editorUuid, final String editorName, final long when) {
        this.gathered = Math.max(0, Math.min(required, gathered));
        this.editorUuid = editorUuid;
        this.editorName = editorName == null ? UNKNOWN_EDITOR : editorName;
        this.editedAt = when;
    }

    public int getRemaining() {
        return Math.max(0, required - gathered);
    }

    public boolean isComplete() {
        return gathered >= required;
    }

    public UUID getEditorUuid() {
        return editorUuid;
    }

    public String getEditorName() {
        return editorName;
    }

    public long getEditedAt() {
        return editedAt;
    }

    public MaterialEntry copy() {
        final MaterialEntry copy = new MaterialEntry(itemId, required);
        copy.gathered = gathered;
        copy.editorUuid = editorUuid;
        copy.editorName = editorName;
        copy.editedAt = editedAt;
        return copy;
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("item", itemId);
        o.addProperty("required", required);
        o.addProperty("gathered", gathered);
        if (editorUuid != null) {
            o.addProperty("editor_uuid", editorUuid.toString());
        }
        if (!editorName.isEmpty()) {
            o.addProperty("editor_name", editorName);
        }
        if (editedAt > 0L) {
            o.addProperty("edited_at", editedAt);
        }
        return o;
    }

    public static MaterialEntry fromJson(final JsonObject o) {
        if (o == null || !o.has("item")) {
            return null;
        }
        final MaterialEntry e = new MaterialEntry(o.get("item").getAsString(), o.has("required") ? o.get("required").getAsInt() : 0);
        UUID uuid = null;
        if (o.has("editor_uuid")) {
            try {
                uuid = UUID.fromString(o.get("editor_uuid").getAsString());
            } catch (final IllegalArgumentException ignored) {
                uuid = null;
            }
        }
        e.setGathered(o.has("gathered") ? o.get("gathered").getAsInt() : 0, uuid,
                o.has("editor_name") ? o.get("editor_name").getAsString() : UNKNOWN_EDITOR,
                o.has("edited_at") ? o.get("edited_at").getAsLong() : 0L);
        return e;
    }
}
