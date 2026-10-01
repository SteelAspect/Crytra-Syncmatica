package com.steelaspect.cytrasyncmatica.client.materials;

import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.collection.DefaultedList;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts the player's items by item id: inventory (including shulker box
 * contents) and, optionally, the container that is open right now. Display
 * only; it never changes a gathered count by itself.
 */
public final class InventoryCounter {
    private InventoryCounter() {
    }

    public static Map<String, Integer> count(final boolean includeOpenContainer) {
        final Map<String, Integer> out = new HashMap<>();
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientPlayerEntity player = client.player;
        if (player == null) {
            return out;
        }
        final Inventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            add(out, inv.getStack(i));
        }
        if (includeOpenContainer && player.currentScreenHandler != null) {
            final ScreenHandler handler = player.currentScreenHandler;
            for (final Slot slot : handler.slots) {
                if (slot.inventory != inv) {
                    add(out, slot.getStack());
                }
            }
        }
        return out;
    }

    private static void add(final Map<String, Integer> out, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (InventoryUtils.shulkerBoxHasItems(stack)) {
            final DefaultedList<ItemStack> inside = InventoryUtils.getStoredItems(stack);
            for (final ItemStack s : inside) {
                if (s != null && !s.isEmpty()) {
                    merge(out, s);
                }
            }
        }
        merge(out, stack);
    }

    private static void merge(final Map<String, Integer> out, final ItemStack stack) {
        final String id = Registries.ITEM.getId(stack.getItem()).toString();
        out.merge(id, stack.getCount(), Integer::sum);
    }
}
