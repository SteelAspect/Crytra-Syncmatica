package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ShoppingListTest {
    private static MaterialList list() {
        final MaterialList list = new MaterialList();
        list.put(new MaterialEntry("minecraft:stone", 3000));
        list.put(new MaterialEntry("minecraft:oak_planks", 100));
        list.put(new MaterialEntry("minecraft:oak_door", 5));
        list.put(new MaterialEntry("minecraft:glass", 10));
        list.get("minecraft:glass").setGathered(10, null, "", 1L);
        list.get("minecraft:stone").setGathered(1000, null, "", 1L);
        MaterialGroups.assign(list, Map.of());
        return list;
    }

    private static int stacks(final String id) {
        return id.endsWith("door") ? 16 : 64;
    }

    @Test
    void linesSkipCompleteItemsSubtractInventoryAndGroupInOrder() {
        final List<ShoppingList.Line> lines = ShoppingList.build(list(), Map.of("minecraft:oak_planks", 40), ShoppingListTest::stacks, null);
        assertEquals(List.of("minecraft:stone", "minecraft:oak_planks", "minecraft:oak_door"),
                lines.stream().map(ShoppingList.Line::itemId).toList(), "glass is done; stone first, wood by remaining");
        assertEquals(2000, lines.get(0).remaining());
        assertEquals("1 SB + 4 st + 16", lines.get(0).text());
        assertEquals(60, lines.get(1).remaining(), "inventory subtracted");
        assertEquals("5", lines.get(2).text(), "doors stack to 16: five is less than a stack");
        assertEquals(2065, ShoppingList.totalItems(lines));
        assertEquals(4, ShoppingList.totalShulkers(lines));
    }

    @Test
    void groupFilterAndText() {
        final List<ShoppingList.Line> wood = ShoppingList.build(list(), null, ShoppingListTest::stacks, "wood");
        assertEquals(2, wood.size());
        final String text = ShoppingList.toText("farm", wood);
        assertTrue(text.startsWith("Shopping list for farm\n105 items in 2 lines, about 2 shulker boxes\n"), text);
        assertTrue(text.contains("== Wood ==\n  oak planks"), text);
        assertTrue(text.contains("(100)"), text);
        assertEquals("Shopping list for farm\nNothing left to gather.\n",
                ShoppingList.toText("farm", ShoppingList.build(list(), null, ShoppingListTest::stacks, "glass")));
        assertEquals(2, ShoppingList.toJson("farm", wood).getAsJsonArray("lines").size());
    }
}
