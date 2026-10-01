package com.steelaspect.cytrasyncmatica.bridge;

/** Static hand-off between the Cytra Link entrypoint (set early, at mod init) and the server context (created later). */
public final class BridgeSinkRegistry {
    private static volatile BridgeSink sink;

    private BridgeSinkRegistry() {
    }

    public static void register(final BridgeSink s) {
        sink = s;
    }

    /** null when Cytra Link is not installed. */
    public static BridgeSink get() {
        return sink;
    }
}
