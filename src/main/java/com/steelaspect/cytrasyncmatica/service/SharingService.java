package com.steelaspect.cytrasyncmatica.service;

import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;

/**
 * Server-side limits for schematic sharing. Upstream kept the transfer limit in
 * its material service; it lives here so sharing, downloads and build management
 * share one value without any material-tracking dependency.
 */
public class SharingService extends AbstractService {
    public static final int MAX_SCHEMATIC_MEGABYTES_DEFAULT = 64;
    public static final int MIN_SCHEMATIC_MEGABYTES = 1;
    public static final int MAX_SCHEMATIC_MEGABYTES_LIMIT = 64;

    public static final boolean HIDE_COORDINATES_WITHOUT_PERMISSION_DEFAULT = false;
    /** {@code /cytra-syncmatica where}; fallback: allowed for everyone. */
    public static final String WHERE_PERMISSION = "cytra-syncmatica.where";

    private int maxSchematicMegabytes = MAX_SCHEMATIC_MEGABYTES_DEFAULT;
    private boolean hideCoordinatesWithoutPermission = HIDE_COORDINATES_WITHOUT_PERMISSION_DEFAULT;

    /** When on, players lacking {@link #WHERE_PERMISSION} see dimension and distance only. */
    public boolean isHideCoordinatesWithoutPermission() {
        return hideCoordinatesWithoutPermission;
    }

    public long getMaxSchematicBytes() {
        return Math.min(ProtocolLimits.DEFAULT_MAX_SCHEMATIC_BYTES, maxSchematicMegabytes * 1024L * 1024L);
    }

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        final ConfigRegistry registry = new ConfigRegistry();
        registerConfigOptions(registry);
        registry.saveDefaults(getConfigKey(), configuration);
    }

    @Override
    public String getConfigKey() {
        return "sharing";
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadInteger("max_schematic_megabytes", this::setMaxSchematicMegabytes);
        configuration.loadBoolean("hide_coordinates_without_permission", v -> hideCoordinatesWithoutPermission = v);
    }

    public void registerConfigOptions(final ConfigRegistry registry) {
        registry.add(ConfigOption.integer(
                getConfigKey(), "max_schematic_megabytes", MAX_SCHEMATIC_MEGABYTES_DEFAULT,
                MIN_SCHEMATIC_MEGABYTES, MAX_SCHEMATIC_MEGABYTES_LIMIT,
                () -> maxSchematicMegabytes, this::setMaxSchematicMegabytes));
        registry.add(ConfigOption.bool(getConfigKey(), "hide_coordinates_without_permission",
                HIDE_COORDINATES_WITHOUT_PERMISSION_DEFAULT, () -> hideCoordinatesWithoutPermission,
                v -> hideCoordinatesWithoutPermission = v));
    }

    private void setMaxSchematicMegabytes(final int value) {
        maxSchematicMegabytes = Math.max(MIN_SCHEMATIC_MEGABYTES, Math.min(MAX_SCHEMATIC_MEGABYTES_LIMIT, value));
    }

    @Override
    public void startup() {
    }

    @Override
    public void shutdown() {
    }
}
