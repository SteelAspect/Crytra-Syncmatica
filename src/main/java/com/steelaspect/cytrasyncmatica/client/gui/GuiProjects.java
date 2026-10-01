package com.steelaspect.cytrasyncmatica.client.gui;

import com.steelaspect.cytrasyncmatica.client.materials.ClientProjects;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialMode;
import com.steelaspect.cytrasyncmatica.client.materials.MaterialTrackerClient;
import com.steelaspect.cytrasyncmatica.client.materials.TrackedSchematic;
import com.steelaspect.cytrasyncmatica.materials.CombinedList;
import com.steelaspect.cytrasyncmatica.projects.Project;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Projects: one row each with Open / Add… / Remove… / Delete, and a "New project…" button. */
public class GuiProjects extends GuiBase {
    private static final int ROW_Y = 50;
    private static final int ROW_H = 22;

    private final Consumer<TrackedSchematic> onOpen;
    private final Runnable refreshListener = this::reinit;

    public GuiProjects(final Consumer<TrackedSchematic> onOpen) {
        this.onOpen = onOpen;
        title = StringUtils.translate("cytra-syncmatica.gui.title.projects");
    }

    private void reinit() {
        if (mc != null && mc.currentScreen == this) {
            initGui();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        clearButtons();
        MaterialTrackerClient.getInstance().removeListener(refreshListener);
        MaterialTrackerClient.getInstance().addListener(refreshListener);
        final String newLabel = StringUtils.translate("cytra-syncmatica.gui.button.new_project");
        addButton(new ButtonGeneric(10, 22, getStringWidth(newLabel) + 14, 20, newLabel), (b, m) -> {
            final GuiTextInput input = new GuiTextInput(Project.MAX_NAME_LENGTH, "cytra-syncmatica.gui.title.new_project", "", this,
                    (String name) -> {
                        final String error = ClientProjects.getInstance().create(name);
                        if (error != null) {
                            addMessage(Message.MessageType.ERROR, error, "");
                            return false;
                        }
                        return true;
                    });
            openGui(input);
        });
        final String back = StringUtils.translate("cytra-syncmatica.gui.button.back");
        final int bw = getStringWidth(back) + 20;
        addButton(new ButtonGeneric(width - bw - 10, 22, bw, 20, back), (b, m) -> closeGui(true));

        int y = ROW_Y;
        for (final Project p : ClientProjects.getInstance().all()) {
            if (y > height - 30) {
                break;
            }
            int x = width - 10;
            x = addRight(x, y, StringUtils.translate("cytra-syncmatica.gui.button.delete"), (b, m) -> ClientProjects.getInstance().delete(p));
            x = addRight(x, y, StringUtils.translate("cytra-syncmatica.gui.button.remove_schematic"), (b, m) -> pick(p, false));
            x = addRight(x, y, StringUtils.translate("cytra-syncmatica.gui.button.add_schematic"), (b, m) -> pick(p, true));
            addRight(x, y, StringUtils.translate("cytra-syncmatica.gui.button.open"), (b, m) -> {
                onOpen.accept(TrackedSchematic.project(p));
                closeGui(true);
            });
            y += ROW_H;
        }
    }

    private int addRight(final int rightX, final int y, final String label, final fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        final int w = getStringWidth(label) + 12;
        addButton(new ButtonGeneric(rightX - w, y, w, 18, label), listener);
        return rightX - w - 3;
    }

    private void pick(final Project p, final boolean add) {
        final MaterialTrackerClient tracker = MaterialTrackerClient.getInstance();
        final List<TrackedSchematic> candidates = new ArrayList<>();
        if (add) {
            for (final TrackedSchematic t : tracker.schematicsOnly()) {
                if (!p.hasMember(t.key())) {
                    candidates.add(t);
                }
            }
        } else {
            candidates.addAll(tracker.members(p));
        }
        final GuiSchematicSelect picker = new GuiSchematicSelect(candidates,
                StringUtils.translate(add ? "cytra-syncmatica.gui.title.pick_schematic_add" : "cytra-syncmatica.gui.title.pick_schematic_remove", p.getName()),
                chosen -> {
                    if (add) {
                        ClientProjects.getInstance().addMember(p, chosen);
                    } else {
                        ClientProjects.getInstance().removeMember(p, chosen);
                    }
                });
        picker.setParent(this);
        openGui(picker);
    }

    @Override
    public void drawContents(final GuiContext guiContext, final int mouseX, final int mouseY, final float partialTicks) {
        final MaterialTrackerClient tracker = MaterialTrackerClient.getInstance();
        final List<Project> all = ClientProjects.getInstance().all();
        if (all.isEmpty()) {
            drawStringWithShadow(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.no_projects"), 10, ROW_Y + 5, 0xFFC0C0C0);
        }
        int y = ROW_Y;
        for (final Project p : all) {
            if (y > height - 30) {
                break;
            }
            final CombinedList.Combined c = tracker.combined(p);
            final String progress = c.list().isEmpty() ? "" : String.format(java.util.Locale.ROOT, " · %.1f%%", c.list().percentComplete());
            drawStringWithShadow(guiContext, p.getName() + " · " + StringUtils.translate("cytra-syncmatica.gui.label.project_members",
                    tracker.members(p).size()) + progress, 10, y + 5, 0xFFFFFFFF);
            y += ROW_H;
        }
        if (tracker.currentMode() == MaterialMode.CONNECTED) {
            drawStringWithShadow(guiContext, StringUtils.translate("cytra-syncmatica.gui.label.projects_need_permission"), 10, height - 16, 0xFF808080);
        }
    }

    @Override
    public void removed() {
        MaterialTrackerClient.getInstance().removeListener(refreshListener);
        super.removed();
    }
}
