package com.steelaspect.cytrasyncmatica.bridge;

import com.google.gson.JsonObject;

/**
 * Where bridge events go. The only implementation talks to Cytra Link and lives
 * in {@link CytraLinkBridge}, which is loaded only when that mod is installed;
 * the common server code sees just this interface.
 */
public interface BridgeSink {
    /** Sends to every connected bot; must return at once and never block. */
    void publish(String type, JsonObject payload);

    boolean anyBotConnected();
}
