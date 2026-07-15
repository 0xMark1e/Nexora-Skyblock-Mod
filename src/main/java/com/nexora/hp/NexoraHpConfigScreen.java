package com.nexora.hp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Settings screen: sidebar tabs on the left, a live search box up top that filters settings
 * across every tab at once, and a scrollable content column. Rows are plain vanilla widgets
 * (buttons/sliders) created on demand for whatever slice of the entry list is currently inside
 * the viewport -- scrolling and searching just rebuild that slice, so there's no custom clipping
 * or widget-offset bookkeeping to get wrong.
 */
public class NexoraHpConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 384;
    private static final int SIDEBAR_WIDTH = 96;
    private static final int PADDING = 12;
    private static final int TOP_BAR_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 32;
    private static final int ROW_HEIGHT_PX = 22;
    private static final int WIDGET_HEIGHT = 20;
    private static final int HEADER_HEIGHT_PX = 16;
    // 2 section headers + 6 rows: the Healing tab (the tallest) fits exactly, so scrolling only
    // ever kicks in for search results that span several tabs.
    private static final int VIEWPORT_HEIGHT = 2 * HEADER_HEIGHT_PX + 6 * ROW_HEIGHT_PX;
    private static final int TAB_ROW_HEIGHT = 22;
    private static final int SEARCH_WIDTH = 110;
    private static final int SCROLL_GUTTER = 8;
    private static final int SCROLL_STEP = 14;
    private static final int ACCENT_COLOR = 0xFF5CE6C7;
    private static final int TAB_INACTIVE_COLOR = 0xFF6E6E7A;

    private enum Tab {
        HEALING("Healing"),
        SLAYER("Slayer"),
        FISHING("Fishing"),
        DISPLAY("Display"),
        MISC("Misc");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private interface WidgetFactory {
        AbstractWidget create(int x, int y, int width, int height);
    }

    /** One row: lowercase haystack the search matches against, a tooltip blurb, and its widget. */
    private record Setting(String keywords, String description, WidgetFactory factory) {
    }

    private record Section(Tab tab, String label, List<Setting> settings, boolean collapsible) {
        Section(Tab tab, String label, List<Setting> settings) {
            this(tab, label, settings, false);
        }
    }

    /** A header row in the entry list; carries its Section so collapsible ones can take clicks. */
    private record HeaderEntry(String display, Section section) {
    }

    private record HeaderPos(String text, Section section, int y) {
    }

    // Remembered across open/close within a session so reopening lands on the tab you were using.
    private static Tab activeTab = Tab.HEALING;

    private final Screen parent;
    private final List<Section> sections = buildSections();

    private String searchQuery = "";
    private EditBox searchBox;
    private Button defaultsButton;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<HeaderPos> visibleHeaders = new ArrayList<>();
    private int scrollAmount = 0;
    private int totalContentHeight = 0;

    private int panelX1;
    private int panelX2;
    private int panelTop;
    private int panelBottom;
    private int contentX1;
    private int contentX2;
    private int viewportTop;
    private int viewportBottom;
    private int footerTop;

    // Labels of collapsible sections currently folded shut. Seeded with every collapsible
    // section, so bulky lists (the creature checklist) start closed each time the menu opens.
    private final Set<String> collapsedSections = new HashSet<>();

    public NexoraHpConfigScreen(Screen parent) {
        super(Component.literal("Nexora"));
        this.parent = parent;
        for (Section section : this.sections) {
            if (section.collapsible()) {
                this.collapsedSections.add(section.label());
            }
        }
    }

    /** A draggable slider snapped to a fixed step, over a [min, max] range, with a unit suffix. */
    private static final class ValueSlider extends AbstractSliderButton {
        private final String label;
        private final String suffix;
        private final int min;
        private final int max;
        private final int step;
        private final IntConsumer onChange;

        ValueSlider(int x, int y, int width, int height, String label, String suffix, int min, int max, int step,
                int initialValue, IntConsumer onChange) {
            super(x, y, width, height, Component.empty(), fraction(initialValue, min, max));
            this.label = label;
            this.suffix = suffix;
            this.min = min;
            this.max = max;
            this.step = step;
            this.onChange = onChange;
            this.updateMessage();
        }

        private static double fraction(int value, int min, int max) {
            return (value - min) / (double) (max - min);
        }

        private int currentValue() {
            int raw = min + (int) Math.round(this.value * (max - min));
            return Math.round(raw / (float) step) * step;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + currentValue() + suffix));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentValue());
        }
    }

    private static AbstractWidget toggle(int x, int y, int width, int height, Supplier<Component> label,
            Runnable flip) {
        return Button.builder(label.get(), b -> {
            flip.run();
            b.setMessage(label.get());
        }).bounds(x, y, width, height).build();
    }

    private static List<Section> buildSections() {
        List<Section> out = new ArrayList<>();

        out.add(new Section(Tab.HEALING, "GENERAL", List.of(
                new Setting("auto heal enabled master wand toggle",
                        "Automatically use your healing wand when HP drops below the threshold.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::enabledLabel,
                                () -> NexoraHpConfig.enabled = !NexoraHpConfig.enabled)),
                new Setting("heal below threshold percent hp trigger",
                        "HP percentage that triggers a heal attempt.",
                        (x, y, w, h) -> new ValueSlider(x, y, w, h, "Heal Below", "%", 10, 95, 5,
                                NexoraHpConfig.healThresholdPercent,
                                percent -> NexoraHpConfig.healThresholdPercent = percent)),
                new Setting("cooldown seconds wand ability wait",
                        "The wand's ability cooldown -- won't try again until this (+0.5s) has passed.",
                        (x, y, w, h) -> new ValueSlider(x, y, w, h, "Cooldown", "s", 1, 60, 1,
                                NexoraHpConfig.cooldownSeconds,
                                seconds -> NexoraHpConfig.cooldownSeconds = seconds)),
                new Setting("avoid ragnarock axe interrupt",
                        "Never interrupt a held Ragnarock to heal -- waits until you switch away yourself.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::avoidRagnarockLabel,
                                () -> NexoraHpConfig.avoidRagnarock = !NexoraHpConfig.avoidRagnarock)))));

        out.add(new Section(Tab.HEALING, "PANIC HEAL", List.of(
                new Setting("panic heal enabled emergency florid zombie sword",
                        "Below the panic threshold, spam the Florid Zombie Sword until its charges run out.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::panicEnabledLabel,
                                () -> NexoraHpConfig.panicEnabled = !NexoraHpConfig.panicEnabled)),
                new Setting("panic below threshold percent hp emergency",
                        "HP percentage that triggers the panic heal.",
                        (x, y, w, h) -> new ValueSlider(x, y, w, h, "Panic Below", "%", 5, 90, 5,
                                NexoraHpConfig.panicThresholdPercent,
                                percent -> NexoraHpConfig.panicThresholdPercent = percent)))));

        out.add(new Section(Tab.SLAYER, "GENERAL", List.of(
                new Setting("auto deployable orb flare slayer place power radiant mana overflux plasmaflux warning alert sos",
                        "When a slayer boss starts spawning, place whatever orb or flare is in your hotbar (looks down, places, looks back).",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::autoDeployableLabel,
                                () -> NexoraHpConfig.autoDeployableEnabled = !NexoraHpConfig.autoDeployableEnabled)))));

        out.add(new Section(Tab.SLAYER, "BLAZE SLAYER", List.of(
                new Setting("auto attunement dagger switch blaze slayer hellion shield",
                        "Auto-switch and toggle your daggers to match the boss's Hellion Shield attunement.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::autoAttunementLabel,
                                () -> NexoraHpConfig.autoAttunementEnabled = !NexoraHpConfig.autoAttunementEnabled)),
                new Setting("swap delay milliseconds attunement confirm retry",
                        "How long to wait for a dagger toggle to take effect before retrying it.",
                        (x, y, w, h) -> new ValueSlider(x, y, w, h, "Swap Delay", "ms",
                                AttunementController.MIN_CONFIRM_WINDOW_MILLIS,
                                AttunementController.MAX_CONFIRM_WINDOW_MILLIS, 50,
                                NexoraHpConfig.attunementSwitchDelayMillis,
                                millis -> NexoraHpConfig.attunementSwitchDelayMillis = millis)),
                new Setting("show attunement hud display boss blaze",
                        "Show the boss's current attunement on the HUD.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::showAttunementLabel,
                                () -> NexoraHpConfig.showAttunement = !NexoraHpConfig.showAttunement)))));

        out.add(new Section(Tab.SLAYER, "ENDERMAN SLAYER", List.of(
                new Setting("auto soulcry enderman slayer voidgloom katana",
                        "Keep the katana's Soulcry ability active for the whole Voidgloom fight (re-casts as it expires).",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::autoSoulcryLabel,
                                () -> NexoraHpConfig.autoSoulcryEnabled = !NexoraHpConfig.autoSoulcryEnabled)))));

        out.add(new Section(Tab.FISHING, "SEA CREATURES", List.of(
                new Setting("sea creature alert fishing spawn glow puddle jumper",
                        "Red alert title when a notable creature spawns, and keeps the mob glowing so it's easy to find.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::seaCreatureAlertLabel,
                                () -> NexoraHpConfig.seaCreatureAlertEnabled =
                                        !NexoraHpConfig.seaCreatureAlertEnabled)))));

        // One checkbox per known creature -- unchecked ones are ignored entirely (no alert,
        // locator, or glow). Generated from the alert's own list so new creatures show up here
        // automatically.
        List<Setting> creatureChecks = new ArrayList<>();
        for (String creature : SeaCreatureAlert.CREATURE_NAMES) {
            creatureChecks.add(new Setting("creature checklist " + creature.toLowerCase(Locale.ROOT),
                    "Alert (and track) when a " + creature + " spawns.",
                    (x, y, w, h) -> Checkbox.builder(Component.literal(creature), Minecraft.getInstance().font)
                            .pos(x, y)
                            .selected(!NexoraHpConfig.disabledCreatures.contains(creature))
                            .onValueChange((checkbox, checked) -> {
                                if (checked) {
                                    NexoraHpConfig.disabledCreatures.remove(creature);
                                } else {
                                    NexoraHpConfig.disabledCreatures.add(creature);
                                }
                            })
                            .maxWidth(w)
                            .build()));
        }
        out.add(new Section(Tab.FISHING, "ALERT LIST", List.copyOf(creatureChecks), true));

        out.add(new Section(Tab.DISPLAY, "DISPLAY", List.of(
                new Setting("show hud enabled overlay indicator hide toggle",
                        "Show the HUD overlay at all. Turn off to hide it completely.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::hudEnabledLabel,
                                () -> NexoraHpConfig.hudEnabled = !NexoraHpConfig.hudEnabled)),
                new Setting("heal sound chime notification audio",
                        "Play a chime when a heal fires.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::soundLabel,
                                () -> NexoraHpConfig.soundEnabled = !NexoraHpConfig.soundEnabled)),
                new Setting("hud position corner overlay indicator",
                        "Which screen corner the HUD indicator is drawn in.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::hudPositionLabel,
                                () -> NexoraHpConfig.hudPosition = NexoraHpConfig.hudPosition.next())))));

        out.add(new Section(Tab.MISC, "MISC", List.of(
                new Setting("auto cake eat gift collect click",
                        "Automatically look at and eat cakes gifted to you (the CLICK TO EAT prompt).",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::autoCakeLabel,
                                () -> NexoraHpConfig.autoCakeEnabled = !NexoraHpConfig.autoCakeEnabled)),
                new Setting("drop announcements crazy rare insane title price",
                        "Flash the big title with the item's live price on CRAZY RARE (pink) and INSANE (red) drops.",
                        (x, y, w, h) -> toggle(x, y, w, h, NexoraHpConfigScreen::dropAnnouncementsLabel,
                                () -> NexoraHpConfig.dropAnnouncementsEnabled =
                                        !NexoraHpConfig.dropAnnouncementsEnabled)))));

        return out;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.rowWidgets.clear();
        this.visibleHeaders.clear();

        int panelHeight = TOP_BAR_HEIGHT + 8 + VIEWPORT_HEIGHT + 8 + FOOTER_HEIGHT;
        int centerX = this.width / 2;
        this.panelX1 = centerX - PANEL_WIDTH / 2;
        this.panelX2 = centerX + PANEL_WIDTH / 2;
        this.panelTop = Math.max(4, this.height / 2 - panelHeight / 2);
        this.panelBottom = this.panelTop + panelHeight;
        this.viewportTop = this.panelTop + TOP_BAR_HEIGHT + 8;
        this.viewportBottom = this.viewportTop + VIEWPORT_HEIGHT;
        this.footerTop = this.viewportBottom + 8;
        this.contentX1 = this.panelX1 + SIDEBAR_WIDTH + PADDING;
        this.contentX2 = this.panelX2 - PADDING;

        this.searchBox = new EditBox(this.font, this.panelX2 - PADDING - SEARCH_WIDTH + 4, this.panelTop + 10,
                SEARCH_WIDTH - 8, 12, Component.literal("Search settings"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(Component.literal("Search..."));
        this.searchBox.setValue(this.searchQuery);
        this.searchBox.setResponder(query -> {
            if (!query.equals(this.searchQuery)) {
                this.searchQuery = query;
                this.scrollAmount = 0;
                this.rebuildRows();
            }
        });
        this.addRenderableWidget(this.searchBox);

        this.defaultsButton = Button.builder(Component.literal("Defaults"), b -> {
            resetTab(activeTab);
            this.rebuildRows();
        }).bounds(this.panelX1 + PADDING, this.footerTop + 6, 64, WIDGET_HEIGHT).build();
        this.defaultsButton.setTooltip(Tooltip.create(Component.literal("Reset this tab's settings to their defaults.")));
        this.addRenderableWidget(this.defaultsButton);

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(this.panelX2 - PADDING - 64, this.footerTop + 6, 64, WIDGET_HEIGHT).build());

        this.rebuildRows();
    }

    /** Headers as Strings, settings as Settings, in display order for the current tab/search. */
    private List<Object> buildEntries() {
        List<Object> entries = new ArrayList<>();
        String query = this.searchQuery.trim().toLowerCase(Locale.ROOT);

        if (query.isEmpty()) {
            for (Section section : this.sections) {
                if (section.tab() != activeTab) {
                    continue;
                }
                boolean collapsed = section.collapsible() && this.collapsedSections.contains(section.label());
                String display = section.label() + (section.collapsible() ? (collapsed ? " [+]" : " [-]") : "");
                entries.add(new HeaderEntry(display, section));
                if (!collapsed) {
                    entries.addAll(section.settings());
                }
            }
        } else {
            // Search overrides collapsing: a match inside a folded section still shows up.
            for (Section section : this.sections) {
                List<Setting> hits = section.settings().stream()
                        .filter(s -> s.keywords().contains(query))
                        .toList();
                if (!hits.isEmpty()) {
                    entries.add(new HeaderEntry(
                            section.tab().label.toUpperCase(Locale.ROOT) + " / " + section.label(), null));
                    entries.addAll(hits);
                }
            }
        }
        return entries;
    }

    /**
     * Recreates the row widgets for whatever slice of the entry list is inside the viewport.
     * Entries that would stick out past either edge are simply not created this pass -- they pop
     * in once scrolled fully into view, which avoids needing scissor-clipping of live widgets.
     */
    private void rebuildRows() {
        for (AbstractWidget widget : this.rowWidgets) {
            this.removeWidget(widget);
        }
        this.rowWidgets.clear();
        this.visibleHeaders.clear();

        List<Object> entries = buildEntries();

        this.totalContentHeight = 0;
        for (Object entry : entries) {
            this.totalContentHeight += entry instanceof HeaderEntry ? HEADER_HEIGHT_PX : ROW_HEIGHT_PX;
        }
        this.scrollAmount = Math.max(0, Math.min(this.scrollAmount, this.totalContentHeight - VIEWPORT_HEIGHT));

        int widgetWidth = this.contentX2 - this.contentX1 - SCROLL_GUTTER;
        int y = -this.scrollAmount;
        for (Object entry : entries) {
            int entryHeight = entry instanceof HeaderEntry ? HEADER_HEIGHT_PX : ROW_HEIGHT_PX;
            if (y >= 0 && y + entryHeight <= VIEWPORT_HEIGHT) {
                if (entry instanceof HeaderEntry header) {
                    this.visibleHeaders.add(new HeaderPos(header.display(), header.section(), this.viewportTop + y));
                } else {
                    Setting setting = (Setting) entry;
                    AbstractWidget widget = setting.factory()
                            .create(this.contentX1, this.viewportTop + y, widgetWidth, WIDGET_HEIGHT);
                    widget.setTooltip(Tooltip.create(Component.literal(setting.description())));
                    this.rowWidgets.add(widget);
                    this.addRenderableWidget(widget);
                }
            }
            y += entryHeight;
        }

        this.defaultsButton.active = this.searchQuery.isBlank();
    }

    private static void resetTab(Tab tab) {
        switch (tab) {
            case HEALING -> {
                NexoraHpConfig.enabled = NexoraHpConfig.DEFAULT_ENABLED;
                NexoraHpConfig.healThresholdPercent = NexoraHpConfig.DEFAULT_HEAL_THRESHOLD_PERCENT;
                NexoraHpConfig.cooldownSeconds = NexoraHpConfig.DEFAULT_COOLDOWN_SECONDS;
                NexoraHpConfig.avoidRagnarock = NexoraHpConfig.DEFAULT_AVOID_RAGNAROCK;
                NexoraHpConfig.panicEnabled = NexoraHpConfig.DEFAULT_PANIC_ENABLED;
                NexoraHpConfig.panicThresholdPercent = NexoraHpConfig.DEFAULT_PANIC_THRESHOLD_PERCENT;
            }
            case SLAYER -> {
                NexoraHpConfig.autoDeployableEnabled = NexoraHpConfig.DEFAULT_AUTO_DEPLOYABLE_ENABLED;
                NexoraHpConfig.autoAttunementEnabled = NexoraHpConfig.DEFAULT_AUTO_ATTUNEMENT_ENABLED;
                NexoraHpConfig.attunementSwitchDelayMillis = NexoraHpConfig.DEFAULT_ATTUNEMENT_SWITCH_DELAY_MILLIS;
                NexoraHpConfig.showAttunement = NexoraHpConfig.DEFAULT_SHOW_ATTUNEMENT;
                NexoraHpConfig.autoSoulcryEnabled = NexoraHpConfig.DEFAULT_AUTO_SOULCRY_ENABLED;
            }
            case FISHING -> {
                NexoraHpConfig.seaCreatureAlertEnabled = NexoraHpConfig.DEFAULT_SEA_CREATURE_ALERT_ENABLED;
                NexoraHpConfig.disabledCreatures.clear();
            }
            case DISPLAY -> {
                NexoraHpConfig.hudEnabled = NexoraHpConfig.DEFAULT_HUD_ENABLED;
                NexoraHpConfig.soundEnabled = NexoraHpConfig.DEFAULT_SOUND_ENABLED;
                NexoraHpConfig.hudPosition = NexoraHpConfig.DEFAULT_HUD_POSITION;
            }
            case MISC -> {
                NexoraHpConfig.autoCakeEnabled = NexoraHpConfig.DEFAULT_AUTO_CAKE_ENABLED;
                NexoraHpConfig.dropAnnouncementsEnabled = NexoraHpConfig.DEFAULT_DROP_ANNOUNCEMENTS_ENABLED;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Collapsible section headers toggle open/shut on click.
        if (event.button() == 0 && event.x() >= this.contentX1 && event.x() < this.contentX2) {
            for (HeaderPos header : this.visibleHeaders) {
                if (header.section() != null && header.section().collapsible()
                        && event.y() >= header.y() && event.y() < header.y() + HEADER_HEIGHT_PX) {
                    String label = header.section().label();
                    if (!this.collapsedSections.remove(label)) {
                        this.collapsedSections.add(label);
                    }
                    this.rebuildRows();
                    return true;
                }
            }
        }

        if (event.button() == 0 && event.x() >= this.panelX1 && event.x() < this.panelX1 + SIDEBAR_WIDTH
                && event.y() >= this.viewportTop && event.y() < this.viewportBottom) {
            int index = (int) ((event.y() - this.viewportTop) / TAB_ROW_HEIGHT);
            Tab[] tabs = Tab.values();
            if (index >= 0 && index < tabs.length) {
                Tab clicked = tabs[index];
                boolean searching = !this.searchQuery.isBlank();
                if (clicked != activeTab || searching) {
                    activeTab = clicked;
                    this.searchQuery = "";
                    this.searchBox.setValue("");
                    this.scrollAmount = 0;
                    this.rebuildRows();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= this.contentX1 && mouseX < this.panelX2
                && mouseY >= this.viewportTop && mouseY < this.viewportBottom
                && this.totalContentHeight > VIEWPORT_HEIGHT) {
            this.scrollAmount -= (int) (scrollY * SCROLL_STEP);
            this.rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x99050507);

        int panelHeight = this.panelBottom - this.panelTop;
        graphics.fillGradient(this.panelX1, this.panelTop, this.panelX2, this.panelBottom, 0xF0181822, 0xF00E0E16);
        graphics.outline(this.panelX1, this.panelTop, PANEL_WIDTH, panelHeight, 0x22FFFFFF);
        drawCornerBrackets(graphics, this.panelX1, this.panelTop, this.panelX2, this.panelBottom, 10, ACCENT_COLOR);
        graphics.fill(this.panelX1 + 1, this.panelTop + 1, this.panelX2 - 1, this.panelTop + 3, ACCENT_COLOR);

        graphics.text(this.font, this.title, this.panelX1 + PADDING, this.panelTop + 11, 0xFFFFFFFF);

        // Search box backdrop (the EditBox itself is borderless so it can sit on our own styling).
        int searchX1 = this.panelX2 - PADDING - SEARCH_WIDTH;
        graphics.fill(searchX1, this.panelTop + 6, this.panelX2 - PADDING, this.panelTop + 24, 0xFF0A0A10);
        graphics.outline(searchX1, this.panelTop + 6, SEARCH_WIDTH, 18,
                this.searchBox.isFocused() ? ACCENT_COLOR : 0x33FFFFFF);

        graphics.horizontalLine(this.panelX1 + 1, this.panelX2 - 2, this.panelTop + TOP_BAR_HEIGHT - 1, 0x30FFFFFF);

        // Sidebar.
        graphics.fill(this.panelX1 + 1, this.panelTop + TOP_BAR_HEIGHT, this.panelX1 + SIDEBAR_WIDTH,
                this.panelBottom - 1, 0x40000000);
        graphics.verticalLine(this.panelX1 + SIDEBAR_WIDTH, this.panelTop + TOP_BAR_HEIGHT - 1, this.panelBottom - 1,
                0x30FFFFFF);

        boolean searching = !this.searchQuery.isBlank();
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tabY = this.viewportTop + i * TAB_ROW_HEIGHT;
            boolean active = !searching && tabs[i] == activeTab;
            boolean hovered = mouseX >= this.panelX1 && mouseX < this.panelX1 + SIDEBAR_WIDTH
                    && mouseY >= tabY && mouseY < tabY + TAB_ROW_HEIGHT;
            if (active) {
                graphics.fill(this.panelX1 + 1, tabY, this.panelX1 + SIDEBAR_WIDTH, tabY + TAB_ROW_HEIGHT,
                        0x205CE6C7);
                graphics.fill(this.panelX1 + 1, tabY + 3, this.panelX1 + 3, tabY + TAB_ROW_HEIGHT - 3, ACCENT_COLOR);
            } else if (hovered) {
                graphics.fill(this.panelX1 + 1, tabY, this.panelX1 + SIDEBAR_WIDTH, tabY + TAB_ROW_HEIGHT,
                        0x18FFFFFF);
            }
            int color = active ? 0xFFFFFFFF : TAB_INACTIVE_COLOR;
            graphics.text(this.font, tabs[i].label, this.panelX1 + 10,
                    tabY + (TAB_ROW_HEIGHT - this.font.lineHeight) / 2 + 1, color);
        }

        // Section headers for the rows currently in view.
        for (HeaderPos header : this.visibleHeaders) {
            int textY = header.y() + 4;
            graphics.text(this.font, header.text(), this.contentX1, textY, ACCENT_COLOR);
            int lineX1 = this.contentX1 + this.font.width(header.text()) + 6;
            graphics.horizontalLine(lineX1, this.contentX2 - SCROLL_GUTTER, textY + this.font.lineHeight / 2,
                    0x40FFFFFF);
        }

        if (searching && this.rowWidgets.isEmpty() && this.visibleHeaders.isEmpty()) {
            graphics.centeredText(this.font, "No matching settings",
                    (this.contentX1 + this.contentX2) / 2,
                    (this.viewportTop + this.viewportBottom) / 2 - this.font.lineHeight / 2, 0xFF808089);
        }

        // Scrollbar, only when there's actually something to scroll.
        if (this.totalContentHeight > VIEWPORT_HEIGHT) {
            int trackX1 = this.contentX2 - 3;
            graphics.fill(trackX1, this.viewportTop, this.contentX2 - 1, this.viewportBottom, 0x22FFFFFF);
            int thumbHeight = Math.max(12, VIEWPORT_HEIGHT * VIEWPORT_HEIGHT / this.totalContentHeight);
            int thumbY = this.viewportTop + this.scrollAmount * (VIEWPORT_HEIGHT - thumbHeight)
                    / (this.totalContentHeight - VIEWPORT_HEIGHT);
            graphics.fill(trackX1, thumbY, this.contentX2 - 1, thumbY + thumbHeight, ACCENT_COLOR);
        }

        // Footer.
        graphics.horizontalLine(this.panelX1 + 1, this.panelX2 - 2, this.footerTop, 0x30FFFFFF);
        graphics.centeredText(this.font, "Nexora • v1.0.4", (this.panelX1 + this.panelX2) / 2,
                this.footerTop + 12, 0xFF55555F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Four accent-colored corner brackets instead of a full border -- matches the HUD's look. */
    private static void drawCornerBrackets(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int len,
            int color) {
        int t = 1;
        graphics.fill(x1, y1, x1 + len, y1 + t, color);
        graphics.fill(x1, y1, x1 + t, y1 + len, color);
        graphics.fill(x2 - len, y1, x2, y1 + t, color);
        graphics.fill(x2 - t, y1, x2, y1 + len, color);
        graphics.fill(x1, y2 - t, x1 + len, y2, color);
        graphics.fill(x1, y2 - len, x1 + t, y2, color);
        graphics.fill(x2 - len, y2 - t, x2, y2, color);
        graphics.fill(x2 - t, y2 - len, x2, y2, color);
    }

    @Override
    public void onClose() {
        NexoraHpConfig.save();
        this.minecraft.setScreen(parent);
    }

    private static Component enabledLabel() {
        return Component.literal("Auto-Heal: " + (NexoraHpConfig.enabled ? "ON" : "OFF"));
    }

    private static Component hudEnabledLabel() {
        return Component.literal("Show HUD: " + (NexoraHpConfig.hudEnabled ? "ON" : "OFF"));
    }

    private static Component soundLabel() {
        return Component.literal("Heal Sound: " + (NexoraHpConfig.soundEnabled ? "ON" : "OFF"));
    }

    private static Component hudPositionLabel() {
        return Component.literal("HUD Position: " + NexoraHpConfig.hudPosition.name().replace('_', ' '));
    }

    private static Component avoidRagnarockLabel() {
        return Component.literal("Avoid Ragnarock: " + (NexoraHpConfig.avoidRagnarock ? "ON" : "OFF"));
    }

    private static Component panicEnabledLabel() {
        return Component.literal("Panic Heal: " + (NexoraHpConfig.panicEnabled ? "ON" : "OFF"));
    }

    private static Component autoCakeLabel() {
        return Component.literal("Auto Cake: " + (NexoraHpConfig.autoCakeEnabled ? "ON" : "OFF"));
    }

    private static Component dropAnnouncementsLabel() {
        return Component.literal("Drop Announce: " + (NexoraHpConfig.dropAnnouncementsEnabled ? "ON" : "OFF"));
    }

    private static Component seaCreatureAlertLabel() {
        return Component.literal("Creature Alert: " + (NexoraHpConfig.seaCreatureAlertEnabled ? "ON" : "OFF"));
    }

    private static Component autoAttunementLabel() {
        return Component.literal("Auto Attunement: " + (NexoraHpConfig.autoAttunementEnabled ? "ON" : "OFF"));
    }

    private static Component showAttunementLabel() {
        return Component.literal("Show Attunement: " + (NexoraHpConfig.showAttunement ? "ON" : "OFF"));
    }

    private static Component autoSoulcryLabel() {
        return Component.literal("Auto Soulcry: " + (NexoraHpConfig.autoSoulcryEnabled ? "ON" : "OFF"));
    }

    private static Component autoDeployableLabel() {
        return Component.literal("Auto Deployable: " + (NexoraHpConfig.autoDeployableEnabled ? "ON" : "OFF"));
    }
}
