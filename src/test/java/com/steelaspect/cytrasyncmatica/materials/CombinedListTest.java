package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CombinedListTest {
    private static MaterialList list(final int stone, final int stoneGathered, final int planks) {
        final MaterialList l = new MaterialList();
        l.put(new MaterialEntry("minecraft:stone", stone));
        l.get("minecraft:stone").setGathered(stoneGathered, null, "Alex", 5L);
        if (planks > 0) {
            l.put(new MaterialEntry("minecraft:oak_planks", planks));
        }
        MaterialGroups.assign(l, Map.of());
        return l;
    }

    @Test
    void combineSumsRequiredAndGatheredAndKeepsTheBreakdown() {
        final CombinedList.Combined c = CombinedList.combine(List.of(
                new CombinedList.Source("a", "Farm A", list(100, 40, 20)),
                new CombinedList.Source("b", "Farm B", list(50, 50, 0)),
                new CombinedList.Source("c", "Farm C", null)));
        assertEquals(1, c.missingLists());
        assertEquals(150, c.list().get("minecraft:stone").getRequired());
        assertEquals(90, c.list().get("minecraft:stone").getGathered());
        assertEquals(20, c.list().get("minecraft:oak_planks").getRequired());
        assertEquals("Stone", c.list().get("minecraft:stone").getGroup());
        assertEquals("Alex", c.list().get("minecraft:stone").getEditorName());
        final List<CombinedList.Part> parts = c.breakdown().get("minecraft:stone");
        assertEquals(List.of("a", "b"), parts.stream().map(CombinedList.Part::key).toList());
        assertEquals(60, parts.get(0).remaining());
        assertEquals(0, parts.get(1).remaining());
    }

    @Test
    void distributeFillsTheFirstPartWithRoomFirstAndTakesBackFromTheLast() {
        final List<CombinedList.Part> parts = List.of(
                new CombinedList.Part("a", "A", 100, 90),
                new CombinedList.Part("b", "B", 50, 10),
                new CombinedList.Part("c", "C", 30, 0));
        assertEquals(Map.of("a", 100, "b", 30), CombinedList.distribute(parts, MaterialOp.ADD, 30));
        assertEquals(Map.of("b", 0, "a", 85), CombinedList.distribute(parts, MaterialOp.ADD, -15));
        assertEquals(Map.of("a", 100, "b", 50, "c", 30), CombinedList.distribute(parts, MaterialOp.DONE, 0));
        assertEquals(Map.of("a", 0, "b", 0), CombinedList.distribute(parts, MaterialOp.RESET, 0));
        // SET 120 from 100 gathered = +20
        assertEquals(Map.of("a", 100, "b", 20), CombinedList.distribute(parts, MaterialOp.SET, 120));
        assertTrue(CombinedList.distribute(parts, MaterialOp.ADD, 0).isEmpty());
        assertTrue(CombinedList.distribute(List.of(), MaterialOp.DONE, 0).isEmpty());
    }
}
