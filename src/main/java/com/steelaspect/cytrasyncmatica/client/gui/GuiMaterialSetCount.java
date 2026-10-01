package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.MaterialEntry;
import com.steelaspect.cytrasyncmatica.materials.MaterialOp;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

/** "Set exact count" for one item. */
public class GuiMaterialSetCount extends GuiBase {
    private final TrackedSchematic schematic;
    private final MaterialEntry entry;
    private final String itemName;
    private GuiTextFieldInteger field;

    public GuiMaterialSetCount(final TrackedSchematic schematic, final MaterialEntry entry, final String itemName) {
        this.schematic = schematic;
        this.entry = entry;
        this.itemName = itemName;
        title = StringUtils.translate("cytra-syncmatica.gui.title.set_count", itemName);
    }

    @Override
    public void initGui() {
        super.initGui();
        final int x = width / 2 - 80;
        final int y = height / 2 - 20;
        field = new GuiTextFieldInteger(x, y, 160, 20, textRenderer);
        field.setTextWrapper(Integer.toString(entry.getGathered()));
        field.setFocusedWrapper(true);
        addTextField(field, null);
        final String ok = StringUtils.translate("cytra-syncmatica.gui.button.apply");
        final String cancel = StringUtils.translate("cytra-syncmatica.gui.button.cancel");
        final int okW = getStringWidth(ok) + 20;
        final int cancelW = getStringWidth(cancel) + 20;
        addButton(new ButtonGeneric(x, y + 26, okW, 20, ok), (b, m) -> apply());
        addButton(new ButtonGeneric(x + okW + 6, y + 26, cancelW, 20, cancel), (b, m) -> closeGui(true));
    }

    private void apply() {
        try {
            final int value = Integer.parseInt(field.getTextWrapper().trim());
            MaterialTrackerClient.getInstance().edit(schematic, entry.getItemId(), MaterialOp.SET, Math.max(0, value));
            closeGui(true);
        } catch (final NumberFormatException e) {
            addMessage(fi.dy.masa.malilib.gui.Message.MessageType.ERROR, "cytra-syncmatica.gui.message.not_a_number");
        }
    }

    @Override
    public boolean onKeyTyped(final net.minecraft.client.input.KeyInput key) {
        if (key.getKeycode() == 257 || key.getKeycode() == 335) { // enter / numpad enter
            apply();
            return true;
        }
        return super.onKeyTyped(key);
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        drawStringWithShadow(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.set_count_hint", itemName, entry.getRequired()),
                width / 2 - 80, height / 2 - 36, 0xFFFFFFFF);
    }
}
