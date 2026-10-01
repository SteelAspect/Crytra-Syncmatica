package com.steelaspect.cytrasyncmatica.materials;

/** Renders item counts as shulker boxes + stacks + items, the way builders talk. */
public final class StackFormat {
    public static final int SLOTS_PER_SHULKER = 27;

    private StackFormat() {
    }

    /** e.g. {@code 2 SB + 3 st + 5} for 2 shulkers, 3 stacks and 5 items; {@code 0} for nothing. */
    public static String format(final long count, final int maxStackSize) {
        if (count <= 0) {
            return "0";
        }
        final int stack = Math.max(1, maxStackSize);
        final long perShulker = (long) stack * SLOTS_PER_SHULKER;
        final long shulkers = count / perShulker;
        final long afterShulkers = count % perShulker;
        final long stacks = stack <= 1 ? 0 : afterShulkers / stack;
        final long items = stack <= 1 ? afterShulkers : afterShulkers % stack;
        final StringBuilder sb = new StringBuilder();
        if (shulkers > 0) {
            sb.append(shulkers).append(" SB");
        }
        if (stacks > 0) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(stacks).append(" st");
        }
        if (items > 0 || sb.length() == 0) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(items);
        }
        return sb.toString();
    }

    /** Whole shulker boxes needed to hold {@code count} items (rounded up). */
    public static long shulkersNeeded(final long count, final int maxStackSize) {
        if (count <= 0) {
            return 0;
        }
        final long perShulker = (long) Math.max(1, maxStackSize) * SLOTS_PER_SHULKER;
        return (count + perShulker - 1) / perShulker;
    }
}
