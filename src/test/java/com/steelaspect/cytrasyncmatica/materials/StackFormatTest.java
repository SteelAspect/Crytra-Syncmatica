package com.steelaspect.cytrasyncmatica.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StackFormatTest {
    @Test
    void formatsShulkersStacksAndItems() {
        assertEquals("0", StackFormat.format(0, 64));
        assertEquals("5", StackFormat.format(5, 64));
        assertEquals("1 st", StackFormat.format(64, 64));
        assertEquals("1 st + 1", StackFormat.format(65, 64));
        assertEquals("1 SB", StackFormat.format(1728, 64));
        assertEquals("2 SB + 3 st + 5", StackFormat.format(2 * 1728 + 3 * 64 + 5, 64));
        assertEquals("1 SB + 1", StackFormat.format(16 * 27 + 1, 16));
        assertEquals("3", StackFormat.format(3, 1));
        assertEquals("1 SB + 2", StackFormat.format(29, 1));
    }

    @Test
    void shulkersNeededRoundsUp() {
        assertEquals(0, StackFormat.shulkersNeeded(0, 64));
        assertEquals(1, StackFormat.shulkersNeeded(1, 64));
        assertEquals(1, StackFormat.shulkersNeeded(1728, 64));
        assertEquals(2, StackFormat.shulkersNeeded(1729, 64));
    }
}
