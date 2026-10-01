package com.steelaspect.cytrasyncmatica.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LinkCodesTest {
    @Test
    void codesAreSingleUseAndOnePerPlayer() {
        final LinkCodes codes = new LinkCodes();
        final UUID steve = UUID.randomUUID();
        final String first = codes.issue(steve, "Steve");
        final String second = codes.issue(steve, "Steve");
        assertNotEquals(first, second);
        assertEquals(1, codes.size(), "re-issuing replaces the old code");
        assertNull(codes.claim(first));
        final LinkCodes.Claim claim = codes.claim(second.toLowerCase());
        assertNotNull(claim);
        assertEquals(steve, claim.uuid());
        assertEquals("Steve", claim.name());
        assertNull(codes.claim(second), "a code can be claimed once");
        assertNull(codes.claim(null));
        assertNull(codes.claim("ZZZZZZ"));
    }
}
