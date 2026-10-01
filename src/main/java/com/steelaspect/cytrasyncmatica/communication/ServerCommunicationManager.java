package com.steelaspect.cytrasyncmatica.communication;

import com.steelaspect.cytrasyncmatica.Feature;
import com.steelaspect.cytrasyncmatica.LocalLitematicState;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.communication.exchange.*;
import com.steelaspect.cytrasyncmatica.communication.MessageType;
import com.steelaspect.cytrasyncmatica.communication.exchange.FeatureExchange;
import com.steelaspect.cytrasyncmatica.client.network.ShareLitematicExchange;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.schematic.SchematicPeek;
import com.steelaspect.cytrasyncmatica.schematic.SchematicPeeker;
import com.steelaspect.cytrasyncmatica.service.BuildService;
import com.steelaspect.cytrasyncmatica.service.MaterialTrackingService;
import com.steelaspect.cytrasyncmatica.materials.MaterialAccess;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.materials.MaterialWire;
import com.steelaspect.cytrasyncmatica.util.SyncmaticaUtil;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import me.lucko.fabric.api.permissions.v0.Permissions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ServerCommunicationManager extends CommunicationManager {

    private static final Logger LOGGER = LogManager.getLogger(ServerCommunicationManager.class);

    private final Map<UUID, List<PendingShare>> downloadingFile = new HashMap<>();
    private final Map<ExchangeTarget, ServerPlayerEntity> playerMap = new HashMap<>();

    public ServerCommunicationManager() {
        super();
    }

    private void purgeStaleTargets() {
        final List<ExchangeTarget> stale = new ArrayList<>();
        for (final Map.Entry<ExchangeTarget, ServerPlayerEntity> e : playerMap.entrySet()) {
            if (e.getValue() == null || e.getValue().isDisconnected()) {
                stale.add(e.getKey());
            }
        }
        for (final ExchangeTarget target : stale) {
            LOGGER.debug("Purging stale ExchangeTarget: {}", target.getPersistentName());
            onPlayerLeave(target);
        }
    }

    public GameProfile getGameProfile(final ExchangeTarget exchangeTarget) {
        final ServerPlayerEntity player = playerMap.get(exchangeTarget);
        return player == null ? null : player.getGameProfile();
    }

    public void sendMessage(final ExchangeTarget client, final MessageType type, final String identifier) {
        sendMessage(client, type, identifier, "");
    }

    /**
     * The detail carries language-neutral diagnostics (byte counts, limits) that
     * cannot be expressed by the translation key alone. {@link MessageCodec}
     * decides whether the peer can read it.
     */
    public void sendMessage(final ExchangeTarget client, final MessageType type, final String identifier,
                            final String detail) {
        final FeatureSet featureSet = client.getFeatureSet();
        final String trimmedDetail = detail == null ? "" : detail;
        if (featureSet != null && featureSet.hasFeature(Feature.MESSAGE)) {
            final PacketByteBuf newPacketBuf = MessageCodec.encode(featureSet, type, identifier, trimmedDetail);
            client.sendPacket(PacketType.MESSAGE.toIdentifier(client.getProtocolFlavor()), newPacketBuf, context);
        } else if (playerMap.containsKey(client)) {
            final ServerPlayerEntity player = playerMap.get(client);
            final String suffix = trimmedDetail.isEmpty() ? "" : " (" + trimmedDetail + ")";
            sendPlayerNotification(player, "Cytra-Syncmatica " + type.toString() + " " + identifier + suffix);
        }
    }

    /**
     * Reaches the player behind a placement (usually its owner) without the
     * caller having to hold on to an {@link ExchangeTarget}.
     */
    public void sendMessageToPlayer(final UUID playerId, final MessageType type, final String identifier,
                                    final String detail) {
        if (playerId == null) {
            return;
        }
        for (final Map.Entry<ExchangeTarget, ServerPlayerEntity> entry : playerMap.entrySet()) {
            final ServerPlayerEntity player = entry.getValue();
            if (player != null && playerId.equals(SyncmaticaUtil.getProfileId(player.getGameProfile()))) {
                sendMessage(entry.getKey(), type, identifier, detail);
                return;
            }
        }
    }

    public void onPlayerJoin(final ExchangeTarget newPlayer, final ServerPlayerEntity player) {
        final VersionHandshakeServer hi = new VersionHandshakeServer(newPlayer, context);
        playerMap.put(newPlayer, player);
        final GameProfile profile = player.getGameProfile();
        context.getPlayerIdentifierProvider().updateName(SyncmaticaUtil.getProfileId(profile), SyncmaticaUtil.getProfileName(profile));
        startExchangeUnchecked(hi);
    }

    public void onPlayerLeave(final ExchangeTarget oldPlayer) {
        final Collection<Exchange> potentialMessageTarget = oldPlayer.getExchanges();
        if (potentialMessageTarget != null) {
            for (final Exchange target : new ArrayList<>(potentialMessageTarget)) {
                target.close(false);
                notifyClose(target);
            }
        }
        broadcastTargets.remove(oldPlayer);
        playerMap.remove(oldPlayer);
    }

    @Override
    protected void handle(final ExchangeTarget source, final Identifier id, final PacketByteBuf packetBuf) {
        purgeStaleTargets();
        if (!playerMap.containsKey(source)) {
            return;
        }
        final PacketType type = PacketType.fromIdentifier(id);
        if (type == null || !broadcastTargets.contains(source)) {
            return;
        }
        if (type == PacketType.REQUEST_LITEMATIC) {
            final UUID syncmaticaId = packetBuf.readUuid();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(syncmaticaId);
            if (placement == null) {
                return;
            }
            final File toUpload = context.getFileStorage().getLocalLitematic(placement);
            final UploadExchange upload;
            try {
                upload = new UploadExchange(placement, toUpload, source, context);
            } catch (final UploadExchange.TransferLimitExceededException tooLarge) {
                LOGGER.warn("Refusing to serve '{}' to {}: {}",
                        placement.getName(), source.getPersistentName(), tooLarge.getMessage());
                cancelDownload(source, placement);
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.download_exceeds_size_limit",
                        SyncmaticaUtil.formatMegabytes(tooLarge.getFileBytes())
                                + " > " + SyncmaticaUtil.formatMegabytes(tooLarge.getLimitBytes()));
                return;
            } catch (final IOException e) {
                LOGGER.warn("Failed to serve litematic '{}' to {}", placement.getName(), source.getPersistentName(), e);
                cancelDownload(source, placement);
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.file_unavailable");
                return;
            }
            startExchange(upload);
            return;
        }
        if (type == PacketType.REGISTER_METADATA) {
            if (!canShare(source)) {
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.permission_denied");
                return;
            }
            if (context.getSyncmaticManager().getAll().size() >= ProtocolLimits.MAX_SERVER_PLACEMENTS) {
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.too_many_placements");
                return;
            }
            final ServerPlacement placement = receiveMetaData(packetBuf, source);
            if (context.getSyncmaticManager().getPlacement(placement.getId()) != null) {
                cancelShare(source, placement);

                return;
            }

            final GameProfile profile = playerMap.get(source).getGameProfile();
            final PlayerIdentifier playerIdentifier = context.getPlayerIdentifierProvider().createOrGet(profile);
            placement.setOwner(playerIdentifier);
            placement.setLastModifiedBy(playerIdentifier);
            final long now = System.currentTimeMillis();
            placement.setCreatedAtMillis(now);
            placement.setLastModifiedAtMillis(now);

            final LocalLitematicState localState = context.getFileStorage().getLocalState(placement);
            if (!localState.isLocalFileReady()) {

                if (localState == LocalLitematicState.DOWNLOADING_LITEMATIC) {
                    final List<PendingShare> pending = downloadingFile.computeIfAbsent(
                            placement.getHash(),
                            key -> new ArrayList<>()
                    );
                    if (pending.size() >= ProtocolLimits.MAX_ACTIVE_EXCHANGES) {
                        cancelShare(source, placement);
                        return;
                    }
                    pending.add(new PendingShare(source, placement));
                    return;
                }
                try {
                    download(placement, source);
                } catch (final Exception e) {
                    LOGGER.warn("Failed to start litematic download from {}", source.getPersistentName(), e);
                    cancelShare(source, placement);
                }

                return;
            }

            addPlacement(source, placement);

            return;
        }
        if (type == PacketType.REMOVE_SYNCMATIC) {
            final UUID placementId = packetBuf.readUuid();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
            if (placement != null && canManage(source, placement)) {
                final Exchange modifier = getModifier(placement);
                if (modifier != null) {
                    modifier.close(true);
                    notifyClose(modifier);
                }
                context.getSyncmaticManager().removePlacement(placement);
                for (final ExchangeTarget client : broadcastTargets) {
                    final PacketByteBuf newPacketBuf = new PacketByteBuf(Unpooled.buffer());
                    newPacketBuf.writeUuid(placement.getId());
                    client.sendPacket(PacketType.REMOVE_SYNCMATIC.toIdentifier(client.getProtocolFlavor()), newPacketBuf, context);
                }
            } else if (placement != null) {
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.permission_denied");
            }
            return;
        }
        if (type == PacketType.MODIFY_REQUEST) {
            final UUID placementId = packetBuf.readUuid();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
            if (placement == null || !canManage(source, placement)) {
                sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.permission_denied");
                denyModification(source, placementId);
                return;
            }
            final ModifyExchangeServer modifier = new ModifyExchangeServer(placementId, source, context);
            startExchange(modifier);
            return;
        }
        if (type == PacketType.BUILD_REGION_CLAIM) {
            handleBuildRegionClaim(source, packetBuf);
            return;
        }
        if (type == PacketType.MATERIAL_REQUEST) {
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(packetBuf.readUuid());
            if (placement != null && supportsMaterialTracking(source)) {
                sendMaterialList(placement, source);
            }
            return;
        }
        if (type == PacketType.MATERIAL_EDIT) {
            handleMaterialEdit(source, packetBuf);
        }
    }

    // -- material tracking -------------------------------------------------------

    private boolean supportsMaterialTracking(final ExchangeTarget target) {
        final FeatureSet local = context.getFeatureSet();
        final FeatureSet partner = target.getFeatureSet();
        return local != null && partner != null
                && local.hasFeature(Feature.MATERIAL_TRACKING) && partner.hasFeature(Feature.MATERIAL_TRACKING);
    }

    public void sendMaterialList(final ServerPlacement placement, final ExchangeTarget target) {
        final MaterialTrackingService materials = context.getMaterialTracking();
        if (materials == null) {
            return;
        }
        final MaterialList list = materials.getList(placement);
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeUuid(placement.getId());
        final String error = materials.getExtractionError(placement.getId());
        buf.writeString(error == null ? "" : error, ProtocolLimits.MAX_MESSAGE_LENGTH);
        MaterialWire.writeList(buf, list == null ? new MaterialList() : list);
        target.sendPacket(PacketType.MATERIAL_LIST.toIdentifier(target.getProtocolFlavor()), buf, context);
    }

    public void broadcastMaterialList(final ServerPlacement placement) {
        purgeStaleTargets();
        for (final ExchangeTarget client : new ArrayList<>(broadcastTargets)) {
            if (supportsMaterialTracking(client)) {
                sendMaterialList(placement, client);
            }
        }
    }

    public void broadcastMaterialUpdate(final ServerPlacement placement, final MaterialEntry entry) {
        purgeStaleTargets();
        for (final ExchangeTarget client : new ArrayList<>(broadcastTargets)) {
            if (!supportsMaterialTracking(client)) {
                continue;
            }
            final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeUuid(placement.getId());
            MaterialWire.writeEntry(buf, entry);
            client.sendPacket(PacketType.MATERIAL_UPDATE.toIdentifier(client.getProtocolFlavor()), buf, context);
        }
    }

    private void handleMaterialEdit(final ExchangeTarget source, final PacketByteBuf packetBuf) {
        final UUID placementId = packetBuf.readUuid();
        final String itemId = packetBuf.readString(ProtocolLimits.MAX_ITEM_ID_LENGTH);
        final MaterialOp op = MaterialOp.fromOrdinal(packetBuf.readByte());
        final int amount = packetBuf.readInt();
        final MaterialTrackingService materials = context.getMaterialTracking();
        final ServerPlayerEntity player = playerMap.get(source);
        if (materials == null || !materials.isEnabled() || op == null || player == null) {
            return;
        }
        final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
        if (placement == null) {
            return;
        }
        if (!canEditMaterials(player, op)) {
            sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.permission_denied");
            return;
        }
        final PlayerIdentifier editor = context.getPlayerIdentifierProvider().createOrGet(player.getGameProfile());
        final MaterialTrackingService.Outcome outcome = materials.apply(placement, itemId, op, amount, editor);
        if (outcome == MaterialTrackingService.Outcome.UNKNOWN_ITEM || outcome == MaterialTrackingService.Outcome.NO_LIST) {
            sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.materials.unknown_item", itemId);
        }
    }

    /** The same rule the bridge applies to a bot's acting player. */
    public static boolean canEditMaterials(final ServerPlayerEntity player, final MaterialOp op) {
        if (op == MaterialOp.RESET) {
            return Permissions.check(player, MaterialAccess.RESET_PERMISSION, MaterialAccess.RESET_PERMISSION_LEVEL);
        }
        return Permissions.check(player, MaterialAccess.EDIT_PERMISSION, MaterialAccess.EDIT_FALLBACK);
    }

    /**
     * Claiming a sub-region records who is responsible for building it. The rule
     * about who may hold one lives in {@link BuildService}; this only resolves the
     * actors and turns the outcome into a reply.
     */
    private void handleBuildRegionClaim(final ExchangeTarget source, final PacketByteBuf packetBuf) {
        final UUID placementId = packetBuf.readUuid();
        final String regionName = packetBuf.readString(ProtocolLimits.MAX_SUBREGION_NAME_LENGTH);

        final BuildService buildService = context.getBuildService();
        if (buildService == null || !buildService.isEnabled()) {
            return;
        }
        final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
        if (placement == null) {
            return;
        }
        final ServerPlayerEntity player = playerMap.get(source);
        if (player == null || !Permissions.check(player, PlacementAccessPolicy.BUILD_CLAIM_PERMISSION, true)) {
            sendMessage(source, MessageType.ERROR, "cytra-syncmatica.error.permission_denied");
            return;
        }
        final PlayerIdentifier claimer = context.getPlayerIdentifierProvider().createOrGet(player.getGameProfile());
        if (buildService.toggleClaim(placement, regionName, claimer) == BuildService.ClaimOutcome.ALREADY_CLAIMED) {
            final PlayerIdentifier owner = buildService.getClaimant(placement, regionName);
            sendMessage(source, MessageType.WARNING, "cytra-syncmatica.error.build.region_taken",
                    owner == null ? "" : owner.getName());
        }
    }

    @Override
    protected void handleExchange(final Exchange exchange) {
        if (exchange instanceof DownloadExchange) {
            final ServerPlacement p = ((DownloadExchange) exchange).getPlacement();

            if (exchange.isSuccessful()) {
                addPlacement(exchange.getPartner(), p);
                if (downloadingFile.containsKey(p.getHash())) {
                    for (final PendingShare pending : downloadingFile.get(p.getHash())) {
                        addPlacement(pending.source, pending.placement);
                    }
                }
            } else {
                cancelShare(exchange.getPartner(), p);
                if (downloadingFile.containsKey(p.getHash())) {
                    for (final PendingShare pending : downloadingFile.get(p.getHash())) {
                        cancelShare(pending.source, pending.placement);
                    }
                }
            }

            downloadingFile.remove(p.getHash());
            return;
        }
        if (exchange instanceof VersionHandshakeServer && exchange.isSuccessful()) {
            if (!broadcastTargets.contains(exchange.getPartner())) {
                broadcastTargets.add(exchange.getPartner());
            }
        }
        if (exchange instanceof ModifyExchangeServer && exchange.isSuccessful()) {
            final ServerPlacement placement = ((ModifyExchangeServer) exchange).getPlacement();
            broadcastPlacementUpdate(placement);
        }
    }

    public void reloadFeatureState() {
        purgeStaleTargets();
        for (final ExchangeTarget client : new ArrayList<>(broadcastTargets)) {
            startExchange(new VersionHandshakeServer(client, context));
        }
    }

    public void broadcastPlacementUpdate(final ServerPlacement placement) {
        purgeStaleTargets();
        final Exchange activeModifier = getModifier(placement);
        for (final ExchangeTarget client : broadcastTargets) {
            if (activeModifier != null && activeModifier.getPartner() == client) {
                // This client is mid-modification on the placement and holds a newer
                // pose than the server does. Its MODIFY_FINISH will carry the new
                // state and trigger the broadcast that follows it.
                continue;
            }
            final FeatureSet clientFeatures = client.getFeatureSet();
            if (clientFeatures != null && clientFeatures.hasFeature(Feature.MODIFY)) {
                final PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
                buf.writeUuid(placement.getId());
                putModificationData(placement, buf, client);
                if (clientFeatures.hasFeature(Feature.CORE_EX)) {
                    buf.writeUuid(placement.getLastModifiedBy().uuid);
                    buf.writeString(placement.getLastModifiedBy().getName(), ProtocolLimits.MAX_PLAYER_NAME_LENGTH);
                    if (supportsTimestamps(client)) {
                        buf.writeLong(placement.getLastModifiedAtMillis());
                    }
                }
                client.sendPacket(PacketType.MODIFY.toIdentifier(client.getProtocolFlavor()), buf, context);
                continue;
            }
            final PacketByteBuf removal = new PacketByteBuf(Unpooled.buffer());
            removal.writeUuid(placement.getId());
            client.sendPacket(PacketType.REMOVE_SYNCMATIC.toIdentifier(client.getProtocolFlavor()), removal, context);
            final PacketByteBuf registration = new PacketByteBuf(Unpooled.buffer());
            putMetaData(placement, registration, client);
            client.sendPacket(PacketType.REGISTER_METADATA.toIdentifier(client.getProtocolFlavor()), registration, context);
        }
    }

    private void addPlacement(final ExchangeTarget t, final ServerPlacement placement) {
        if (!registerNewPlacement(placement)) {
            cancelShare(t, placement);
        }
    }

    public boolean registerNewPlacement(final ServerPlacement placement) {
        if (context.getSyncmaticManager().getPlacement(placement.getId()) != null
                || context.getSyncmaticManager().getAll().size() >= ProtocolLimits.MAX_SERVER_PLACEMENTS) {
            return false;
        }
        applyAuthoritativeMetadata(placement);
        context.getSyncmaticManager().addPlacement(placement);
        for (final ExchangeTarget target : broadcastTargets) {
            sendMetaData(placement, target);
        }
        return true;
    }

    /**
     * Placement names come from compatible clients; the stored litematic only
     * fills names missing from legacy peers and remains authoritative for
     * schematic versions.
     */
    private void applyAuthoritativeMetadata(final ServerPlacement placement) {
        final File litematic = context.getFileStorage().getLocalLitematic(placement);
        final SchematicPeek peek = SchematicPeeker.peek(litematic);
        if (peek == null) {
            return;
        }
        if (!placement.hasDisplayName() && peek.hasName()) {
            placement.setDisplayName(peek.getName());
        }
        placement.setVersion(peek.getLitematicVersion(), peek.getDataVersion());
    }

    private void cancelShare(final ExchangeTarget source, final ServerPlacement placement) {
        final PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
        packetByteBuf.writeUuid(placement.getId());
        source.sendPacket(PacketType.CANCEL_SHARE.toIdentifier(source.getProtocolFlavor()), packetByteBuf, context);
    }

    /**
     * Releases the requester's pending download exchange instead of leaving it
     * to expire on the exchange timeout.
     */
    private void cancelDownload(final ExchangeTarget source, final ServerPlacement placement) {
        final PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
        packetByteBuf.writeUuid(placement.getId());
        source.sendPacket(PacketType.CANCEL_LITEMATIC.toIdentifier(source.getProtocolFlavor()), packetByteBuf, context);
    }

    private void denyModification(final ExchangeTarget source, final UUID placementId) {
        final PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
        packetByteBuf.writeUuid(placementId);
        source.sendPacket(PacketType.MODIFY_REQUEST_DENY.toIdentifier(source.getProtocolFlavor()), packetByteBuf, context);
    }

    private boolean canShare(final ExchangeTarget source) {
        final ServerPlayerEntity player = playerMap.get(source);
        return player != null && Permissions.check(player, PlacementAccessPolicy.SHARE_PERMISSION, true);
    }

    private boolean canManage(final ExchangeTarget source, final ServerPlacement placement) {
        final ServerPlayerEntity player = playerMap.get(source);
        if (player == null || placement == null || placement.getOwner() == null) {
            return false;
        }
        final UUID playerId = SyncmaticaUtil.getProfileId(player.getGameProfile());
        final boolean elevated = Permissions.check(
                player,
                PlacementAccessPolicy.MANAGE_PERMISSION,
                PlacementAccessPolicy.MANAGE_PERMISSION_LEVEL
        );
        return PlacementAccessPolicy.canManage(playerId, placement.getOwner().uuid, elevated);
    }

    @Override
    protected Collection<ExchangeTarget> getTickTargets() {
        return playerMap.keySet();
    }

    private void sendPlayerNotification(final ServerPlayerEntity player, final String message) {
        player.sendMessageToClient(Text.literal(message), false);
    }

    private static final class PendingShare {
        private final ExchangeTarget source;
        private final ServerPlacement placement;

        private PendingShare(final ExchangeTarget source, final ServerPlacement placement) {
            this.source = source;
            this.placement = placement;
        }
    }
}
