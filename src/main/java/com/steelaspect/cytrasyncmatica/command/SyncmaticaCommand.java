package com.steelaspect.cytrasyncmatica.command;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.ServerPlacement;
import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.communication.PlacementAccessPolicy;
import com.steelaspect.cytrasyncmatica.communication.ServerCommunicationManager;
import com.steelaspect.cytrasyncmatica.extended_core.PlayerIdentifier;
import com.steelaspect.cytrasyncmatica.schematic.SchematicPeek;
import com.steelaspect.cytrasyncmatica.schematic.SchematicPeeker;
import com.steelaspect.cytrasyncmatica.service.ConfigOption;
import com.steelaspect.cytrasyncmatica.service.ConfigRegistry;
import com.steelaspect.cytrasyncmatica.util.SyncmaticaUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;

public final class SyncmaticaCommand {
    private static final String LOAD_PERMISSION = "cytra-syncmatica.command.load";
    private static final String CONFIG_PERMISSION = "cytra-syncmatica.config";
    private static final String LITEMATIC_EXTENSION = ".litematic";
    private static final Map<String, CachedPeek> PEEK_CACHE = new HashMap<>();

    private SyncmaticaCommand() {
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("cytra-syncmatica")
                .then(loadArgument())
                .then(configArgument())
                .then(exportArgument())
                .then(whereArgument())
                .then(shoppingArgument())
                .then(linkArgument())
                .then(projectCommands())
                .then(rescanArgument());
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> exportArgument() {
        return CommandManager.literal("export")
                .then(CommandManager.argument("schematic", greedyString())
                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                        .executes(SyncmaticaCommand::handleExport));
    }

    private static CompletableFuture<Suggestions> suggestPlacementNames(
            final CommandContext<ServerCommandSource> context, final SuggestionsBuilder builder) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext != null) {
            final String typed = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            syncmaticaContext.getSyncmaticManager().getAll().stream()
                    .map(ServerPlacement::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed))
                    .forEach(name -> builder.suggest(name.contains(" ") ? "\"" + name + "\"" : name));
        }
        return builder.buildFuture();
    }

    /** Resolves a placement by display name, file name or id prefix. */
    public static Optional<ServerPlacement> findPlacementByName(final Context syncmaticaContext, final String raw) {
        final String name = raw == null ? "" : raw.trim().replaceAll("^\"|\"$", "");
        final Collection<ServerPlacement> all = syncmaticaContext.getSyncmaticManager().getAll();
        Optional<ServerPlacement> hit = all.stream().filter(p -> p.getName().equals(name)).findFirst();
        if (hit.isEmpty()) {
            hit = all.stream().filter(p -> p.getName().equalsIgnoreCase(name) || p.getFileName().equalsIgnoreCase(name)).findFirst();
        }
        if (hit.isEmpty() && name.length() >= 8) {
            hit = all.stream().filter(p -> p.getId().toString().startsWith(name.toLowerCase(java.util.Locale.ROOT))).findFirst();
        }
        return hit;
    }

    private static int handleExport(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null || syncmaticaContext.getMaterialTracking() == null) {
            context.getSource().sendError(literal("Material tracking is unavailable"));
            return 0;
        }
        final String name = context.getArgument("schematic", String.class);
        final Optional<ServerPlacement> placement = findPlacementByName(syncmaticaContext, name);
        final Optional<com.steelaspect.cytrasyncmatica.projects.Project> project = placement.isPresent() || syncmaticaContext.getProjects() == null
                ? Optional.empty() : syncmaticaContext.getProjects().findByName(name);
        if (placement.isEmpty() && project.isEmpty()) {
            context.getSource().sendError(literal("Unknown shared schematic or project: " + name));
            return 0;
        }
        final java.nio.file.Path folder = new File(syncmaticaContext.getConfigFolder(), "exports").toPath();
        final ServerCommandSource source = context.getSource();
        final String exportName = placement.isPresent() ? placement.get().getName() : project.get().getName();
        final CompletableFuture<List<java.nio.file.Path>> export = placement.isPresent()
                ? syncmaticaContext.getMaterialTracking().export(placement.get(), folder)
                : syncmaticaContext.getMaterialTracking().exportList(exportName,
                        syncmaticaContext.getProjects().combined(project.get()).list(), folder);
        export.whenComplete((paths, error) -> {
            final Runnable reply = () -> {
                if (error != null) {
                    source.sendError(literal("Export failed: " + error.getMessage()));
                } else {
                    source.sendFeedback(() -> literal("Exported materials of '" + exportName + "' to "
                            + paths.get(0).getFileName() + " and " + paths.get(1).getFileName() + " in " + folder), false);
                }
            };
            if (source.getServer() != null) {
                source.getServer().execute(reply);
            } else {
                reply.run();
            }
        });
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> shoppingArgument() {
        return CommandManager.literal("shopping")
                .then(CommandManager.argument("schematic", greedyString())
                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                        .executes(SyncmaticaCommand::handleShopping));
    }

    private static final int SHOPPING_CHAT_LINES = 15;

    /** What is still missing, grouped, with a click-to-copy of the full text. Trailing "group:<name>" filters. */
    private static int handleShopping(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null || syncmaticaContext.getMaterialTracking() == null) {
            context.getSource().sendError(literal("Material tracking is unavailable"));
            return 0;
        }
        String name = context.getArgument("schematic", String.class);
        String group = null;
        final int at = name.lastIndexOf(" group:");
        if (at > 0) {
            group = name.substring(at + " group:".length()).trim();
            name = name.substring(0, at).trim();
        }
        final Optional<ServerPlacement> found = findPlacementByName(syncmaticaContext, name);
        final Optional<com.steelaspect.cytrasyncmatica.projects.Project> project = found.isPresent() || syncmaticaContext.getProjects() == null
                ? Optional.empty() : syncmaticaContext.getProjects().findByName(name);
        if (found.isEmpty() && project.isEmpty()) {
            context.getSource().sendError(literal("Unknown shared schematic or project: " + name));
            return 0;
        }
        final com.steelaspect.cytrasyncmatica.materials.MaterialList list = found.isPresent()
                ? syncmaticaContext.getMaterialTracking().getList(found.get())
                : syncmaticaContext.getProjects().combined(project.get()).list();
        final String targetName = found.isPresent() ? found.get().getName() : project.get().getName();
        if (list == null) {
            final String error = syncmaticaContext.getMaterialTracking().getExtractionError(found.get().getId());
            context.getSource().sendError(literal(error == null ? "No material list yet for " + targetName : error));
            return 0;
        }
        final List<com.steelaspect.cytrasyncmatica.materials.ShoppingList.Line> lines =
                com.steelaspect.cytrasyncmatica.materials.ShoppingList.build(list, null,
                        com.steelaspect.cytrasyncmatica.service.MaterialTrackingService::stackSizeOf, group);
        final String title = targetName + (group == null ? "" : " (" + group + ")");
        final String full = com.steelaspect.cytrasyncmatica.materials.ShoppingList.toText(title, lines);
        final ServerCommandSource source = context.getSource();
        if (lines.isEmpty()) {
            source.sendFeedback(() -> literal("Nothing left to gather for " + title), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("Shopping list for " + title + ": ").formatted(net.minecraft.util.Formatting.GOLD)
                .append(Text.literal(com.steelaspect.cytrasyncmatica.materials.ShoppingList.totalItems(lines) + " items, about "
                        + com.steelaspect.cytrasyncmatica.materials.ShoppingList.totalShulkers(lines) + " shulker boxes ").formatted(net.minecraft.util.Formatting.GRAY))
                .append(Text.literal("[copy all]").setStyle(net.minecraft.text.Style.EMPTY
                        .withColor(net.minecraft.util.Formatting.AQUA)
                        .withClickEvent(new net.minecraft.text.ClickEvent.CopyToClipboard(full))
                        .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Copy the whole list to the clipboard"))))), false);
        String current = null;
        int shown = 0;
        for (final com.steelaspect.cytrasyncmatica.materials.ShoppingList.Line l : lines) {
            if (shown++ >= SHOPPING_CHAT_LINES) {
                final int more = lines.size() - SHOPPING_CHAT_LINES;
                source.sendFeedback(() -> literal("  … and " + more + " more (click [copy all] above)"), false);
                break;
            }
            if (!l.group().equals(current)) {
                current = l.group();
                final String g = current;
                source.sendFeedback(() -> Text.literal("  " + g).formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            source.sendFeedback(() -> Text.literal("    " + com.steelaspect.cytrasyncmatica.materials.ShoppingList.prettyName(l.itemId()) + ": ")
                    .append(Text.literal(l.text()).formatted(net.minecraft.util.Formatting.WHITE))
                    .append(Text.literal(" (" + l.remaining() + ")").formatted(net.minecraft.util.Formatting.DARK_GRAY)), false);
        }
        return 1;
    }

    // -- projects ---------------------------------------------------------------------

    private static LiteralArgumentBuilder<ServerCommandSource> projectCommands() {
        return CommandManager.literal("project")
                .then(CommandManager.literal("list").executes(SyncmaticaCommand::handleProjectList))
                .then(CommandManager.literal("info")
                        .then(CommandManager.argument("project", greedyString())
                                .suggests(SyncmaticaCommand::suggestProjectNames)
                                .executes(SyncmaticaCommand::handleProjectInfo)))
                .then(CommandManager.literal("create")
                        .requires(SyncmaticaCommand::hasProjectPermission)
                        .then(CommandManager.argument("name", greedyString())
                                .executes(SyncmaticaCommand::handleProjectCreate)))
                .then(CommandManager.literal("delete")
                        .requires(SyncmaticaCommand::hasProjectPermission)
                        .then(CommandManager.argument("project", greedyString())
                                .suggests(SyncmaticaCommand::suggestProjectNames)
                                .executes(SyncmaticaCommand::handleProjectDelete)))
                .then(CommandManager.literal("add")
                        .requires(SyncmaticaCommand::hasProjectPermission)
                        .then(CommandManager.argument("project", string())
                                .suggests(SyncmaticaCommand::suggestProjectNames)
                                .then(CommandManager.argument("schematic", greedyString())
                                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                                        .executes(c -> handleProjectMember(c, true)))))
                .then(CommandManager.literal("remove")
                        .requires(SyncmaticaCommand::hasProjectPermission)
                        .then(CommandManager.argument("project", string())
                                .suggests(SyncmaticaCommand::suggestProjectNames)
                                .then(CommandManager.argument("schematic", greedyString())
                                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                                        .executes(c -> handleProjectMember(c, false)))));
    }

    private static boolean hasProjectPermission(final ServerCommandSource source) {
        return Permissions.check(source, com.steelaspect.cytrasyncmatica.projects.ProjectAccess.MANAGE_PERMISSION,
                com.steelaspect.cytrasyncmatica.projects.ProjectAccess.MANAGE_PERMISSION_LEVEL);
    }

    private static CompletableFuture<Suggestions> suggestProjectNames(final CommandContext<ServerCommandSource> context, final SuggestionsBuilder builder) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext != null && syncmaticaContext.getProjects() != null) {
            final String remaining = builder.getRemaining().toLowerCase();
            for (final com.steelaspect.cytrasyncmatica.projects.Project p : syncmaticaContext.getProjects().all()) {
                if (p.getName().toLowerCase().startsWith(remaining)) {
                    builder.suggest(p.getName().contains(" ") ? '"' + p.getName() + '"' : p.getName());
                }
            }
        }
        return builder.buildFuture();
    }

    private static com.steelaspect.cytrasyncmatica.service.ProjectService projects(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null || syncmaticaContext.getProjects() == null) {
            context.getSource().sendError(literal("Projects are unavailable"));
            return null;
        }
        return syncmaticaContext.getProjects();
    }

    private static PlayerIdentifier actor(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        final ServerPlayerEntity player = context.getSource().getPlayer();
        return player == null || syncmaticaContext == null ? null
                : syncmaticaContext.getPlayerIdentifierProvider().createOrGet(player.getGameProfile());
    }

    private static int handleProjectList(final CommandContext<ServerCommandSource> context) {
        final com.steelaspect.cytrasyncmatica.service.ProjectService projects = projects(context);
        if (projects == null) {
            return 0;
        }
        if (projects.all().isEmpty()) {
            sendFeedback(context, "No projects yet. Create one with /cytra-syncmatica project create <name>");
            return 1;
        }
        for (final com.steelaspect.cytrasyncmatica.projects.Project p : projects.all()) {
            final com.steelaspect.cytrasyncmatica.materials.CombinedList.Combined c = projects.combined(p);
            final String progress = c.list().isEmpty() ? "no materials yet"
                    : String.format(java.util.Locale.ROOT, "%.1f%% · %d remaining", c.list().percentComplete(), c.list().totalRemaining());
            sendFeedback(context, p.getName() + " — " + projects.members(p).size() + " schematic(s) · " + progress);
        }
        return 1;
    }

    private static int handleProjectInfo(final CommandContext<ServerCommandSource> context) {
        final com.steelaspect.cytrasyncmatica.service.ProjectService projects = projects(context);
        if (projects == null) {
            return 0;
        }
        final String name = context.getArgument("project", String.class);
        final Optional<com.steelaspect.cytrasyncmatica.projects.Project> found = projects.findByName(name);
        if (found.isEmpty()) {
            context.getSource().sendError(literal("Unknown project: " + name));
            return 0;
        }
        final com.steelaspect.cytrasyncmatica.projects.Project p = found.get();
        final com.steelaspect.cytrasyncmatica.materials.CombinedList.Combined c = projects.combined(p);
        sendFeedback(context, "Project " + p.getName() + (p.getCreatedBy().isEmpty() ? "" : " (created by " + p.getCreatedBy() + ")"));
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        for (final ServerPlacement m : projects.members(p)) {
            final com.steelaspect.cytrasyncmatica.materials.MaterialList l = syncmaticaContext.getMaterialTracking() == null ? null
                    : syncmaticaContext.getMaterialTracking().getList(m);
            final String progress = l == null ? "no list yet" : String.format(java.util.Locale.ROOT, "%.1f%%", l.percentComplete());
            sendFeedback(context, "  " + m.getName() + " · " + m.getDimension().replace("minecraft:", "") + " · " + progress);
        }
        if (projects.members(p).isEmpty()) {
            sendFeedback(context, "  (no schematics; add one with /cytra-syncmatica project add \"" + p.getName() + "\" <schematic>)");
        } else {
            sendFeedback(context, String.format(java.util.Locale.ROOT, "  combined: %.1f%% · %d of %d items gathered · %d remaining%s",
                    c.list().percentComplete(), c.list().totalGathered(), c.list().totalRequired(), c.list().totalRemaining(),
                    c.missingLists() > 0 ? " · " + c.missingLists() + " list(s) still loading" : ""));
        }
        return 1;
    }

    private static int handleProjectCreate(final CommandContext<ServerCommandSource> context) {
        final com.steelaspect.cytrasyncmatica.service.ProjectService projects = projects(context);
        if (projects == null) {
            return 0;
        }
        try {
            final com.steelaspect.cytrasyncmatica.projects.Project p = projects.create(context.getArgument("name", String.class), actor(context));
            sendFeedback(context, "Created project " + p.getName() + ". Add schematics with /cytra-syncmatica project add \"" + p.getName() + "\" <schematic>");
            return 1;
        } catch (final IllegalArgumentException e) {
            context.getSource().sendError(literal("Cannot create project: " + e.getMessage()));
            return 0;
        }
    }

    private static int handleProjectDelete(final CommandContext<ServerCommandSource> context) {
        final com.steelaspect.cytrasyncmatica.service.ProjectService projects = projects(context);
        if (projects == null) {
            return 0;
        }
        final String name = context.getArgument("project", String.class);
        final Optional<com.steelaspect.cytrasyncmatica.projects.Project> found = projects.findByName(name);
        if (found.isEmpty()) {
            context.getSource().sendError(literal("Unknown project: " + name));
            return 0;
        }
        projects.delete(found.get(), actor(context));
        sendFeedback(context, "Deleted project " + found.get().getName() + " (its schematics stay shared)");
        return 1;
    }

    private static int handleProjectMember(final CommandContext<ServerCommandSource> context, final boolean add) {
        final com.steelaspect.cytrasyncmatica.service.ProjectService projects = projects(context);
        if (projects == null) {
            return 0;
        }
        final String name = context.getArgument("project", String.class);
        final Optional<com.steelaspect.cytrasyncmatica.projects.Project> found = projects.findByName(name);
        if (found.isEmpty()) {
            context.getSource().sendError(literal("Unknown project: " + name));
            return 0;
        }
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        final String schematic = context.getArgument("schematic", String.class);
        final Optional<ServerPlacement> placement = findPlacementByName(syncmaticaContext, schematic);
        if (placement.isEmpty()) {
            context.getSource().sendError(literal("Unknown shared schematic: " + schematic));
            return 0;
        }
        final boolean changed = add ? projects.addMember(found.get(), placement.get(), actor(context))
                : projects.removeMember(found.get(), placement.get(), actor(context));
        if (!changed) {
            context.getSource().sendError(literal(add ? placement.get().getName() + " is already in " + found.get().getName() + " (or the project is full)"
                    : placement.get().getName() + " is not in " + found.get().getName()));
            return 0;
        }
        sendFeedback(context, (add ? "Added " : "Removed ") + placement.get().getName() + (add ? " to " : " from ") + found.get().getName());
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> whereArgument() {
        return CommandManager.literal("where")
                .then(CommandManager.argument("schematic", greedyString())
                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                        .executes(SyncmaticaCommand::handleWhere));
    }

    /** Dimension, origin, centre and (same dimension) distance; coordinates are clickable to copy. */
    private static int handleWhere(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null) {
            context.getSource().sendError(literal("Cytra-Syncmatica server context unavailable"));
            return 0;
        }
        final String name = context.getArgument("schematic", String.class);
        final Optional<ServerPlacement> found = findPlacementByName(syncmaticaContext, name);
        final ServerCommandSource source = context.getSource();
        final boolean mayseeCoordinates = !syncmaticaContext.getSharingService().isHideCoordinatesWithoutPermission()
                || Permissions.check(source, com.steelaspect.cytrasyncmatica.service.SharingService.WHERE_PERMISSION, true);
        if (found.isEmpty()) {
            final Optional<com.steelaspect.cytrasyncmatica.projects.Project> project = syncmaticaContext.getProjects() == null
                    ? Optional.empty() : syncmaticaContext.getProjects().findByName(name);
            if (project.isEmpty()) {
                context.getSource().sendError(literal("Unknown shared schematic or project: " + name));
                return 0;
            }
            sendFeedback(context, "Project " + project.get().getName() + ":");
            for (final ServerPlacement m : syncmaticaContext.getProjects().members(project.get())) {
                for (final net.minecraft.text.Text line : describeLocation(syncmaticaContext, m, source, mayseeCoordinates)) {
                    source.sendFeedback(() -> line, false);
                }
            }
            return 1;
        }
        for (final net.minecraft.text.Text line : describeLocation(syncmaticaContext, found.get(), source, mayseeCoordinates)) {
            source.sendFeedback(() -> line, false);
        }
        return 1;
    }

    static List<net.minecraft.text.Text> describeLocation(final Context syncmaticaContext, final ServerPlacement placement,
                                                           final ServerCommandSource source, final boolean showCoordinates) {
        final List<net.minecraft.text.Text> lines = new ArrayList<>();
        final String dimension = placement.getDimension();
        final BlockPos origin = placement.getPosition();
        final com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor.Stats stats =
                syncmaticaContext.getMaterialTracking() == null
                        ? com.steelaspect.cytrasyncmatica.materials.MaterialListExtractor.Stats.EMPTY
                        : syncmaticaContext.getMaterialTracking().getStats(placement.getId());
        final BlockPos centre = stats.volume() > 0
                ? origin.add(stats.sizeX() / 2, stats.sizeY() / 2, stats.sizeZ() / 2)
                : origin;
        final net.minecraft.text.MutableText head = Text.literal(placement.getName()).formatted(net.minecraft.util.Formatting.GOLD)
                .append(Text.literal(" in ").formatted(net.minecraft.util.Formatting.GRAY))
                .append(Text.literal(dimension.replace("minecraft:", "")).formatted(net.minecraft.util.Formatting.AQUA));
        lines.add(head);
        if (showCoordinates) {
            lines.add(Text.literal("  origin ").formatted(net.minecraft.util.Formatting.GRAY).append(copyable(origin)));
            if (stats.volume() > 0) {
                lines.add(Text.literal("  centre ").formatted(net.minecraft.util.Formatting.GRAY).append(copyable(centre))
                        .append(Text.literal("  size " + stats.sizeX() + "×" + stats.sizeY() + "×" + stats.sizeZ())
                                .formatted(net.minecraft.util.Formatting.DARK_GRAY)));
            }
        } else {
            lines.add(Text.literal("  coordinates are hidden on this server").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        }
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            final String playerDimension = player.getEntityWorld().getRegistryKey().getValue().toString();
            if (playerDimension.equals(dimension)) {
                final double distance = Math.sqrt(player.getBlockPos().getSquaredDistance(centre));
                lines.add(Text.literal(String.format(java.util.Locale.ROOT, "  %.0f blocks away", distance))
                        .formatted(net.minecraft.util.Formatting.GREEN));
            } else {
                lines.add(Text.literal("  you are in another dimension").formatted(net.minecraft.util.Formatting.DARK_GRAY));
            }
        }
        return lines;
    }

    private static net.minecraft.text.Text copyable(final BlockPos pos) {
        final String text = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Text.literal(text).styled(style -> style
                .withColor(net.minecraft.util.Formatting.YELLOW)
                .withClickEvent(new net.minecraft.text.ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Click to copy " + text))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> linkArgument() {
        return CommandManager.literal("link").executes(SyncmaticaCommand::handleLink);
    }

    /** Hands the player a one-time code that the Discord bot's /link command claims through the bridge. */
    private static int handleLink(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        final Entity entity = context.getSource().getEntity();
        if (!(entity instanceof ServerPlayerEntity player)) {
            context.getSource().sendError(literal("Only a player can link an account"));
            return 0;
        }
        if (syncmaticaContext == null || syncmaticaContext.getBridge() == null) {
            context.getSource().sendError(literal("The Discord bridge is unavailable"));
            return 0;
        }
        if (!syncmaticaContext.getBridge().isAvailable()) {
            context.getSource().sendError(literal("Discord linking is off: Cytra Link is not installed on this server"));
            return 0;
        }
        final String code = syncmaticaContext.getBridge().getLinkCodes().issue(
                SyncmaticaUtil.getProfileId(player.getGameProfile()), SyncmaticaUtil.getProfileName(player.getGameProfile()));
        sendPrivateFeedback(context, "Your Discord link code is " + code + " (valid 10 minutes). In Discord, run: /link " + code);
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> configArgument() {
        return CommandManager.literal("config")
                .requires(SyncmaticaCommand::hasConfigPermission)
                .then(CommandManager.literal("list")
                        .executes(context -> handleConfigList(context, null))
                        .then(CommandManager.argument("section", string())
                                .suggests(SyncmaticaCommand::suggestConfigSections)
                                .executes(context -> handleConfigList(
                                        context, context.getArgument("section", String.class)))))
                .then(configReadArgument("get", SyncmaticaCommand::handleConfigGet))
                .then(configReadArgument("reset", SyncmaticaCommand::handleConfigReset))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("section", string())
                                .suggests(SyncmaticaCommand::suggestConfigSections)
                                .then(CommandManager.argument("key", string())
                                        .suggests(SyncmaticaCommand::suggestConfigKeys)
                                        .then(CommandManager.argument("value", string())
                                                .suggests(SyncmaticaCommand::suggestConfigValues)
                                                .executes(SyncmaticaCommand::handleConfigSet)))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> configReadArgument(
            final String operation,
            final com.mojang.brigadier.Command<ServerCommandSource> handler
    ) {
        return CommandManager.literal(operation)
                .then(CommandManager.argument("section", string())
                        .suggests(SyncmaticaCommand::suggestConfigSections)
                        .then(CommandManager.argument("key", string())
                                .suggests(SyncmaticaCommand::suggestConfigKeys)
                                .executes(handler)));
    }

    private static CompletableFuture<Suggestions> suggestConfigSections(
            final CommandContext<ServerCommandSource> context,
            final SuggestionsBuilder builder
    ) {
        final ConfigRegistry registry = configRegistry();
        if (registry != null) {
            registry.sections().forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestConfigKeys(
            final CommandContext<ServerCommandSource> context,
            final SuggestionsBuilder builder
    ) {
        final ConfigRegistry registry = configRegistry();
        if (registry != null) {
            registry.keys(context.getArgument("section", String.class)).forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestConfigValues(
            final CommandContext<ServerCommandSource> context,
            final SuggestionsBuilder builder
    ) {
        final ConfigRegistry registry = configRegistry();
        if (registry == null) {
            return builder.buildFuture();
        }
        final ConfigOption<?> option = registry.find(
                context.getArgument("section", String.class),
                context.getArgument("key", String.class)
        );
        if (option != null && option.getDefaultValue() instanceof Boolean) {
            builder.suggest("true");
            builder.suggest("false");
        }
        return builder.buildFuture();
    }

    private static ConfigRegistry configRegistry() {
        final Context context = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        return context == null ? null : context.getConfigRegistry();
    }

    private static ConfigCommandLogic configLogic() {
        final Context context = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (context == null || context.getConfigRegistry() == null || context.getConfigStore() == null) {
            throw new IllegalStateException("Cytra-Syncmatica server configuration unavailable");
        }
        return new ConfigCommandLogic(context.getConfigRegistry(), context.getConfigStore());
    }

    private static int handleConfigList(
            final CommandContext<ServerCommandSource> context,
            final String section
    ) {
        try {
            final List<String> entries = configLogic().list(section);
            entries.forEach(entry -> sendPrivateFeedback(context, entry));
            return entries.isEmpty() ? 0 : 1;
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            context.getSource().sendError(literal(exception.getMessage()));
            return 0;
        }
    }

    private static int handleConfigGet(final CommandContext<ServerCommandSource> context) {
        try {
            sendPrivateFeedback(context, configLogic().get(
                    context.getArgument("section", String.class),
                    context.getArgument("key", String.class)
            ));
            return 1;
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            context.getSource().sendError(literal(exception.getMessage()));
            return 0;
        }
    }

    private static int handleConfigSet(final CommandContext<ServerCommandSource> context) {
        try {
            sendPrivateFeedback(context, configLogic().set(
                    context.getArgument("section", String.class),
                    context.getArgument("key", String.class),
                    context.getArgument("value", String.class)
            ));
            return 1;
        } catch (final IOException | IllegalArgumentException | IllegalStateException exception) {
            context.getSource().sendError(literal("Failed to update configuration: " + exception.getMessage()));
            return 0;
        }
    }

    private static int handleConfigReset(final CommandContext<ServerCommandSource> context) {
        try {
            sendPrivateFeedback(context, configLogic().reset(
                    context.getArgument("section", String.class),
                    context.getArgument("key", String.class)
            ));
            return 1;
        } catch (final IOException | IllegalArgumentException | IllegalStateException exception) {
            context.getSource().sendError(literal("Failed to reset configuration: " + exception.getMessage()));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> loadArgument() {
        return CommandManager.literal("load")
                .requires(SyncmaticaCommand::hasLoadPermission)
                .executes(SyncmaticaCommand::handleLoadAll)
                .then(CommandManager.argument("file", string())
                        .suggests(SyncmaticaCommand::suggestOrphanFiles)
                        .executes(SyncmaticaCommand::handleLoadSingle));
    }

    private static CompletableFuture<Suggestions> suggestOrphanFiles(final CommandContext<ServerCommandSource> context,
                                                                     final SuggestionsBuilder builder) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext != null) {
            for (final File file : listOrphanCandidates(syncmaticaContext)) {
                final String base = removeLitematicExtension(file.getName());
                final SchematicPeek peek = peekCached(file);
                if (peek != null && peek.hasName()) {
                    builder.suggest(base, new LiteralMessage(peek.getName()));
                } else {
                    builder.suggest(base);
                }
            }
        }
        return builder.buildFuture();
    }

    private static int handleLoadAll(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null) {
            context.getSource().sendError(literal("Cytra-Syncmatica server context unavailable"));
            return 0;
        }
        final List<File> candidates = listOrphanCandidates(syncmaticaContext);
        if (candidates.isEmpty()) {
            sendFeedback(context, "No syncmatic file(s) found that need to be loaded");
            return 0;
        }
        int loaded = 0;
        for (final File file : candidates) {
            if (loadOrphanFile(context, syncmaticaContext, file)) {
                loaded++;
            }
        }
        sendFeedback(context, loaded + " syncmatic file(s) loaded");
        return loaded > 0 ? 1 : 0;
    }

    private static int handleLoadSingle(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null) {
            context.getSource().sendError(literal("Cytra-Syncmatica server context unavailable"));
            return 0;
        }
        String name = context.getArgument("file", String.class);
        if (name.endsWith(LITEMATIC_EXTENSION)) {
            name = removeLitematicExtension(name);
        }
        if (name.isEmpty() || !SyncmaticaUtil.sanitizeFileName(name).equals(name)) {
            context.getSource().sendError(literal("Invalid syncmatic file name: " + name));
            return 0;
        }
        final File file = new File(syncmaticaContext.getLitematicFolder(), name + LITEMATIC_EXTENSION);
        if (!file.isFile()) {
            context.getSource().sendError(literal("Syncmatic file not found: " + name + LITEMATIC_EXTENSION));
            return 0;
        }
        return loadOrphanFile(context, syncmaticaContext, file) ? 1 : 0;
    }

    /**
     * Registers a litematic file from the server folder as a shared placement.
     * The file is stored under its content hash so later downloads resolve;
     * files that already back a registered placement are skipped.
     */
    private static boolean loadOrphanFile(final CommandContext<ServerCommandSource> context,
                                          final Context syncmaticaContext,
                                          final File file) {
        final UUID hash;
        try (FileInputStream input = new FileInputStream(file)) {
            hash = SyncmaticaUtil.createChecksum(input);
        } catch (final Exception exception) {
            context.getSource().sendError(literal("Failed to read syncmatic file: " + file.getName()));
            return false;
        }
        if (hasPlacementHash(syncmaticaContext, hash)) {
            sendFeedback(context, "Skipping " + file.getName() + ": already registered");
            return false;
        }

        final File storedFile = new File(syncmaticaContext.getLitematicFolder(), hash + LITEMATIC_EXTENSION);
        if (!file.equals(storedFile) && !storedFile.exists()) {
            try {
                Files.move(file.toPath(), storedFile.toPath());
            } catch (final Exception exception) {
                context.getSource().sendError(literal("Failed to store syncmatic file: " + file.getName()));
                return false;
            }
        }

        final ServerCommandSource source = context.getSource();
        final Entity entity = source.getEntity();
        final ServerPlayerEntity player = entity instanceof ServerPlayerEntity ? (ServerPlayerEntity) entity : null;
        final PlayerIdentifier owner = player != null
                ? syncmaticaContext.getPlayerIdentifierProvider().createOrGet(player.getGameProfile())
                : PlayerIdentifier.MISSING_PLAYER;

        final ServerPlacement placement = new ServerPlacement(
                UUID.randomUUID(),
                removeLitematicExtension(file.getName()),
                hash,
                owner
        );
        final String dimension = source.getWorld().getRegistryKey().getValue().toString();
        final BlockPos position = player != null ? player.getBlockPos() : BlockPos.ORIGIN;
        placement.move(dimension, position, BlockRotation.NONE, BlockMirror.NONE);

        final ServerCommunicationManager comms =
                (ServerCommunicationManager) syncmaticaContext.getCommunicationManager();
        if (!comms.registerNewPlacement(placement)) {
            context.getSource().sendError(literal("Failed to register placement for " + file.getName()));
            return false;
        }
        sendFeedback(context, "Loaded server placement '" + placement.getName() + "'");
        return true;
    }

    private static List<File> listOrphanCandidates(final Context syncmaticaContext) {
        final File[] files = syncmaticaContext.getLitematicFolder()
                .listFiles((dir, name) -> name.endsWith(LITEMATIC_EXTENSION));
        if (files == null) {
            return Collections.emptyList();
        }
        final List<File> candidates = new ArrayList<>();
        for (final File file : files) {
            if (file.isFile() && !isRegisteredByName(syncmaticaContext, file)) {
                candidates.add(file);
            }
        }
        return candidates;
    }

    private static boolean isRegisteredByName(final Context syncmaticaContext, final File file) {
        try {
            final UUID hash = UUID.fromString(removeLitematicExtension(file.getName()));
            return hasPlacementHash(syncmaticaContext, hash);
        } catch (final IllegalArgumentException notAHashName) {
            return false;
        }
    }

    private static boolean hasPlacementHash(final Context syncmaticaContext, final UUID hash) {
        return syncmaticaContext.getSyncmaticManager().getAll().stream()
                .anyMatch(placement -> hash.equals(placement.getHash()));
    }

    private static String removeLitematicExtension(final String fileName) {
        return fileName.endsWith(LITEMATIC_EXTENSION)
                ? fileName.substring(0, fileName.length() - LITEMATIC_EXTENSION.length())
                : fileName;
    }

    private static SchematicPeek peekCached(final File file) {
        final String key = file.getAbsolutePath();
        final CachedPeek cached = PEEK_CACHE.get(key);
        if (cached != null && cached.lastModified == file.lastModified() && cached.length == file.length()) {
            return cached.peek;
        }
        final SchematicPeek peek = SchematicPeeker.peek(file);
        PEEK_CACHE.put(key, new CachedPeek(file.lastModified(), file.length(), peek));
        if (PEEK_CACHE.size() > 1024) {
            PEEK_CACHE.clear();
        }
        return peek;
    }

    private static void sendFeedback(final CommandContext<ServerCommandSource> context, final String message) {
        context.getSource().sendFeedback(() -> literal(message), true);
    }

    private static void sendPrivateFeedback(
            final CommandContext<ServerCommandSource> context,
            final String message
    ) {
        context.getSource().sendFeedback(() -> literal(message), false);
    }

    private static final class CachedPeek {
        private final long lastModified;
        private final long length;
        private final SchematicPeek peek;

        private CachedPeek(final long lastModified, final long length, final SchematicPeek peek) {
            this.lastModified = lastModified;
            this.length = length;
            this.peek = peek;
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> rescanArgument() {
        return CommandManager.literal("rescan")
                .requires(SyncmaticaCommand::hasCommandPermission)
                .then(CommandManager.argument("schematic", greedyString())
                        .suggests(SyncmaticaCommand::suggestPlacementNames)
                        .executes(SyncmaticaCommand::handleRescanBuild));
    }

    /**
     * Build progress is counted per chunk column and kept, on the grounds that a
     * block cannot change while its chunk is unloaded. Editing the world outside
     * the game breaks that assumption, and this throws the counts away so they
     * are taken again from what is actually there.
     */
    private static int handleRescanBuild(final CommandContext<ServerCommandSource> context) {
        final Context syncmaticaContext = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);
        if (syncmaticaContext == null || syncmaticaContext.getBuildService() == null) {
            context.getSource().sendError(literal("Cytra-Syncmatica build service unavailable"));
            return 0;
        }
        final String projectName = context.getArgument("schematic", String.class);
        final Optional<ServerPlacement> placement = findPlacementByName(syncmaticaContext, projectName);
        if (!placement.isPresent()) {
            context.getSource().sendError(literal("Unknown shared schematic: " + projectName));
            return 0;
        }
        if (!syncmaticaContext.getBuildService().rescan(placement.get())) {
            context.getSource().sendError(literal("Build completion tracking is disabled"));
            return 0;
        }
        sendFeedback(context, "Build progress of '" + projectName + "' will be measured again");
        return 1;
    }

    private static boolean hasCommandPermission(final ServerCommandSource source) {
        return Permissions.check(
                source,
                PlacementAccessPolicy.COMMAND_PERMISSION,
                PlacementAccessPolicy.COMMAND_PERMISSION_LEVEL
        );
    }

    private static boolean hasLoadPermission(final ServerCommandSource source) {
        return hasCommandPermission(source)
                && Permissions.check(source, LOAD_PERMISSION, PlacementAccessPolicy.COMMAND_PERMISSION_LEVEL);
    }

    private static boolean hasConfigPermission(final ServerCommandSource source) {
        return Permissions.check(source, CONFIG_PERMISSION, PlacementAccessPolicy.COMMAND_PERMISSION_LEVEL);
    }

    private static net.minecraft.text.Text literal(final String message) {
        return Text.literal(message);
    }
}
