package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

final class MaterialWireTest {
    @Test
    void listSurvivesTheWire() {
        final MaterialList list = new MaterialList();
        list.put(new MaterialEntry("minecraft:stone", 300));
        final UUID who = UUID.randomUUID();
        list.get("minecraft:stone").setGathered(12, who, "Alex", 77L);
        list.put(new MaterialEntry("minecraft:oak_log", 7));

        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        MaterialWire.writeList(buf, list);
        final MaterialList back = MaterialWire.readList(buf);

        assertEquals(2, back.size());
        assertEquals(300, back.get("minecraft:stone").getRequired());
        assertEquals(12, back.get("minecraft:stone").getGathered());
        assertEquals(who, back.get("minecraft:stone").getEditorUuid());
        assertEquals("Alex", back.get("minecraft:stone").getEditorName());
        assertEquals(77L, back.get("minecraft:stone").getEditedAt());
        assertNull(back.get("minecraft:oak_log").getEditorUuid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void opsRoundTripByOrdinalAndWireName() {
        for (final MaterialOp op : MaterialOp.values()) {
            assertEquals(op, MaterialOp.fromOrdinal(op.ordinal()));
            assertEquals(op, MaterialOp.fromWireName(op.wireName()));
        }
        assertNull(MaterialOp.fromOrdinal(99));
        assertNull(MaterialOp.fromWireName("nope"));
    }
}
