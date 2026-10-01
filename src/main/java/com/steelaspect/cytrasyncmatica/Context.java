package com.steelaspect.cytrasyncmatica;

import com.steelaspect.cytrasyncmatica.communication.CommunicationManager;
import com.steelaspect.cytrasyncmatica.communication.FeatureSet;
import com.steelaspect.cytrasyncmatica.communication.ProtocolLimits;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifierProvider;
import com.steelaspect.cytrasyncmatica.service.*;
import com.steelaspect.cytrasyncmatica.service.IServiceConfiguration;
import com.steelaspect.cytrasyncmatica.util.VersionComparator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.server.MinecraftServer;

public class Context {

    private final IFileStorage files;
    private final CommunicationManager comMan;
    private final SyncmaticManager synMan;
    private final boolean server;
    private final boolean integratedServer;
    private final File litematicFolder;
    private final File worldFolder;
    private final QuotaService quota;
    private final DebugService debugService;
    private final PlayerIdentifierProvider playerIdentifierProvider;
    private final SharingService sharingService;
    private final BuildService buildService;
    private final MaterialTrackingService materialTracking;
    private final BridgeService bridge;
    private final ProjectService projects;
    private final PreviewService previews;
    private MinecraftServer minecraftServer;
    private ConfigRegistry configRegistry;
    private ConfigStore configStore;
    private JsonObject loadedConfiguration;
    private FeatureSet fs = null;
    private boolean isStarted = false;
    private boolean quotaStarted;
    private boolean sharingStarted;
    private boolean materialsStarted;
    private boolean bridgeStarted;
    private boolean projectsStarted;
    private boolean previewsStarted;
    private boolean buildStarted;
    private boolean debugStarted;
    private boolean managerStarted;

    public Context(
            final IFileStorage fs,
            final CommunicationManager comMan,
            final SyncmaticManager synMan,
            final File litematicFolder
    ) {
        this(fs, comMan, synMan, false, litematicFolder, false, null);
    }

    public Context(
            final IFileStorage fs,
            final CommunicationManager comMan,
            final SyncmaticManager synMan,
            final boolean isServer,
            final File litematicFolder,
            final boolean integrated,
            final File worldFolder
    ) {
        files = fs;
        fs.setContext(this);
        this.comMan = comMan;
        comMan.setContext(this);
        this.synMan = synMan;
        synMan.setContext(this);
        server = isServer;
        if (isServer) {
            quota = new QuotaService();
            quota.setContext(this);
            sharingService = new SharingService();
            sharingService.setContext(this);
            buildService = new BuildService();
            buildService.setContext(this);
            materialTracking = new MaterialTrackingService();
            materialTracking.setContext(this);
            bridge = new BridgeService();
            bridge.setContext(this);
            projects = new ProjectService();
            projects.setContext(this);
            previews = new PreviewService();
            previews.setContext(this);
        } else {
            quota = null;
            sharingService = null;
            buildService = null;
            materialTracking = null;
            bridge = null;
            projects = null;
            previews = null;
        }
        playerIdentifierProvider = new PlayerIdentifierProvider(this);
        debugService = new DebugService();
        this.litematicFolder = litematicFolder;
        if (!litematicFolder.exists() && !litematicFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create litematica folder " + litematicFolder.getAbsolutePath());
        }
        integratedServer = integrated;
        this.worldFolder = worldFolder;
        loadConfiguration();
    }

    public PlayerIdentifierProvider getPlayerIdentifierProvider() {
        return playerIdentifierProvider;
    }

    public IFileStorage getFileStorage() {
        return files;
    }

    public CommunicationManager getCommunicationManager() {
        return comMan;
    }

    public SyncmaticManager getSyncmaticManager() {
        return synMan;
    }

    public QuotaService getQuotaService() {
        return quota;
    }

    public SharingService getSharingService() {
        return sharingService;
    }

    /** Server side only; null on a client context. */
    public MaterialTrackingService getMaterialTracking() {
        return materialTracking;
    }

    /** Server side only; null on a client context. Works without Cytra Link (then only queues). */
    /** null on the client. */
    public ProjectService getProjects() {
        return projects;
    }

    /** null on the client. */
    public PreviewService getPreviews() {
        return previews;
    }

    public BridgeService getBridge() {
        return bridge;
    }

    public BuildService getBuildService() {
        return buildService;
    }

    public MinecraftServer getMinecraftServer() {
        return minecraftServer;
    }

    public void attachMinecraftServer(final MinecraftServer minecraftServer) {
        if (!server) {
            throw new IllegalStateException("A client context cannot own a Minecraft server");
        }
        if (this.minecraftServer != null && this.minecraftServer != minecraftServer) {
            throw new IllegalStateException("Minecraft server is already attached");
        }
        this.minecraftServer = minecraftServer;
    }

    public long getMaxTransferBytes() {
        return sharingService == null
                ? ProtocolLimits.DEFAULT_MAX_SCHEMATIC_BYTES
                : sharingService.getMaxSchematicBytes();
    }

