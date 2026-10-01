package com.steelaspect.cytrasyncmatica.client.materials;

/** Where the material counts live for the world the client is in right now. */
public enum MaterialMode {
    /** The server runs Cytra-Syncmatica: it owns the counts, every player sees the same list. */
    CONNECTED("cytra-syncmatica.gui.label.mode.connected"),
    /** Vanilla or other server, or a server without the mod: local tracking only, no network use. */
    CLIENT_ONLY("cytra-syncmatica.gui.label.mode.client_only"),
    /** Singleplayer: local tracking only. */
    SINGLEPLAYER("cytra-syncmatica.gui.label.mode.singleplayer");

    private final String labelKey;

    MaterialMode(final String labelKey) {
        this.labelKey = labelKey;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public boolean isLocal() {
        return this != CONNECTED;
    }
}
