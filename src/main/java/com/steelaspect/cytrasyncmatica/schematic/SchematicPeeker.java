package com.steelaspect.cytrasyncmatica.schematic;

import com.steelaspect.cytrasyncmatica.util.NbtHelper;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Reads litematic metadata (name, format version, Minecraft data version)
 * from a file without instantiating litematica classes, so it is usable on
 * both the dedicated server and the client.
 */
public final class SchematicPeeker {

    private static final Logger LOGGER = LogManager.getLogger(SchematicPeeker.class);
    private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

    private SchematicPeeker() {
    }

    /**
     * Returns the peeked metadata, or null if the file is missing, unreadable
     * or not a litematic.
     */
    public static SchematicPeek peek(final File litematicFile) {
        if (litematicFile == null || !litematicFile.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(litematicFile)) {
            final NbtCompound root = NbtIo.readCompressed(input, NbtSizeTracker.of(MAX_NBT_BYTES));
            final NbtCompound metadata = NbtHelper.getCompound(root, "Metadata");
            if (metadata == null) {
                return null;
            }
            final int version = NbtHelper.containsNumber(root, "Version")
                    ? NbtHelper.getInt(root, "Version")
                    : SchematicPeek.UNKNOWN_VERSION;
            final int dataVersion = NbtHelper.containsNumber(root, "MinecraftDataVersion")
                    ? NbtHelper.getInt(root, "MinecraftDataVersion")
                    : SchematicPeek.UNKNOWN_VERSION;
            return new SchematicPeek(NbtHelper.getString(metadata, "Name"), version, dataVersion);
        } catch (final Exception exception) {
            LOGGER.debug("Failed to peek litematic metadata from {}", litematicFile, exception);
            return null;
        }
    }
}