    public DebugService getDebugService() {
        return debugService;
    }

    public ConfigRegistry getConfigRegistry() {
        return configRegistry;
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public JsonObject getLoadedConfiguration() {
        return loadedConfiguration == null
                ? null
                : new Gson().fromJson(loadedConfiguration.toString(), JsonObject.class);
    }

    public FeatureSet getFeatureSet() {
        if (fs == null) {
            generateFeatureSet();
        }
        return fs;
    }

    public boolean isServer() {
        return server;
    }

    public boolean isIntegratedServer() {
        return integratedServer;
    }

    public boolean isStarted() {
        return isStarted;
    }

    public File getLitematicFolder() {
        return litematicFolder;
    }

    private void generateFeatureSet() {
        final ArrayList<Feature> features = new ArrayList<>(Arrays.asList(Feature.values()));
        // Build management reads the schematic itself, so it stands or falls on
        // its own switch rather than on whether materials are tracked.
        if (isServer() && (buildService == null || !buildService.isEnabled())) {
            features.remove(Feature.BUILD_MANAGEMENT);
        }
        if (isServer() && (materialTracking == null || !materialTracking.isEnabled())) {
            features.remove(Feature.MATERIAL_TRACKING);
        }
        fs = new FeatureSet(features);
    }

    public void serverFeaturesChanged() {
        if (!isServer()) {
            return;
        }
        fs = null;
        if (isStarted && comMan instanceof ServerCommunicationManager) {
            ((ServerCommunicationManager) comMan).reloadFeatureState();
        }
    }

    public void startup() {
        if (isStarted) {
            return;
        }
        try {
            if (quota != null) {
                quota.startup();
                quotaStarted = true;
            }
            if (sharingService != null) {
                sharingService.startup();
                sharingStarted = true;
            }
            if (buildService != null) {
                buildService.startup();
                buildStarted = true;
            }
            if (materialTracking != null) {
                materialTracking.startup();
                materialsStarted = true;
            }
            if (projects != null) {
                projects.startup();
                projectsStarted = true;
            }
            if (previews != null) {
                previews.startup();
                previewsStarted = true;
            }
            if (bridge != null) {
                bridge.startup();
                bridgeStarted = true;
            }
            debugService.startup();
            debugStarted = true;
            synMan.startup();
            managerStarted = true;
            isStarted = true;
        } catch (final RuntimeException | Error failure) {
            rollbackStartup(failure);
            throw failure;
        }
    }

    public void shutdown() {
        stopStartedServices(null);
        playerIdentifierProvider.clear();
        isStarted = false;
    }

    public boolean checkPartnerVersion(final String version) {
        if (version == null) {
            return true;
        }
        final String normalized = VersionComparator.normalize(version);
        // Preserve legacy behaviour: only hard-block the known bad sentinel version.
        return !"0.0.1".equals(normalized);
    }

    private File getConfigRoot() {
        if (isServer() && isIntegratedServer()) {
            return worldFolder == null ? new File(".") : worldFolder;
        }
        return new File(".", "config");
    }

    public File getConfigFolder() {
        final File root = getConfigRoot();
        return new File(root, Syncmatica.MOD_ID);
    }

    /**
     * @return the save directory of the world this server runs, or null off a
     *         server. Data that describes world blocks rather than schematics
     *         belongs here, so restoring a backup restores it too — on a
     *         dedicated server that is a different place from the config folder.
     */
    public File getWorldFolder() {
        return worldFolder;
    }

    public File getConfigFile() {
        return new File(getConfigFolder(), "config.json");
    }

    public File getAndCreateConfigFile() throws IOException {
        final File configFolder = getConfigFolder();
        if (!configFolder.exists() && !configFolder.mkdirs()) {
            throw new IOException("Failed to create config folder " + configFolder.getAbsolutePath());
        }
        final File configFile = getConfigFile();
        if (!configFile.exists() && !configFile.createNewFile()) {
            throw new IOException("Failed to create config file " + configFile.getAbsolutePath());
        }
        return configFile;
    }

    public void loadConfiguration() {
        boolean attemptToLoad = false;
        JsonObject configuration;
        try {
            configuration = new Gson().fromJson(new BufferedReader(new FileReader(getConfigFile())), JsonObject.class);
            attemptToLoad = true;
        } catch (final Exception ignored) {
            configuration = new JsonObject();
        }
        boolean needsRewrite = false;
        needsRewrite |= applyRootDefaults(configuration);
        if (isServer()) {
            needsRewrite |= loadConfigurationForService(quota, configuration, attemptToLoad);
            if (sharingService != null) {
                needsRewrite |= loadConfigurationForService(sharingService, configuration, attemptToLoad);
            }
            if (buildService != null) {
                needsRewrite |= loadConfigurationForService(buildService, configuration, attemptToLoad);
            }
            if (materialTracking != null) {
                needsRewrite |= loadConfigurationForService(materialTracking, configuration, attemptToLoad);
            }
            if (previews != null) {
                needsRewrite |= loadConfigurationForService(previews, configuration, attemptToLoad);
            }
            if (bridge != null) {
                needsRewrite |= loadConfigurationForService(bridge, configuration, attemptToLoad);
            }
        }
        needsRewrite |= loadConfigurationForService(debugService, configuration, attemptToLoad);
        loadedConfiguration = configuration;
        if (isServer()) {
            configRegistry = new ConfigRegistry();
            quota.registerConfigOptions(configRegistry);
            sharingService.registerConfigOptions(configRegistry);
            buildService.registerConfigOptions(configRegistry);
            materialTracking.registerConfigOptions(configRegistry);
            previews.registerConfigOptions(configRegistry);
            bridge.registerConfigOptions(configRegistry);
            debugService.registerConfigOptions(configRegistry);
            configStore = new ConfigStore(getConfigFile().toPath(), configuration, configRegistry);
        } else {
            configRegistry = null;
            configStore = null;
        }
        if (needsRewrite) {
            try (
                    final Writer writer = new BufferedWriter(new FileWriter(getAndCreateConfigFile()))
            ) {
                final Gson gson = new GsonBuilder().setPrettyPrinting().create();
                final String jsonString = gson.toJson(configuration);
                writer.write(jsonString);
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean applyRootDefaults(final JsonObject configuration) {
        boolean changed = false;
        if (configuration.has("checkupdate")) {
            configuration.remove("checkupdate");
            changed = true;
        }
        if (configuration.has("check_pre_release")) {
            configuration.remove("check_pre_release");
            changed = true;
        }
        return changed;
    }

    private Boolean loadConfigurationForService(final IService service, final JsonObject configuration, final boolean attemptToLoad) {
        final String configKey = service.getConfigKey();
        JsonObject serviceJson = null;
        JsonConfiguration serviceConfiguration = null;
        boolean configured = false;
        boolean needsRewrite = false;

        if (attemptToLoad && configuration.has(configKey)) {
            try {
                serviceJson = configuration.getAsJsonObject(configKey);
                if (serviceJson != null) {
                    serviceConfiguration = new JsonConfiguration(serviceJson);
                    service.configure(serviceConfiguration);
                    configured = true;
                    if (serviceConfiguration.hadError()) {
                        needsRewrite = true;
                    }
                }
            } catch (final Exception e) {
                e.printStackTrace();
                needsRewrite = true;
            }
        }
        if (serviceJson == null) {
            serviceJson = new JsonObject();
            configuration.add(configKey, serviceJson);
            needsRewrite = true;
        }
        if (serviceConfiguration == null) {
            serviceConfiguration = new JsonConfiguration(serviceJson);
        }
        service.getDefaultConfiguration(serviceConfiguration);
        if (serviceConfiguration.didWriteDefaults()) {
            needsRewrite = true;
        }
        if (!configured) {
            service.configure(serviceConfiguration);
        }
        return needsRewrite;
    }

    private void rollbackStartup(final Throwable failure) {
        stopStartedServices(failure);
        playerIdentifierProvider.clear();
        isStarted = false;
    }

    private void stopStartedServices(final Throwable startupFailure) {
        if (managerStarted) {
            stop(synMan::shutdown, startupFailure);
            managerStarted = false;
        }
        if (debugStarted) {
            stop(debugService::shutdown, startupFailure);
            debugStarted = false;
        }
        if (bridgeStarted) {
            stop(bridge::shutdown, startupFailure);
            bridgeStarted = false;
        }
        if (previewsStarted) {
            stop(previews::shutdown, startupFailure);
            previewsStarted = false;
        }
        if (projectsStarted) {
            stop(projects::shutdown, startupFailure);
            projectsStarted = false;
        }
        if (materialsStarted) {
            stop(materialTracking::shutdown, startupFailure);
            materialsStarted = false;
        }
        if (buildStarted) {
            stop(buildService::shutdown, startupFailure);
            buildStarted = false;
        }
        if (sharingStarted) {
            stop(sharingService::shutdown, startupFailure);
            sharingStarted = false;
        }
        if (quotaStarted) {
            stop(quota::shutdown, startupFailure);
            quotaStarted = false;
        }
    }

    private static void stop(final Runnable operation, final Throwable startupFailure) {
        try {
            operation.run();
        } catch (final RuntimeException | Error shutdownFailure) {
            if (startupFailure == null) {
                throw shutdownFailure;
            }
            startupFailure.addSuppressed(shutdownFailure);
        }
    }

    public static class DuplicateContextAssignmentException extends RuntimeException {
        private static final long serialVersionUID = -5147544661160756303L;

        public DuplicateContextAssignmentException(final String reason) {
            super(reason);
        }
    }

    public static class ContextMismatchException extends RuntimeException {
        private static final long serialVersionUID = 2769376183212635479L;

        public ContextMismatchException(final String reason) {
            super(reason);
        }
    }
}
