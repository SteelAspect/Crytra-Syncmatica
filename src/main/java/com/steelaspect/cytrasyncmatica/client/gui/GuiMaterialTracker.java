package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerPreferences;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.service.MaterialTrackingService;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The in-game material list: one row per item with the shared counts and edit buttons. */
public class GuiMaterialTracker extends GuiListBase<MaterialEntry, WidgetMaterialTrackerEntry, WidgetListMaterialTracker> {
    private static final int TOP_BAR_Y = 20;
    private static final int LIST_TOP = 66;

    private TrackedSchematic schematic;
    /** Group filter; null shows every group. */
    private String group;
    private final Runnable refreshListener = this::onTrackerChanged;

    public GuiMaterialTracker(final TrackedSchematic schematic) {
        super(12, LIST_TOP);
        this.schematic = schematic != null ? schematic : MaterialTrackerClient.getInstance().defaultSchematic();
        if (this.schematic != null) {
            MaterialTrackerPreferences.setLastSchematicKey(this.schematic.key());
        }
        updateTitle();
    }

    public TrackedSchematic getSchematic() {
        return schematic;
    }

    public String getGroup() {
        return group;
    }

    private List<String> groupNames() {
        final MaterialList list = MaterialTrackerClient.getInstance().getList(schematic);
        final List<String> names = new java.util.ArrayList<>();
        if (list != null) {
            for (final MaterialList.GroupTotals g : list.groups()) {
                names.add(g.name());
            }
        }
        return names;
    }

    private void cycleGroup() {
        final List<String> names = groupNames();
        if (names.isEmpty()) {
            group = null;
            return;
        }
        final int i = group == null ? -1 : names.indexOf(group);
        group = i + 1 >= names.size() ? null : names.get(i + 1);
    }

