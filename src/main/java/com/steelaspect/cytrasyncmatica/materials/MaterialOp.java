package com.steelaspect.cytrasyncmatica.materials;

/** An edit of one item's gathered count. Wire and bridge use the lower-case name. */
public enum MaterialOp {
    /** gathered += amount (may be negative; clamped to [0, required]) */
    ADD,
    /** gathered = amount */
    SET,
    /** gathered = required */
    DONE,
    /** gathered = 0; needs the reset permission */
    RESET;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static MaterialOp fromWireName(final String s) {
        if (s == null) {
            return null;
        }
        for (final MaterialOp op : values()) {
            if (op.wireName().equals(s.toLowerCase(java.util.Locale.ROOT))) {
                return op;
            }
        }
        return null;
    }

    public static MaterialOp fromOrdinal(final int ordinal) {
        return ordinal < 0 || ordinal >= values().length ? null : values()[ordinal];
    }
}
