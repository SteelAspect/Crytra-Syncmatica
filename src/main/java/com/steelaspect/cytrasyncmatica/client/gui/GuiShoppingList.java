package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.Syncmatica;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialList;
import com.steelaspect.cytrasyncmatica.materials.ShoppingList;
import com.steelaspect.cytrasyncmatica.service.MaterialTrackingService;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The shopping list: what is still missing, grouped, optionally minus what you carry; copy or export. */
public class GuiShoppingList extends GuiListBase<ShoppingList.Line, GuiShoppingList.LineWidget, GuiShoppingList.LineList> {
    private static final int TOP_BAR_Y = 20;
    private static final int LIST_TOP = 60;

    private final TrackedSchematic schematic;
    private boolean subtractInventory = true;
    private String group;

    public GuiShoppingList(final TrackedSchematic schematic) {
        super(12, LIST_TOP);
        this.schematic = schematic;
        title = StringUtils.translate("cytra-syncmatica.gui.title.shopping_list") + (schematic == null ? "" : ": " + schematic.name());
    }

    List<ShoppingList.Line> lines() {
        final MaterialList list = MaterialTrackerClient.getInstance().getList(schematic);
        if (list == null) {
            return List.of();
        }
        Map<String, Integer> inventory = null;
        if (subtractInventory) {
            inventory = new HashMap<>();
            for (final MaterialEntry e : list.getEntries()) {
                inventory.put(e.getItemId(), MaterialTrackerClient.getInstance().inInventory(e.getItemId()));
            }
        }
        return ShoppingList.build(list, inventory, MaterialTrackingService::stackSizeOf, group);
    }

    private String fullText() {
        return ShoppingList.toText(schematic == null ? "?" : schematic.name() + (group == null ? "" : " (" + group + ")"), lines());
    }

    @Override
    public void initGui() {
        super.initGui();
        int x = 10;
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.copy"), (b, m) -> {
            MinecraftClient.getInstance().keyboard.setClipboard(fullText());
            addMessage(Message.MessageType.SUCCESS, "cytra-syncmatica.gui.message.copied");
        });
        x = addTopButton(x, subtractLabel(), (b, m) -> {
            subtractInventory = !subtractInventory;
            b.setDisplayString(subtractLabel());
            getListWidget().refreshEntries();
        });
        x = addTopButton(x, groupLabel(), (b, m) -> {
            cycleGroup();
            b.setDisplayString(groupLabel());
            getListWidget().refreshEntries();
        });
        x = addTopButton(x, StringUtils.translate("cytra-syncmatica.gui.button.export"), (b, m) -> export());
        final String back = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int backWidth = getStringWidth(back) + 20;
        addButton(new ButtonGeneric(width - backWidth - 10, TOP_BAR_Y, backWidth, 20, back), (b, m) -> closeGui(true));
    }

    private int addTopButton(final int x, final String label, final fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        final int w = getStringWidth(label) + 14;
        addButton(new ButtonGeneric(x, TOP_BAR_Y, w, 20, label), listener);
        return x + w + 4;
    }

    private String subtractLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.subtract_inventory",
                StringUtils.translate(subtractInventory ? "cytra-syncmatica.gui.label.toggle_on" : "cytra-syncmatica.gui.label.toggle_off"));
    }

    private String groupLabel() {
        return StringUtils.translate("cytra-syncmatica.gui.button.group",
                group == null ? StringUtils.translate("cytra-syncmatica.gui.label.group.all") : group);
    }

    private void cycleGroup() {
        final MaterialList list = MaterialTrackerClient.getInstance().getList(schematic);
        final List<String> names = new ArrayList<>();
        if (list != null) {
            for (final MaterialList.GroupTotals g : list.groups()) {
                names.add(g.name());
            }
        }
        final int i = group == null ? -1 : names.indexOf(group);
        group = names.isEmpty() || i + 1 >= names.size() ? null : names.get(i + 1);
    }

    private void export() {
        if (schematic == null) {
            return;
        }
        final Path folder = Path.of("config", Syncmatica.MOD_ID, "exports");
        final Path file = folder.resolve(MaterialTrackingService.safeFileName(schematic.name()) + "-shopping.txt");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, fullText(), StandardCharsets.UTF_8);
            addMessage(Message.MessageType.SUCCESS, "cytra-syncmatica.gui.message.exported", file.toString());
        } catch (final IOException e) {
            addMessage(Message.MessageType.ERROR, "cytra-syncmatica.gui.message.export_failed", e.getMessage());
        }
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);
        final List<ShoppingList.Line> lines = lines();
        final String status = lines.isEmpty()
                ? StringUtils.translate("cytra-syncmatica.gui.label.nothing_to_gather")
                : StringUtils.translate("cytra-syncmatica.gui.label.shopping_summary", ShoppingList.totalItems(lines), lines.size(), ShoppingList.totalShulkers(lines));
        drawStringWithShadow(guiContext, status, 10, 46, 0xFFC0C0C0);
    }

    @Override
    protected LineList createListWidget(final int listX, final int listY) {
        return new LineList(listX, listY, getBrowserWidth(), getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserHeight() {
        return height - LIST_TOP - 10;
    }

    @Override
    protected int getBrowserWidth() {
        return width - 20;
    }

    public static class LineList extends WidgetListBase<ShoppingList.Line, LineWidget> {
        private final GuiShoppingList parent;

        LineList(final int x, final int y, final int width, final int height, final GuiShoppingList parent) {
            super(x, y, width, height, null);
            this.parent = parent;
            browserEntryHeight = 18;
            browserEntriesOffsetY = 2;
            browserEntryWidth = width - 8;
        }

        @Override
        protected Collection<ShoppingList.Line> getAllEntries() {
            return parent.lines();
        }

        @Override
        protected LineWidget createListEntryWidget(final int x, final int y, final int listIndex, final boolean isOdd, final ShoppingList.Line entry) {
            return new LineWidget(x, y, browserEntryWidth, browserEntryHeight, entry, listIndex);
        }

        @Override
        protected List<String> getEntryStringsForFilter(final ShoppingList.Line entry) {
            return List.of(entry.itemId().toLowerCase(), entry.group().toLowerCase(), ShoppingList.prettyName(entry.itemId()).toLowerCase());
        }
    }

    public static class LineWidget extends WidgetListEntryBase<ShoppingList.Line> {
        private final ShoppingList.Line line;
        private final boolean odd;
        private final ItemStack stack;

        LineWidget(final int x, final int y, final int width, final int height, final ShoppingList.Line line, final int listIndex) {
            super(x, y, width, height, line, listIndex);
            this.line = line;
            odd = listIndex % 2 == 1;
            stack = WidgetMaterialTrackerEntry.stackOf(line.itemId());
        }

        @Override
        public void render(final GuiContext guiContext, final int mouseX, final int mouseY, final boolean selected) {
            RenderUtils.drawRect(guiContext, x, y, width, height, odd ? 0x20FFFFFF : 0x40FFFFFF);
            if (!stack.isEmpty()) {
                guiContext.drawItem(stack, x + 2, y + 1);
            }
            drawString(guiContext, x + 22, y + 5, 0xFFFFFFFF, WidgetMaterialTrackerEntry.displayName(line.itemId()));
            drawString(guiContext, x + width / 2, y + 5, 0xFFA0A0A0, line.group());
            final String text = line.text() + "  (" + line.remaining() + ")";
            drawString(guiContext, x + width - 4 - getStringWidth(text), y + 5, 0xFFFFC080, text);
        }
    }
}
