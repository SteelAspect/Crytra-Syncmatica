package com.steelaspect.cytrasyncmatica.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.FileStorage;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.SyncmaticManager;
import com.steelaspect.cytrasyncmatica.communication.CommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.ExchangeTarget;
import com.steelaspect.cytrasyncmatica.communication.exchange.Exchange;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.service.BridgeService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BridgeServiceTest {
    @TempDir
    Path tempDir;

    private static final class FakeSink implements BridgeSink {
        boolean connected;
        final List<String> types = new ArrayList<>();
        final List<JsonObject> payloads = new ArrayList<>();

        @Override
        public void publish(final String type, final JsonObject payload) {
            types.add(type);
            payloads.add(payload);
        }

        @Override
        public boolean anyBotConnected() {
            return connected;
        }
    }

    @AfterEach
    void unregister() {
        BridgeSinkRegistry.register(null);
    }

    @Test
    void eventsQueueWhileNoBotIsConnectedAndFlushWithAResync() {
        final FakeSink sink = new FakeSink();
        BridgeSinkRegistry.register(sink);
        final Context context = newServerContext();
        try {
            context.startup();
            final BridgeService bridge = context.getBridge();
            final ServerPlacement p = placement();
            bridge.onSchematicShared(p);
            bridge.onSchematicRemoved(p);
            assertEquals(2, bridge.queuedEvents());
            assertTrue(sink.types.isEmpty());

            sink.connected = true;
            bridge.onBotConnected();
            assertEquals(List.of("schematic_shared", "schematic_removed", "resync"), sink.types);
            assertEquals(2, sink.payloads.get(2).get("queued_events_sent").getAsInt());
            assertEquals(0, bridge.queuedEvents());

            bridge.onSchematicShared(p);
            assertEquals(4, sink.types.size(), "sent directly once a bot is connected");
        } finally {
            context.shutdown();
        }
    }

    @Test
    void queueDropsOldestBeyondTheLimit() {
        final FakeSink sink = new FakeSink();
        BridgeSinkRegistry.register(sink);
        final Context context = newServerContext();
        try {
            context.startup();
            final BridgeService bridge = context.getBridge();
            bridge.configure(new com.steelaspect.cytrasyncmatica.service.IServiceConfiguration() {
                @Override
                public void loadBoolean(final String key, final java.util.function.Consumer<Boolean> loader) {
                }

                @Override
                public void saveBoolean(final String key, final Boolean value) {
                }

                @Override
                public void loadInteger(final String key, final java.util.function.IntConsumer loader) {
                    if ("queue_limit".equals(key)) {
                        loader.accept(3);
                    }
                }

                @Override
                public void saveInteger(final String key, final Integer value) {
                }
            });
            for (int i = 0; i < 5; i++) {
                final JsonObject o = new JsonObject();
                o.addProperty("n", i);
                bridge.publish("test", o);
            }
            assertEquals(3, bridge.queuedEvents());
            sink.connected = true;
            bridge.onBotConnected();
            assertEquals(2, sink.payloads.get(0).get("n").getAsInt(), "the oldest two were dropped");
        } finally {
            context.shutdown();
        }
    }

    @Test
    void itemChangesAreBatchedPerSchematicAndCarryOldAndNew() {
        final FakeSink sink = new FakeSink();
        sink.connected = true;
        BridgeSinkRegistry.register(sink);
        final Context context = newServerContext();
        try {
            context.startup();
            final BridgeService bridge = context.getBridge();
            final ServerPlacement p = placement();
            final MaterialEntry stone = new MaterialEntry("minecraft:stone", 100);
            final PlayerIdentifier alex = context.getPlayerIdentifierProvider().createOrGet(UUID.randomUUID(), "Alex");
            stone.setGathered(10, alex.uuid, "Alex", 1L);
            bridge.onItemChanged(p, stone, 0, alex, MaterialOp.ADD);
            stone.setGathered(25, alex.uuid, "Alex", 2L);
            bridge.onItemChanged(p, stone, 10, alex, MaterialOp.ADD);
            bridge.tick();
            assertTrue(sink.types.isEmpty(), "still inside the batch window");

            final MaterialList list = new MaterialList();
            list.put(stone);
            bridge.onSchematicCompleted(p, list, alex); // forces a flush first
            assertEquals(List.of("item_changed", "schematic_completed"), sink.types);
            final JsonObject batch = sink.payloads.get(0);
            assertEquals("farm", batch.get("schematic").getAsString());
            assertEquals(1, batch.getAsJsonArray("changes").size(), "two edits of one item collapse into one change");
            final JsonObject change = batch.getAsJsonArray("changes").get(0).getAsJsonObject();
            assertEquals(0, change.get("old").getAsInt());
            assertEquals(25, change.get("new").getAsInt());
            assertEquals("Alex", change.getAsJsonObject("editor").get("name").getAsString());
            // this placement was never attached to the material service, so the summary says so
            assertFalse(batch.getAsJsonObject("materials").get("available").getAsBoolean());
        } finally {
            context.shutdown();
        }
    }

    private static ServerPlacement placement() {
        final ServerPlacement p = new ServerPlacement(UUID.randomUUID(), "farm", UUID.randomUUID(), PlayerIdentifier.MISSING_PLAYER);
        p.move("minecraft:overworld", new BlockPos(10, 64, -20), BlockRotation.NONE, BlockMirror.NONE);
        return p;
    }

    private Context newServerContext() {
        return new Context(new FileStorage(), new StubCommunicationManager(), new SyncmaticManager(), true,
                tempDir.resolve("litematics").toFile(), true, tempDir.toFile());
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
