package com.steelaspect.cytrasyncmatica.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

final class ProjectTest {
    @Test
    void membersAreOrderedUniqueAndCapped() {
        final Project p = new Project(UUID.randomUUID(), "  Iron   farms ", "Alex", 7L);
        assertEquals("Iron farms", p.getName());
        assertTrue(p.addMember("a"));
        assertTrue(p.addMember("b"));
        assertFalse(p.addMember("a"));
        assertFalse(p.addMember(""));
        assertEquals(List.of("a", "b"), p.getMembers());
        assertTrue(p.removeMember("a"));
        assertFalse(p.removeMember("a"));
        for (int i = 0; i < Project.MAX_MEMBERS + 5; i++) {
            p.addMember("m" + i);
        }
        assertEquals(Project.MAX_MEMBERS, p.getMembers().size());
        assertEquals(Project.MAX_NAME_LENGTH, Project.cleanName("x".repeat(200)).length());
    }

    @Test
    void jsonAndWireRoundTrip() {
        final Project p = new Project(UUID.randomUUID(), "Base", "Alex", 7L);
        p.addMember(UUID.randomUUID().toString());
        p.addMember("local-farm");
        final Project fromJson = Project.fromJson(p.toJson());
        assertEquals(p.getId(), fromJson.getId());
        assertEquals(p.getMembers(), fromJson.getMembers());
        assertEquals("Alex", fromJson.getCreatedBy());
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        p.write(buf);
        final Project fromWire = Project.read(buf);
        assertEquals(p.getName(), fromWire.getName());
        assertEquals(p.getMembers(), fromWire.getMembers());
        assertEquals(7L, fromWire.getCreatedAt());
        assertEquals(0, buf.readableBytes());
        assertNull(Project.fromJson(null));
    }
}
