package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerPreferences;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import com.steelaspect.cytrasyncmatica.materials.StackFormat;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.text.DateFormat;
import java.util.Date;

/** Two lines per item: counts on the first, "last edited by" and the buttons on the second. */
public class WidgetMaterialTrackerEntry extends WidgetListEntryBase<MaterialEntry> {
    public static final int HEIGHT = 34;
    public static final int NAME_X = 24;
    public static final int REQUIRED_RIGHT = 230;
    public static final int GATHERED_RIGHT = 170;
    public static final int INVENTORY_RIGHT = 110;
    public static final int REMAINING_RIGHT = 4;

    private final MaterialEntry entry;
    private final TrackedSchematic schematic;
    private final boolean odd;
    private final ItemStack stack;

    public WidgetMaterialTrackerEntry(final int x, final int y, final int width, final int height,
                                      final MaterialEntry entry, final int listIndex, final TrackedSchematic schematic) {
        super(x, y, width, height, entry, listIndex);
        this.entry = entry;
        this.schematic = schematic;
        odd = listIndex % 2 == 1;
        stack = stackOf(entry.getItemId());

        int bx = x + width - 4;
        bx = addRight(bx, y + 16, pinLabel(), (b, m) -> {
            MaterialTrackerPreferences.togglePinned(schematic.key(), entry.getItemId());
            b.setDisplayString(pinLabel());
        });
        bx = addRight(bx, y + 16, StringUtils.translate("cytra-syncmatica.gui.button.reset"), (b, m) -> edit(MaterialOp.RESET, 0));
        bx = addRight(bx, y + 16, StringUtils.translate("cytra-syncmatica.gui.button.done"), (b, m) -> edit(MaterialOp.DONE, 0));
        bx = addRight(bx, y + 16, StringUtils.translate("cytra-syncmatica.gui.button.set"), (b, m) -> {
            final GuiMaterialSetCount gui = new GuiMaterialSetCount(schematic, entry, displayName(entry.getItemId()));
            gui.setParent(mc.currentScreen);
            GuiBase.openGui(gui);
        });
        bx = addRight(bx, y + 16, "+64", (b, m) -> edit(MaterialOp.ADD, 64));
        bx = addRight(bx, y + 16, "+16", (b, m) -> edit(MaterialOp.ADD, 16));
        addRight(bx, y + 16, "+1", (b, m) -> edit(MaterialOp.ADD, 1));
    }

    private int addRight(final int rightX, final int y, final String label,
                         final fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        final int w = getStringWidth(label) + 10;
        addButton(new ButtonGeneric(rightX - w, y, w, 16, label), listener);
        return rightX - w - 2;
    }

    private String pinLabel() {
        return StringUtils.translate(MaterialTrackerPreferences.isPinned(schematic.key(), entry.getItemId())
                ? "cytra-syncmatica.gui.button.unpin" : "cytra-syncmatica.gui.button.pin");
    }

    private void edit(final MaterialOp op, final int amount) {
        MaterialTrackerClient.getInstance().edit(schematic, entry.getItemId(), op, amount);
    }

    public static ItemStack stackOf(final String itemId) {
        final Identifier id = Identifier.tryParse(itemId);
        final Item item = id == null ? Items.AIR : Registries.ITEM.getOptionalValue(id).orElse(Items.AIR);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static String displayName(final String itemId) {
        final ItemStack s = stackOf(itemId);
        return s.isEmpty() ? itemId : s.getName().getString();
    }

    @Override
    public void render(final GuiContext guiContext, final int mouseX, final int mouseY, final boolean selected) {
        final int bg = entry.isComplete() ? 0x3040C040 : odd ? 0x20FFFFFF : 0x40FFFFFF;
        RenderUtils.drawRect(guiContext, x, y, width, height, bg);
        if (isMouseOver(mouseX, mouseY)) {
            RenderUtils.drawRect(guiContext, x, y, width, height, 0x20FFFFFF);
        }
        if (!stack.isEmpty()) {
            guiContext.drawItem(stack, x + 3, y + 1);
        }
        final int line1 = y + 5;
        final int right = x + width - 4;
        drawString(guiContext, x + NAME_X, line1, 0xFFFFFFFF, displayName(entry.getItemId()));
        drawRight(guiContext, Integer.toString(entry.getRequired()), right - REQUIRED_RIGHT, line1, 0xFFFFFFFF);
        drawRight(guiContext, Integer.toString(entry.getGathered()), right - GATHERED_RIGHT, line1, entry.isComplete() ? 0xFF80FF80 : 0xFFFFFFFF);
        final int inv = MaterialTrackerClient.getInstance().inInventory(entry.getItemId());
        drawRight(guiContext, Integer.toString(inv), right - INVENTORY_RIGHT, line1, inv > 0 ? 0xFFA0D0FF : 0xFF909090);
        final int remaining = entry.getRemaining();
        final String remainingText = remaining == 0 ? "✓" : StackFormat.format(remaining, stack.isEmpty() ? 64 : stack.getMaxCount());
        drawRight(guiContext, remainingText, right - REMAINING_RIGHT, line1, remaining == 0 ? 0xFF80FF80 : 0xFFFFC080);

        final String editor = entry.getEditorName() == null || entry.getEditorName().isEmpty()
                ? StringUtils.translate("cytra-syncmatica.gui.label.edited_by_nobody")
                : StringUtils.translate("cytra-syncmatica.gui.label.edited_by", entry.getEditorName(),
                        entry.getEditedAt() > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.getEditedAt())) : "");
        drawString(guiContext, x + NAME_X, y + 21, 0xFFA0A0A0, editor);
        drawSubWidgets(guiContext, mouseX, mouseY);
    }

    private void drawRight(final GuiContext ctx, final String text, final int rightX, final int y, final int color) {
        drawString(ctx, rightX - getStringWidth(text), y, color, text);
    }
}
