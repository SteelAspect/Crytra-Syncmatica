package com.steelaspect.cytrasyncmatica.materials;

import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;

/**
 * Server-side hooks for everything that wants to know about material changes
 * (the Discord bridge, future integrations). Called on the server thread.
 */
public interface MaterialEventListener {
    /** A list was (re)built from the schematic. */
    default void onListCreated(ServerPlacement placement, MaterialList list) {
    }

    /** gathered changed from {@code oldGathered} to {@code entry.getGathered()}. */
    default void onItemChanged(ServerPlacement placement, MaterialEntry entry, int oldGathered, PlayerIdentifier editor, MaterialOp op) {
    }

    default void onItemCompleted(ServerPlacement placement, MaterialEntry entry, PlayerIdentifier editor) {
    }

    default void onSchematicCompleted(ServerPlacement placement, MaterialList list, PlayerIdentifier editor) {
    }
}
