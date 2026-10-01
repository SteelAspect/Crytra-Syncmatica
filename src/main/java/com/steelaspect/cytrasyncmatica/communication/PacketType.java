package com.steelaspect.cytrasyncmatica.communication;

import net.minecraft.util.Identifier;

public enum PacketType {

    REGISTER_METADATA("register_metadata"),

    CANCEL_SHARE("cancel_share"),

    REQUEST_LITEMATIC("request_download"),

    SEND_LITEMATIC("send_litematic"),

    RECEIVED_LITEMATIC("received_litematic"),

    FINISHED_LITEMATIC("finished_litematic"),

    CANCEL_LITEMATIC("cancel_litematic"),

    REMOVE_SYNCMATIC("remove_syncmatic"),

    REGISTER_VERSION("register_version"),

    // Reforged-only capability announce from client to server
    REVOLUTION("revolution"),

    CONFIRM_USER("confirm_user"),

    FEATURE_REQUEST("feature_request"),

    FEATURE("feature"),

    MODIFY("modify"),

    MODIFY_REQUEST("modify_request"),

    MODIFY_REQUEST_DENY("modify_request_deny"),
    MODIFY_REQUEST_ACCEPT("modify_request_accept"),

    MODIFY_FINISH("modify_finish"),

    MESSAGE("mesage"),

    BUILD_REGION_CLAIM("build_region_claim"),

    // Cytra-Syncmatica material tracking
    /** S2C: the full list of one placement (answer to a request, or after a rebuild). */
    MATERIAL_LIST("material_list"),
    /** S2C: one entry changed. */
    MATERIAL_UPDATE("material_update"),
    /** C2S: please send me the list of this placement. */
    MATERIAL_REQUEST("material_request"),
    /** C2S: change one entry (add/set/done/reset). */
    MATERIAL_EDIT("material_edit");

    /** Our own channel namespace. Deliberately not the cytra-syncmatica one: the wire format differs. */
    public static final String NAMESPACE = "cytra-syncmatica";

    private final String path;

    PacketType(final String path) {
        this.path = path;
    }

    public Identifier toIdentifier(final ProtocolFlavor flavor) {
        return Identifier.of(NAMESPACE, path);
    }

    public Identifier toIdentifier() {
        return toIdentifier(ProtocolFlavor.NEW);
    }

    public static boolean containsIdentifier(final Identifier id) {
        return fromIdentifier(id) != null;
    }

    public static PacketType fromIdentifier(final Identifier id) {
        final String namespace = id.getNamespace();
        if (!NAMESPACE.equals(namespace)) {
            return null;
        }
        final String path = id.getPath();
        for (final PacketType type : PacketType.values()) {
            if (type.path.equals(path)) {
                return type;
            }
        }
        return null;
    }
}
