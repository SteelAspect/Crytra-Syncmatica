package com.steelaspect.cytrasyncmatica.materials;

import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

/** Byte layout of the material packets, shared by both sides. */
public final class MaterialWire {
    public static final int MAX_ENTRIES = MaterialListExtractor.MAX_ENTRIES;

    private MaterialWire() {
    }

    public static void writeEntry(final PacketByteBuf buf, final MaterialEntry e) {
        buf.writeString(e.getItemId(), ProtocolLimits.MAX_ITEM_ID_LENGTH);
        buf.writeVarInt(e.getRequired());
        buf.writeVarInt(e.getGathered());
        final boolean hasEditor = e.getEditorUuid() != null;
        buf.writeBoolean(hasEditor);
        if (hasEditor) {
            buf.writeUuid(e.getEditorUuid());
            buf.writeString(e.getEditorName(), ProtocolLimits.MAX_PLAYER_NAME_LENGTH);
        }
        buf.writeLong(e.getEditedAt());
    }

    public static MaterialEntry readEntry(final PacketByteBuf buf) {
        final String itemId = buf.readString(ProtocolLimits.MAX_ITEM_ID_LENGTH);
        final int required = buf.readVarInt();
        final int gathered = buf.readVarInt();
        UUID editor = null;
        String name = MaterialEntry.UNKNOWN_EDITOR;
        if (buf.readBoolean()) {
            editor = buf.readUuid();
            name = buf.readString(ProtocolLimits.MAX_PLAYER_NAME_LENGTH);
        }
        final long at = buf.readLong();
        final MaterialEntry e = new MaterialEntry(itemId, required);
        e.setGathered(gathered, editor, name, at);
        return e;
    }

    public static void writeList(final PacketByteBuf buf, final MaterialList list) {
        final int n = Math.min(list.size(), MAX_ENTRIES);
        buf.writeVarInt(n);
        int written = 0;
        for (final MaterialEntry e : list.getEntries()) {
            if (written++ >= n) {
                break;
            }
            writeEntry(buf, e);
        }
    }

    public static MaterialList readList(final PacketByteBuf buf) {
        final int n = ProtocolLimits.requireCount(buf.readVarInt(), MAX_ENTRIES, "material count");
        final MaterialList list = new MaterialList();
        for (int i = 0; i < n; i++) {
            list.put(readEntry(buf));
        }
        return list;
    }
}
