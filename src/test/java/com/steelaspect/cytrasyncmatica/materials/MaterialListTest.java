package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MaterialListTest {
    @Test
    void gatheredIsClampedAndEditorRecorded() {
        final MaterialEntry e = new MaterialEntry("minecraft:stone", 10);
        final UUID who = UUID.randomUUID();
        e.setGathered(25, who, "Steve", 123L);
        assertEquals(10, e.getGathered());
        assertEquals(0, e.getRemaining());
        assertTrue(e.isComplete());
        assertEquals(who, e.getEditorUuid());
        assertEquals("Steve", e.getEditorName());
        assertEquals(123L, e.getEditedAt());
        e.setGathered(-5, null, null, 1L);
        assertEquals(0, e.getGathered());
        assertEquals("", e.getEditorName());
    }

    @Test
    void applyingNewRequirementsKeepsCountsOfItemsStillNeeded() {
        final MaterialList list = new MaterialList();
        final Map<String, Integer> first = new LinkedHashMap<>();
        first.put("minecraft:stone", 64);
        first.put("minecraft:dirt", 10);
        list.applyRequirements(first);
        list.get("minecraft:stone").setGathered(30, UUID.randomUUID(), "Alex", 5L);

        final Map<String, Integer> second = new LinkedHashMap<>();
        second.put("minecraft:stone", 40);
        second.put("minecraft:glass", 3);
        list.applyRequirements(second);

        assertEquals(30, list.get("minecraft:stone").getGathered());
        assertEquals("Alex", list.get("minecraft:stone").getEditorName());
        assertNull(list.get("minecraft:dirt"));
        assertEquals(0, list.get("minecraft:glass").getGathered());
        assertEquals(43, list.totalRequired());
        assertEquals(30, list.totalGathered());
        assertEquals(13, list.totalRemaining());
        assertFalse(list.isComplete());
    }

    @Test
    void percentCompleteAndJsonRoundTrip() {
        final MaterialList list = new MaterialList();
        list.put(new MaterialEntry("minecraft:stone", 100));
        list.get("minecraft:stone").setGathered(25, UUID.randomUUID(), "Bob", 9L);
        assertEquals(25.0, list.percentComplete(), 0.001);

        final MaterialList copy = MaterialList.fromJson(list.toJson());
        assertEquals(1, copy.size());
        assertEquals(25, copy.get("minecraft:stone").getGathered());
        assertEquals("Bob", copy.get("minecraft:stone").getEditorName());
        assertEquals(9L, copy.get("minecraft:stone").getEditedAt());
        assertEquals(list.get("minecraft:stone").getEditorUuid(), copy.get("minecraft:stone").getEditorUuid());
        assertEquals(100.0, new MaterialList().percentComplete(), 0.001);
    }
}
