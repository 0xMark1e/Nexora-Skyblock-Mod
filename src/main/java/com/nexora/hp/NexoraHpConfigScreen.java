package com.nexora.hp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NexoraHpConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 22;
    private static final int SECTION_GAP = 14;
    private static final int SECTION_HEADER_HEIGHT = 14;
    private static final int TITLE_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 22;
    private static final int ACCENT_COLOR = 0xFF5CE6C7;

    // Row counts per section, used to compute the panel's height up front so it's always
    // correctly centered and sized instead of relying on a hand-tuned starting offset.
    private static final int GENERAL_ROWS = 3;
    private static final int DISPLAY_ROWS = 2;

    private final Screen parent;

    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    private int panelTop;
    private int panelBottom;

    public NexoraHpConfigScreen(Screen parent) {
        super(Component.literal("Nexora-Heal"));
        this.parent = parent;
    }

    private record SectionHeader(String text, int y) {
    }

    /** A draggable percentage slider, snapped to 5% steps, over a fixed [min, max] range. */
    private static final class PercentSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer onChange;

        PercentSlider(int x, int y, int width, int height, String label, int min, int max, int initialPercent,
                IntConsumer onChange) {
            super(x, y, width, height, Component.empty(), fraction(initialPercent, min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            this.updateMessage();
        }

        private static double fraction(int percent, int min, int max) {
            return (percent - min) / (double) (max - min);
        }

        private int currentPercent() {
            int raw = min + (int) Math.round(this.value * (max - min));
            return Math.round(raw / 5f) * 5;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + currentPercent() + "%"));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentPercent());
        }
    }

    private static int sectionHeight(int rows) {
        return SECTION_HEADER_HEIGHT + rows * SPACING;
    }

    @Override
    protected void init() {
        this.sectionHeaders.clear();

        int contentHeight = sectionHeight(GENERAL_ROWS) + SECTION_GAP
                + sectionHeight(DISPLAY_ROWS) + 12 + BUTTON_HEIGHT;
        int panelHeight = TITLE_HEIGHT + contentHeight + FOOTER_HEIGHT;

        int centerX = this.width / 2;
        this.panelTop = Math.max(4, this.height / 2 - panelHeight / 2);
        this.panelBottom = this.panelTop + panelHeight;

        int y = this.panelTop + TITLE_HEIGHT;

        y = section(centerX, y, "GENERAL");

        Button enabledButton = Button.builder(enabledLabel(), b -> {
            NexoraHpConfig.enabled = !NexoraHpConfig.enabled;
            b.setMessage(enabledLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(enabledButton);
        y += SPACING;

        this.addRenderableWidget(new PercentSlider(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Heal Below", 10, 95, NexoraHpConfig.healThresholdPercent,
                percent -> NexoraHpConfig.healThresholdPercent = percent));
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
    }

    /** Draws a small caps section label with a divider line, and returns the y for the first widget in it. */
    private int section(int centerX, int y, String text) {
        this.sectionHeaders.add(new SectionHeader(text, y));
        return y + SECTION_HEADER_HEIGHT;
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

        graphics.centeredText(this.font, "Nexora-Heal • v1.0.0", centerX, this.panelBottom - 12, 0xFF55555F);

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
}