    private String groupLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.group",
                group == null ? StringUtils.translate("cytra-syncmatica.gui.label.group.all") : group);
    }

    private void updateTitle() {
        title = StringUtils.translate("cytra-syncmatica.gui.title.material_tracker")
                + (schematic == null ? "" : ": " + schematic.name());
    }

    @Override
    public void initGui() {
        super.initGui();
        MaterialTrackerClient.getInstance().addListener(refreshListener);
        int x = 10;
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.schematic_select"), (b, m) -> openSchematicSelect());
        x = addTopButton(x, sortLabel(), (b, m) -> {
            MaterialTrackerPreferences.cycleSortMode();
            b.setDisplayString(sortLabel());
            getListWidget().refreshEntries();
        });
        x = addTopButton(x, groupLabel(), (b, m) -> {
            cycleGroup();
            b.setDisplayString(groupLabel());
            getListWidget().refreshEntries();
        });
        x = addTopButton(x, hideLabel(), (b, m) -> {
            MaterialTrackerPreferences.toggleHideCompleted();
            b.setDisplayString(hideLabel());
            getListWidget().refreshEntries();
        });
        x = addTopButton(x, autoCountLabel(), (b, m) -> {
            MaterialTrackerPreferences.toggleAutoCount();
            b.setDisplayString(autoCountLabel());
        });
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.add_from_inventory"), (b, m) -> {
            final int n = MaterialTrackerClient.getInstance().addFromInventory(schematic);
            addMessage(Message.MessageType.INFO, "cytra-syncmatica.gui.message.added_from_inventory", n);
        });
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.export"), (b, m) -> exportLocal());
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.refresh"), (b, m) -> {
            MaterialTrackerClient.getInstance().refresh(schematic);
            getListWidget().refreshEntries();
        });
        final String back = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int backWidth = getStringWidth(back) + 20;
        addButton(new ButtonGeneric(width - backWidth - 10, TOP_BAR_Y, backWidth, 20, back), (b, m) -> closeGui(true));
    }

    private int addTopButton(final int x, final String label, final fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        final int w = getStringWidth(label) + 14;
        addButton(new ButtonGeneric(x, TOP_BAR_Y, w, 20, label), listener);
        return x + w + 4;
    }

    private String sortLabel() {
        final String mode = MaterialTrackerPreferences.getSortMode() == MaterialTrackerPreferences.SortMode.NAME
                ? StringUtils.translate("cytra-syncmatica.gui.label.sort.name")
                : StringUtils.translate("cytra-syncmatica.gui.label.sort.remaining");
        return StringUtils.translate("cytra-syncmatica.gui.button.sort", mode);
    }

    private String hideLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.hide_completed",
                StringUtils.translate(MaterialTrackerPreferences.isHideCompleted() ? "cytra-syncmatica.gui.label.toggle_on" : "cytra-syncmatica.gui.label.toggle_off"));
    }

    private String autoCountLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.auto_count",
                StringUtils.translate(MaterialTrackerPreferences.isAutoCount() ? "cytra-syncmatica.gui.label.toggle_on" : "cytra-syncmatica.gui.label.toggle_off"));
    }

    private void openSchematicSelect() {
        final GuiSchematicSelect gui = new GuiSchematicSelect(selected -> {
            schematic = selected;
            group = null;
            MaterialTrackerPreferences.setLastSchematicKey(selected.key());
            updateTitle();
        });
        gui.setParent(this);
        openGui(gui);
    }

    /** Export from what the client sees; works in every mode. Written to the client's exports folder. */
    private void exportLocal() {
        final MaterialList list = MaterialTrackerClient.getInstance().getList(schematic);
        if (list == null || schematic == null) {
            addMessage(Message.MessageType.ERROR, "cytra-syncmatica.gui.message.no_list");
            return;
        }
        final List<MaterialEntry> entries = list.copyEntries();
        final Map<String, Integer> stacks = new HashMap<>();
        for (final MaterialEntry e : entries) {
            stacks.put(e.getItemId(), MaterialTrackingService.stackSizeOf(e.getItemId()));
        }
        final Path folder = Path.of("config", com.steelaspect.cytrasyncmatica.Syncmatica.MOD_ID, "exports");
        final String base = MaterialTrackingService.safeFileName(schematic.name());
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(base + ".csv"), MaterialTrackingService.toCsv(entries), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(base + ".txt"), MaterialTrackingService.toText(schematic.name(), entries, stacks), StandardCharsets.UTF_8);
            addMessage(Message.MessageType.SUCCESS, "cytra-syncmatica.gui.message.exported", folder.resolve(base + ".txt").toString());
        } catch (final IOException e) {
            addMessage(Message.MessageType.ERROR, "cytra-syncmatica.gui.message.export_failed", e.getMessage());
        }
    }

    private void onTrackerChanged() {
        if (getListWidget() != null) {
            getListWidget().refreshEntries();
        }
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);
        final MaterialTrackerClient tracker = MaterialTrackerClient.getInstance();
        String status = tracker.describeMode();
        final MaterialList list = tracker.getList(schematic);
        if (schematic == null) {
            status += " · " + StringUtils.translate("cytra-syncmatica.gui.label.no_schematics");
        } else if (list == null) {
            status += " · " + StringUtils.translate(tracker.isCounting(schematic)
                    ? "cytra-syncmatica.gui.label.counting" : "cytra-syncmatica.gui.label.loading");
        } else {
            final String error = tracker.getError(schematic);
            if (error != null) {
                status += " · " + error;
            } else {
                status += " · " + String.format(java.util.Locale.ROOT, "%.1f%%", list.percentComplete()) + " · "
                        + StringUtils.translate("cytra-syncmatica.gui.label.remaining_total", list.totalRemaining());
                final MaterialList.GroupTotals g = group == null ? null : list.group(group);
                if (g != null) {
                    status += " · " + g.name() + " " + String.format(java.util.Locale.ROOT, "%.0f%%", g.percent())
                            + " (" + g.gathered() + "/" + g.required() + ")";
                }
            }
        }
        drawStringWithShadow(guiContext, status, 10, 46, 0xFFC0C0C0);
    }

    @Override
    public void removed() {
        MaterialTrackerClient.getInstance().removeListener(refreshListener);
        super.removed();
    }

    @Override
    protected WidgetListMaterialTracker createListWidget(final int listX, final int listY) {
        return new WidgetListMaterialTracker(listX, listY, getBrowserWidth(), getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserHeight() {
        return height - LIST_TOP - 10;
    }

    @Override
    protected int getBrowserWidth() {
        return width - 20;
    }
}
