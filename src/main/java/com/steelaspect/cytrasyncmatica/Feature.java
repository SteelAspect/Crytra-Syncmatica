package com.steelaspect.cytrasyncmatica;

public enum Feature {
    CORE,

    FEATURE,
    MODIFY,
    MESSAGE,
    QUOTA,
    DEBUG,
    CORE_EX,
    TIMESTAMPS,
    VERSION,
    DISPLAY_NAME,
    PLACEMENT_RENAME,
    LIMIT_REPORT,
    BUILD_MANAGEMENT,
    /** Cytra-Syncmatica material tracking (shared gathered counts). */
    MATERIAL_TRACKING;

    public static Feature fromString(final String s) {
        for (final Feature f : Feature.values()) {
            if (f.toString().equals(s)) {
                return f;
            }
        }
        return null;
    }
}
