package com.steelaspect.cytrasyncmatica.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.steelaspect.cytrasyncmatica.communication.exchange.AbstractExchange;
import com.steelaspect.cytrasyncmatica.communication.exchange.Exchange;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

final class ActiveExchangeLimitTest {
    @Test
    void rejectsExchangeBeyondPerConnectionLimit() {
        final StubManager manager = new StubManager();
        final ExchangeTarget target = new ExchangeTarget("player") {
            @Override
            protected void transmit(final com.steelaspect.cytrasyncmatica.communication.SyncmaticaPayload payload) {
            }
        };
        for (int i = 0; i < ProtocolLimits.MAX_ACTIVE_EXCHANGES; i++) {
            manager.start(new StubExchange(target));
        }
        final StubExchange rejected = new StubExchange(target);

        manager.start(rejected);

        assertEquals(ProtocolLimits.MAX_ACTIVE_EXCHANGES, target.getExchanges().size());
        assertTrue(rejected.isFinished());
        assertFalse(rejected.isSuccessful());
    }

    private static final class StubManager extends CommunicationManager {
        private void start(final Exchange exchange) {
            startExchangeUnchecked(exchange);
        }

        @Override
        protected void handle(final ExchangeTarget source, final Identifier id, final PacketByteBuf packetBuf) {
        }

        @Override
        protected void handleExchange(final Exchange exchange) {
        }
    }

    private static final class StubExchange extends AbstractExchange {
        private StubExchange(final ExchangeTarget partner) {
            super(partner, null);
        }

        @Override
        public boolean checkPacket(final Identifier id, final PacketByteBuf packetBuf) {
            return false;
        }

        @Override
        public void handle(final Identifier id, final PacketByteBuf packetBuf) {
        }

        @Override
        public void init() {
        }
    }
}
