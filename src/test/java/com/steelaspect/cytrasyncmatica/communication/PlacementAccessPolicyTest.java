package com.steelaspect.cytrasyncmatica.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlacementAccessPolicyTest {
    @Test
    void exposesDocumentedPermissionNodesAndOperatorFallback() {
        assertEquals("cytra-syncmatica.share", PlacementAccessPolicy.SHARE_PERMISSION);
        assertEquals("cytra-syncmatica.build.claim", PlacementAccessPolicy.BUILD_CLAIM_PERMISSION);
        assertEquals("cytra-syncmatica.manage", PlacementAccessPolicy.MANAGE_PERMISSION);
        assertEquals(2, PlacementAccessPolicy.MANAGE_PERMISSION_LEVEL);
    }

    @Test
    void ownerCanManagePlacement() {
        final UUID owner = UUID.randomUUID();

        assertTrue(PlacementAccessPolicy.canManage(owner, owner, false));
    }

    @Test
    void elevatedUserCanManageAnotherPlayersPlacement() {
        assertTrue(PlacementAccessPolicy.canManage(UUID.randomUUID(), UUID.randomUUID(), true));
    }

    @Test
    void unrelatedPlayerCannotManagePlacement() {
        assertFalse(PlacementAccessPolicy.canManage(UUID.randomUUID(), UUID.randomUUID(), false));
        assertFalse(PlacementAccessPolicy.canManage(null, UUID.randomUUID(), false));
    }

}
