package com.steelaspect.cytrasyncmatica.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.FileStorage;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.SyncmaticManager;
import com.steelaspect.cytrasyncmatica.communication.CommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.exchange.Exchange;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialEventListener;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.util.SyncmaticaUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MaterialTrackingServiceTest {
    @TempDir
    Path tempDir;

    /** Registries are not available in unit tests; blocks map to the item of the same name. */
    private static final MaterialListExtractor.BlockItemResolver RESOLVER =
            block -> block.endsWith(":air") ? null : block;

    /** 4x3x2 region: 20 stone, 2 oak doors (lower+upper halves), 1 double slab, 1 air. */
    private UUID writeLitematic(final Context context) throws Exception {
        final NbtCompound region = new NbtCompound();
        region.putIntArray("Position", new int[]{0, 0, 0});
        region.putIntArray("Size", new int[]{4, 3, 2});
        final NbtList palette = new NbtList();
        palette.add(blockState("minecraft:air", null, null));
        palette.add(blockState("minecraft:stone", null, null));
        palette.add(blockState("minecraft:oak_door", "half", "lower"));
        palette.add(blockState("minecraft:oak_door", "half", "upper"));
        palette.add(blockState("minecraft:stone_slab", "type", "double"));
        region.put("BlockStatePalette", palette);
        // 24 blocks, 3 bits each -> 72 bits -> 2 longs
        final int[] states = new int[24];
        for (int i = 0; i < 20; i++) {
            states[i] = 1;
        }
        states[20] = 2;
        states[21] = 3;
        states[22] = 4;
        states[23] = 0;
        region.putLongArray("BlockStates", pack(states, 3));
        final NbtCompound regions = new NbtCompound();
        regions.put("main", region);
        final NbtCompound root = new NbtCompound();
        root.put("Regions", regions);
        final File staging = new File(context.getLitematicFolder(), "staging.litematic");
        try (OutputStream output = new FileOutputStream(staging)) {
            NbtIo.writeCompressed(root, output);
        }
        final UUID hash;
        try (InputStream input = new FileInputStream(staging)) {
            hash = SyncmaticaUtil.createChecksum(input);
        }
        assertTrue(staging.renameTo(new File(context.getLitematicFolder(), hash + ".litematic")));
        return hash;
    }

    private static NbtCompound blockState(final String name, final String prop, final String value) {
        final NbtCompound c = new NbtCompound();
        c.putString("Name", name);
        if (prop != null) {
            final NbtCompound props = new NbtCompound();
            props.putString(prop, value);
            c.put("Properties", props);
        }
        return c;
    }

    /** Litematica's packing: bits run across long boundaries. */
    private static long[] pack(final int[] values, final int bits) {
        final long[] out = new long[(values.length * bits + 63) / 64];
        for (int i = 0; i < values.length; i++) {
            final long bitIndex = (long) i * bits;
            final int longIndex = (int) (bitIndex >> 6);
            final int offset = (int) (bitIndex & 63);
            out[longIndex] |= ((long) values[i]) << offset;
            if (offset + bits > 64) {
                out[longIndex + 1] |= ((long) values[i]) >>> (64 - offset);
            }
        }
        return out;
    }

    @Test
    void extractorCountsItemsLikeLitematica() throws Exception {
        final Context context = newServerContext();
        try {
            final UUID hash = writeLitematic(context);
            final MaterialListExtractor.Result result = MaterialListExtractor.extract(
                    new File(context.getLitematicFolder(), hash + ".litematic"), 1_000_000, 64L << 20, RESOLVER);
            assertTrue(result.ok(), result.error);
            assertEquals(20, result.requirements.get("minecraft:stone"));
            assertEquals(1, result.requirements.get("minecraft:oak_door"));
            assertEquals(2, result.requirements.get("minecraft:stone_slab"));
            assertEquals(3, result.requirements.size());
        } finally {
            context.shutdown();
        }
    }

    @Test
    void attachBuildsTheListEditsAreSharedPersistedAndReported() throws Exception {
        final Context context = newServerContext();
        try {
            context.startup();
            final MaterialTrackingService materials = context.getMaterialTracking();
            materials.setResolver(RESOLVER);
            final List<String> events = new ArrayList<>();
            materials.addListener(new MaterialEventListener() {
                @Override
                public void onListCreated(final ServerPlacement p, final MaterialList l) {
                    events.add("created");
                }

                @Override
                public void onItemChanged(final ServerPlacement p, final MaterialEntry e, final int old, final PlayerIdentifier who, final MaterialOp op) {
                    events.add("changed:" + e.getItemId() + ":" + old + "->" + e.getGathered());
                }

                @Override
                public void onItemCompleted(final ServerPlacement p, final MaterialEntry e, final PlayerIdentifier who) {
                    events.add("completed:" + e.getItemId());
                }

                @Override
                public void onGroupCompleted(final ServerPlacement p, final MaterialList.GroupTotals g, final PlayerIdentifier who) {
                    events.add("group_complete:" + g.name());
                }

                @Override
                public void onSchematicCompleted(final ServerPlacement p, final MaterialList l, final PlayerIdentifier who) {
                    events.add("schematic_complete");
                }
            });
            Files.createDirectories(materials.groupsFile().getParent());
            Files.writeString(materials.groupsFile(), "{\"overrides\": {\"minecraft:stone_slab\": \"Floor\"}}");
            final UUID hash = writeLitematic(context);
            final ServerPlacement placement = new ServerPlacement(UUID.randomUUID(), "farm", hash, PlayerIdentifier.MISSING_PLAYER);
            placement.move("minecraft:overworld", BlockPos.ORIGIN, BlockRotation.NONE, BlockMirror.NONE);
            context.getSyncmaticManager().addPlacement(placement);

            final long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && (materials.getList(placement) == null || materials.getList(placement).isEmpty())) {
                Thread.sleep(10L);
            }
            final MaterialList list = materials.getList(placement);
            assertNotNull(list);
            assertEquals(20, list.get("minecraft:stone").getRequired());
            assertTrue(events.contains("created"));
            assertEquals("Stone", list.get("minecraft:stone").getGroup());
            assertEquals("Wood", list.get("minecraft:oak_door").getGroup());
            assertEquals("Floor", list.get("minecraft:stone_slab").getGroup(), "groups.json override applied");

            final PlayerIdentifier alex = context.getPlayerIdentifierProvider().createOrGet(UUID.randomUUID(), "Alex");
            assertEquals(MaterialTrackingService.Outcome.OK, materials.apply(placement, "minecraft:stone", MaterialOp.ADD, 16, alex));
            assertEquals(16, list.get("minecraft:stone").getGathered());
            assertEquals("Alex", list.get("minecraft:stone").getEditorName());
            assertEquals(MaterialTrackingService.Outcome.NO_CHANGE, materials.apply(placement, "minecraft:stone", MaterialOp.ADD, 0, alex));
            assertEquals(MaterialTrackingService.Outcome.UNKNOWN_ITEM, materials.apply(placement, "minecraft:bedrock", MaterialOp.ADD, 1, alex));
            assertEquals(MaterialTrackingService.Outcome.OK, materials.apply(placement, "minecraft:stone", MaterialOp.DONE, 0, alex));
            assertTrue(events.contains("completed:minecraft:stone"));
            assertTrue(events.contains("group_complete:Stone"), events.toString());
            materials.apply(placement, "minecraft:oak_door", MaterialOp.SET, 1, alex);
            assertTrue(events.contains("group_complete:Wood"), events.toString());
            assertFalse(events.contains("schematic_complete"), events.toString());
            materials.apply(placement, "minecraft:stone_slab", MaterialOp.SET, 2, alex);
            assertTrue(events.contains("group_complete:Floor"), events.toString());
            assertTrue(events.contains("schematic_complete"), events.toString());
            assertEquals(MaterialTrackingService.Outcome.OK, materials.apply(placement, "minecraft:stone", MaterialOp.RESET, 0, alex));
            assertEquals(0, list.get("minecraft:stone").getGathered());

            // export
            final Path exports = tempDir.resolve("exports");
            final List<Path> files = materials.export(placement, exports).get();
            assertEquals(2, files.size());
            final String csv = Files.readString(files.get(0));
            assertTrue(csv.startsWith("item,group,required,gathered,remaining,last_edited_by,last_edited_at"), csv);
            assertTrue(csv.contains("minecraft:stone,Stone,20,0,20,Alex,"), csv);
            final String txt = Files.readString(files.get(1));
            assertTrue(txt.contains("Materials for farm"), txt);
            assertTrue(txt.contains("minecraft:stone"), txt);

            // persistence: a fresh service for the same world folder reads the stored counts back
            materials.apply(placement, "minecraft:stone", MaterialOp.SET, 7, alex);
            Thread.sleep(200L);
            final Path stored = tempDir.resolve("cytra-syncmatica").resolve("materials").resolve(placement.getId() + ".json");
            final long d2 = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < d2 && !Files.isRegularFile(stored)) {
                Thread.sleep(10L);
            }
            assertTrue(Files.isRegularFile(stored), "material file persisted");
            assertTrue(Files.readString(stored).contains("\"gathered\": 7"), Files.readString(stored));
        } finally {
            context.shutdown();
        }
    }

    private Context newServerContext() {
        return new Context(
                new FileStorage(),
                new StubCommunicationManager(),
                new SyncmaticManager(),
                true,
                tempDir.resolve("litematics").toFile(),
                true,
                tempDir.toFile()
        );
    }

    private static final class StubCommunicationManager extends CommunicationManager {
        @Override
        protected void handle(final ExchangeTarget source, final Identifier id, final PacketByteBuf packetBuf) {
        }

        @Override
        protected void handleExchange(final Exchange exchange) {
        }
    }
}
