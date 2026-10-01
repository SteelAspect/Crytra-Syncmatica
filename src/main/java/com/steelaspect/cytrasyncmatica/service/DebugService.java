package com.steelaspect.cytrasyncmatica.service;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.service.IServiceConfiguration;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;

public class DebugService extends AbstractService {

    private static final boolean PACKET_LOGGING_DEFAULT = false;
    private boolean doPacketLogging = false;

    public void logReceivePacket(final Identifier packageType) {
        if (doPacketLogging) {
            LogManager.getLogger(Syncmatica.class).info("Cytra-Syncmatica - received packet:[type={}]", packageType);
        }
    }

    public void logSendPacket(final Identifier packetType, final String targetIdentifier) {
        if (doPacketLogging) {
            LogManager.getLogger(Syncmatica.class).info(
                    "Sending packet[type={}] to ExchangeTarget[id={}]",
                    packetType,
                    targetIdentifier
            );
        }
    }

    @Override
    public void getDefaultConfiguration(final IServiceConfiguration configuration) {
        final ConfigRegistry registry = new ConfigRegistry();
        registerConfigOptions(registry);
        registry.saveDefaults(getConfigKey(), configuration);
    }

    @Override
    public String getConfigKey() {
        return "debug";
    }

    @Override
    public void configure(final IServiceConfiguration configuration) {
        configuration.loadBoolean("doPackageLogging", this::setPacketLogging);
    }

    public void registerConfigOptions(final ConfigRegistry registry) {
        registry.add(ConfigOption.bool(
                getConfigKey(), "doPackageLogging", PACKET_LOGGING_DEFAULT,
                () -> doPacketLogging, this::setPacketLogging));
    }

    private void setPacketLogging(final boolean value) {
        doPacketLogging = value;
    }

    @Override
    public void startup() {
    }

    @Override
    public void shutdown() {
    }
}
