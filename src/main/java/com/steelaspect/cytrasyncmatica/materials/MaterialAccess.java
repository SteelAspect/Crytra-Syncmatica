package com.steelaspect.cytrasyncmatica.materials;

/** Permission nodes for material tracking. Bot edits are checked with the acting player's UUID. */
public final class MaterialAccess {
    /** Change gathered counts (add/set/done). Fallback: allowed for everyone. */
    public static final String EDIT_PERMISSION = "cytra-syncmatica.materials.edit";
    public static final boolean EDIT_FALLBACK = true;
    /** Reset a count to zero. Fallback: permission level 2. */
    public static final String RESET_PERMISSION = "cytra-syncmatica.materials.reset";
    public static final int RESET_PERMISSION_LEVEL = 2;

    private MaterialAccess() {
    }

    public static String requiredNode(final MaterialOp op) {
        return op == MaterialOp.RESET ? RESET_PERMISSION : EDIT_PERMISSION;
    }
}
