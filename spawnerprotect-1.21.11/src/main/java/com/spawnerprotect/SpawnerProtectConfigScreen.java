package com.spawnerprotect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class SpawnerProtectConfigScreen extends Screen {

    private final Screen parent;
    private Button modeButton;
    private Button countLabel;
    private final List<Button> countButtons = new ArrayList<>();

    public SpawnerProtectConfigScreen(Screen parent) {
        super(Component.literal("Spawner Protect"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        countButtons.clear();
        int cx = this.width / 2;
        int y = this.height / 4 + 10;

        modeButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            SpawnerProtectConfig.autoDetect = !SpawnerProtectConfig.autoDetect;
            refresh();
        }).bounds(cx - 102, y, 204, 20).build());

        int ry = y + 50;
        countButtons.add(addCountButton("-10", cx - 102, ry, 36, -10));
        countButtons.add(addCountButton("-1", cx - 62, ry, 28, -1));
        countLabel = this.addRenderableWidget(Button.builder(Component.empty(), b -> { })
                .bounds(cx - 30, ry, 60, 20).build());
        countButtons.add(addCountButton("+1", cx + 34, ry, 28, 1));
        countButtons.add(addCountButton("+10", cx + 66, ry, 36, 10));

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - 102, y + 110, 204, 20).build());

        refresh();
    }

    private Button addCountButton(String label, int x, int y, int w, int delta) {
        return this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            SpawnerProtectConfig.breakCount = SpawnerProtectConfig.clamp(SpawnerProtectConfig.breakCount + delta);
            refresh();
        }).bounds(x, y, w, 20).build());
    }

    private void refresh() {
        boolean auto = SpawnerProtectConfig.autoDetect;
        modeButton.setMessage(Component.literal("Auto detect: " + (auto ? "ON" : "OFF (manual)")));
        countLabel.setMessage(Component.literal(String.valueOf(SpawnerProtectConfig.breakCount)));
        countLabel.active = !auto;
        for (Button b : countButtons) {
            b.active = !auto;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 4 + 10;
        graphics.drawCenteredString(this.font, this.title, cx, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal("Manual mode: how many times to break"),
                cx, y + 36, 0xFFAAAAAA);
        String hint = SpawnerProtectConfig.autoDetect
                ? "Auto: keeps breaking until no players are detected."
                : "Manual: breaks exactly this many times (4 stacked = 4), then logs off.";
        graphics.drawCenteredString(this.font, Component.literal(hint), cx, y + 82, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        SpawnerProtectConfig.save();
        this.minecraft.setScreen(parent);
    }
}
