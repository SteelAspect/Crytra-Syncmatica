package com.steelaspect.cytrasyncmatica.communication;

import java.util.UUID;

public final class PlacementAccessPolicy {
    public static final String SHARE_PERMISSION = "cytra-syncmatica.share";
    /**
     * Signing up to build part of the schematic is a separate job from gathering
     * materials, so it has its own node.
     */
    public static final String BUILD_CLAIM_PERMISSION = "cytra-syncmatica.build.claim";
    public static final String MANAGE_PERMISSION = "cytra-syncmatica.manage";
    public static final int MANAGE_PERMISSION_LEVEL = 2;
    /**
     * Gate for privileged commands. The packet layer cannot see Brigadier's
     * {@code requires} predicates, so it re-checks these constants instead of
     * duplicating the literals.
     */
    public static final String COMMAND_PERMISSION = "cytra-syncmatica.command";
    public static final int COMMAND_PERMISSION_LEVEL = 2;

    private PlacementAccessPolicy() {
    }

    public static boolean canManage(final UUID playerId, final UUID ownerId, final boolean elevated) {
        return elevated || (playerId != null && playerId.equals(ownerId));
    }
}
