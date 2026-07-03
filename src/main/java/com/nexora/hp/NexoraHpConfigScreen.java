package com.nexora.hp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NexoraHpConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 24;
    private static final int SECTION_GAP = 18;
    private static final int ACCENT_COLOR = 0xFF5CE6C7;

    private final Screen parent;

    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    private int panelTop;
    private int panelBottom;

    public NexoraHpConfigScreen(Screen parent) {
        super(Component.literal("Nexora-Wand"));
        this.parent = parent;
    }

    private record SectionHeader(String text, int y) {
    }

    @Override
    protected void init() {
        this.sectionHeaders.clear();

        int centerX = this.width / 2;
        int y = this.height / 2 - 165;
        this.panelTop = y - 40;
        y += 10; // keep the first section header clear of the title text above it

        y = section(centerX, y, "GENERAL");

        Button enabledButton = Button.builder(enabledLabel(), b -> {
            NexoraHpConfig.enabled = !NexoraHpConfig.enabled;
            b.setMessage(enabledLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(enabledButton);
        y += SPACING;

        Button thresholdButton = Button.builder(thresholdLabel(), b -> {
            NexoraHpConfig.healThresholdPercent += 5;
            if (NexoraHpConfig.healThresholdPercent > 95) {
                NexoraHpConfig.healThresholdPercent = 10;
            }
            b.setMessage(thresholdLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(thresholdButton);
        y += SPACING;

        Button slotButton = Button.builder(slotLabel(), b -> {
            NexoraHpConfig.hotbarSlot = NexoraHpConfig.hotbarSlot % 9 + 1;
            b.setMessage(slotLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(slotButton);
        y += SPACING;

        Button cooldownButton = Button.builder(cooldownLabel(), b -> {
            NexoraHpConfig.cooldownSeconds = NexoraHpConfig.cooldownSeconds % 60 + 1;
            b.setMessage(cooldownLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(cooldownButton);
        y += SPACING + SECTION_GAP;

        y = section(centerX, y, "PANIC HEAL");

        Button panicEnabledButton = Button.builder(panicEnabledLabel(), b -> {
            NexoraHpConfig.panicEnabled = !NexoraHpConfig.panicEnabled;
            b.setMessage(panicEnabledLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(panicEnabledButton);
        y += SPACING;

        Button panicThresholdButton = Button.builder(panicThresholdLabel(), b -> {
            NexoraHpConfig.panicThresholdPercent += 5;
            if (NexoraHpConfig.panicThresholdPercent > 90) {
                NexoraHpConfig.panicThresholdPercent = 5;
            }
            b.setMessage(panicThresholdLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(panicThresholdButton);
        y += SPACING;

        Button panicSlotButton = Button.builder(panicSlotLabel(), b -> {
            NexoraHpConfig.panicHotbarSlot = NexoraHpConfig.panicHotbarSlot % 9 + 1;
            b.setMessage(panicSlotLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(panicSlotButton);
        y += SPACING;

        Button panicCooldownButton = Button.builder(panicCooldownLabel(), b -> {
            NexoraHpConfig.panicCooldownSeconds = NexoraHpConfig.panicCooldownSeconds % 60 + 1;
            b.setMessage(panicCooldownLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(panicCooldownButton);
        y += SPACING + SECTION_GAP;

        y = section(centerX, y, "DISPLAY");

        Button soundButton = Button.builder(soundLabel(), b -> {
            NexoraHpConfig.soundEnabled = !NexoraHpConfig.soundEnabled;
            b.setMessage(soundLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(soundButton);
        y += SPACING;

        Button hudPositionButton = Button.builder(hudPositionLabel(), b -> {
            NexoraHpConfig.hudPosition = NexoraHpConfig.hudPosition.next();
            b.setMessage(hudPositionLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(hudPositionButton);
        y += SPACING + 12;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT;

        this.panelBottom = y + 22;
    }

    /** Draws a small caps section label with a divider line, and returns the y for the first widget in it. */
    private int section(int centerX, int y, String text) {
        this.sectionHeaders.add(new SectionHeader(text, y));
        return y + 14;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x99050507);

        int centerX = this.width / 2;
        int panelX1 = centerX - PANEL_WIDTH / 2;
        int panelX2 = centerX + PANEL_WIDTH / 2;
        int panelHeight = this.panelBottom - this.panelTop;

        // NOTE: outline() takes (x, y, width, height, color), not a second x/y corner.
        graphics.fillGradient(panelX1, this.panelTop, panelX2, this.panelBottom, 0xF0181822, 0xF00E0E16);
        graphics.outline(panelX1, this.panelTop, PANEL_WIDTH, panelHeight, 0x40FFFFFF);
        graphics.fill(panelX1 + 1, this.panelTop + 1, panelX2 - 1, this.panelTop + 3, ACCENT_COLOR);

        graphics.centeredText(this.font, this.title, centerX, this.panelTop + 10, 0xFFFFFFFF);

        for (SectionHeader header : this.sectionHeaders) {
            int textY = header.y() - 12;
            graphics.text(this.font, header.text(), panelX1 + 20, textY, ACCENT_COLOR);
            int lineX1 = panelX1 + 20 + this.font.width(header.text()) + 6;
            graphics.horizontalLine(lineX1, panelX2 - 20, textY + this.font.lineHeight / 2, 0x40FFFFFF);
        }

        graphics.centeredText(this.font, "Nexora-Wand • v1.0.0", centerX, this.panelBottom - 12, 0xFF55555F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        NexoraHpConfig.save();
        this.minecraft.setScreen(parent);
    }

    private static Component enabledLabel() {
        return Component.literal("Auto-Heal: " + (NexoraHpConfig.enabled ? "ON" : "OFF"));
    }

    private static Component thresholdLabel() {
        return Component.literal("Heal Below: " + NexoraHpConfig.healThresholdPercent + "%");
    }

    private static Component slotLabel() {
        return Component.literal("Heal Item Slot: " + NexoraHpConfig.hotbarSlot);
    }

    private static Component cooldownLabel() {
        return Component.literal("Cooldown: " + NexoraHpConfig.cooldownSeconds + "s");
    }

    private static Component soundLabel() {
        return Component.literal("Heal Sound: " + (NexoraHpConfig.soundEnabled ? "ON" : "OFF"));
    }

    private static Component hudPositionLabel() {
        return Component.literal("HUD Position: " + NexoraHpConfig.hudPosition.name().replace('_', ' '));
    }

    private static Component panicEnabledLabel() {
        return Component.literal("Panic Heal: " + (NexoraHpConfig.panicEnabled ? "ON" : "OFF"));
    }

    private static Component panicThresholdLabel() {
        return Component.literal("Panic Below: " + NexoraHpConfig.panicThresholdPercent + "%");
    }

    private static Component panicSlotLabel() {
        return Component.literal("Panic Item Slot: " + NexoraHpConfig.panicHotbarSlot);
    }

    private static Component panicCooldownLabel() {
        return Component.literal("Panic Cooldown: " + NexoraHpConfig.panicCooldownSeconds + "s");
    }
}
