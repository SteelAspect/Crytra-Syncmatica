package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MaterialGroupsTest {
    @TempDir
    Path tempDir;

    @Test
    void builtInMappingCoversCommonBlocks() {
        assertEquals(MaterialGroups.STONE, MaterialGroups.builtIn("minecraft:stone"));
        assertEquals(MaterialGroups.STONE, MaterialGroups.builtIn("minecraft:stone_bricks"));
        assertEquals(MaterialGroups.STONE, MaterialGroups.builtIn("minecraft:deepslate_tiles"));
        assertEquals(MaterialGroups.WOOD, MaterialGroups.builtIn("minecraft:oak_planks"));
        assertEquals(MaterialGroups.WOOD, MaterialGroups.builtIn("minecraft:spruce_stairs"));
        assertEquals(MaterialGroups.WOOD, MaterialGroups.builtIn("minecraft:oak_door"));
        assertEquals(MaterialGroups.GLASS, MaterialGroups.builtIn("minecraft:white_stained_glass_pane"));
        assertEquals(MaterialGroups.REDSTONE, MaterialGroups.builtIn("minecraft:sticky_piston"));
        assertEquals(MaterialGroups.REDSTONE, MaterialGroups.builtIn("minecraft:redstone_torch"));
        assertEquals(MaterialGroups.LIGHTING, MaterialGroups.builtIn("minecraft:redstone_lamp"));
        assertEquals(MaterialGroups.LIGHTING, MaterialGroups.builtIn("minecraft:sea_lantern"));
        assertEquals(MaterialGroups.METAL, MaterialGroups.builtIn("minecraft:iron_block"));
        assertEquals(MaterialGroups.METAL, MaterialGroups.builtIn("minecraft:iron_door"));
        assertEquals(MaterialGroups.STONE, MaterialGroups.builtIn("minecraft:sandstone"));
        assertEquals(MaterialGroups.STONE, MaterialGroups.builtIn("minecraft:bedrock"));
        assertEquals(MaterialGroups.WOOD, MaterialGroups.builtIn("minecraft:crimson_planks"));
        assertEquals(MaterialGroups.DECORATION, MaterialGroups.builtIn("minecraft:red_bed"));
        assertEquals(MaterialGroups.NETHER_END, MaterialGroups.builtIn("minecraft:polished_blackstone_bricks"));
        assertEquals(MaterialGroups.NETHER_END, MaterialGroups.builtIn("minecraft:obsidian"));
        assertEquals(MaterialGroups.TERRAIN, MaterialGroups.builtIn("minecraft:dirt"));
        assertEquals(MaterialGroups.DECORATION, MaterialGroups.builtIn("minecraft:red_wool"));
        assertEquals(MaterialGroups.LIQUIDS, MaterialGroups.builtIn("minecraft:water_bucket"));
        assertEquals(MaterialGroups.OTHER, MaterialGroups.builtIn("minecraft:enchanting_table"));
        assertEquals(MaterialGroups.OTHER, MaterialGroups.builtIn(null));
    }

    @Test
    void overridesWinAndAreCleaned() {
        final Map<String, String> overrides = Map.of("minecraft:stone", "  Walls  ", "minecraft:glass", "");
        assertEquals("Walls", MaterialGroups.groupOf("minecraft:stone", overrides));
        assertEquals(MaterialGroups.GLASS, MaterialGroups.groupOf("minecraft:glass", overrides), "blank override is ignored");
        assertEquals(MaterialGroups.OTHER, MaterialGroups.clean("   "));
        assertEquals(MaterialGroups.MAX_GROUP_NAME, MaterialGroups.clean("x".repeat(100)).length());
    }

    @Test
    void overridesFileIsCreatedWhenMissingAndReadWhenPresent() throws Exception {
        final Path file = tempDir.resolve("groups.json");
        assertTrue(MaterialGroups.loadOverrides(file).isEmpty());
        MaterialGroups.ensureTemplate(file);
        assertTrue(Files.isRegularFile(file), "template written");
        assertTrue(Files.readString(file).contains("\"overrides\""));

        Files.writeString(file, "{\"overrides\": {\"minecraft:stone\": \"Walls\", \"oak_planks\": \"Roof\", \"bad\": 3}}");
        final Map<String, String> o = MaterialGroups.loadOverrides(file);
        assertEquals("Walls", o.get("minecraft:stone"));
        assertEquals("Roof", o.get("minecraft:oak_planks"), "namespace defaults to minecraft");
        assertEquals("3", o.get("minecraft:bad"));

        Files.writeString(file, "not json");
        assertTrue(MaterialGroups.loadOverrides(file).isEmpty(), "broken file yields no overrides");
    }

    @Test
    void listGroupTotalsAndCompletion() {
        final MaterialList list = new MaterialList();
        list.put(new MaterialEntry("minecraft:stone", 10));
        list.put(new MaterialEntry("minecraft:stone_slab", 4));
        list.put(new MaterialEntry("minecraft:oak_planks", 6));
        MaterialGroups.assign(list, Map.of());
        assertEquals(List.of(MaterialGroups.STONE, MaterialGroups.WOOD),
                list.groups().stream().map(MaterialList.GroupTotals::name).toList());
        final MaterialList.GroupTotals stone = list.group("stone");
        assertEquals(2, stone.items());
        assertEquals(14, stone.required());
        assertFalse(stone.complete());
        assertFalse(list.isGroupComplete("Stone"));
        assertFalse(list.isGroupComplete("Nope"), "a group with no items is not complete");

        list.get("minecraft:oak_planks").setGathered(6, null, "", 1L);
        assertTrue(list.isGroupComplete("Wood"));
        assertEquals(100.0, list.group("Wood").percent());
        assertEquals(1, list.groups().stream().filter(MaterialList.GroupTotals::complete).count());

        // the group survives a re-extraction and a JSON round trip
        list.applyRequirements(Map.of("minecraft:oak_planks", 9));
        assertEquals(MaterialGroups.WOOD, list.get("minecraft:oak_planks").getGroup());
        final MaterialList back = MaterialList.fromJson(list.toJson());
        assertEquals(MaterialGroups.WOOD, back.get("minecraft:oak_planks").getGroup());
    }
}
