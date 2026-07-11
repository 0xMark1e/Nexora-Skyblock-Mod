package com.nexora.hp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class NexoraHpConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("nexora-heal.properties");

    public enum HudPosition {
        TOP_RIGHT, TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public HudPosition next() {
            HudPosition[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    // Defaults live here (not just in the initializers) so the config screen's per-tab
    // "Defaults" button has a single source of truth to reset back to.
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_HEAL_THRESHOLD_PERCENT = 70;
    public static final int DEFAULT_COOLDOWN_SECONDS = 7;
    public static final boolean DEFAULT_SOUND_ENABLED = true;
    public static final boolean DEFAULT_HUD_ENABLED = true;
    public static final HudPosition DEFAULT_HUD_POSITION = HudPosition.TOP_RIGHT;
    public static final boolean DEFAULT_AVOID_RAGNAROCK = true;
    public static final boolean DEFAULT_PANIC_ENABLED = true;
    public static final int DEFAULT_PANIC_THRESHOLD_PERCENT = 25;
    public static final boolean DEFAULT_SHOW_ATTUNEMENT = true;
    public static final boolean DEFAULT_AUTO_ATTUNEMENT_ENABLED = true;
    public static final int DEFAULT_ATTUNEMENT_SWITCH_DELAY_MILLIS = 1000;
    public static final boolean DEFAULT_AUTO_SOULCRY_ENABLED = true;
    public static final boolean DEFAULT_AUTO_CAKE_ENABLED = true;

    public static boolean enabled = DEFAULT_ENABLED;
    public static int healThresholdPercent = DEFAULT_HEAL_THRESHOLD_PERCENT;
    public static int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    public static boolean soundEnabled = DEFAULT_SOUND_ENABLED;
    public static boolean hudEnabled = DEFAULT_HUD_ENABLED;
    public static HudPosition hudPosition = DEFAULT_HUD_POSITION;
    public static boolean avoidRagnarock = DEFAULT_AVOID_RAGNAROCK;
    public static boolean panicEnabled = DEFAULT_PANIC_ENABLED;
    public static int panicThresholdPercent = DEFAULT_PANIC_THRESHOLD_PERCENT;
    public static boolean showAttunement = DEFAULT_SHOW_ATTUNEMENT;
    public static boolean autoAttunementEnabled = DEFAULT_AUTO_ATTUNEMENT_ENABLED;
    public static int attunementSwitchDelayMillis = DEFAULT_ATTUNEMENT_SWITCH_DELAY_MILLIS;
    public static boolean autoSoulcryEnabled = DEFAULT_AUTO_SOULCRY_ENABLED;
    public static boolean autoCakeEnabled = DEFAULT_AUTO_CAKE_ENABLED;

    private NexoraHpConfig() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }

        enabled = Boolean.parseBoolean(props.getProperty("enabled", String.valueOf(enabled)));
        healThresholdPercent = clamp(parseInt(props.getProperty("healThresholdPercent"), healThresholdPercent), 10, 95);
        cooldownSeconds = clamp(parseInt(props.getProperty("cooldownSeconds"), cooldownSeconds), 1, 60);
        soundEnabled = Boolean.parseBoolean(props.getProperty("soundEnabled", String.valueOf(soundEnabled)));
        hudEnabled = Boolean.parseBoolean(props.getProperty("hudEnabled", String.valueOf(hudEnabled)));
        hudPosition = parseHudPosition(props.getProperty("hudPosition"), hudPosition);
        avoidRagnarock = Boolean.parseBoolean(props.getProperty("avoidRagnarock", String.valueOf(avoidRagnarock)));
        panicEnabled = Boolean.parseBoolean(props.getProperty("panicEnabled", String.valueOf(panicEnabled)));
        panicThresholdPercent = clamp(parseInt(props.getProperty("panicThresholdPercent"), panicThresholdPercent), 5, 90);
        showAttunement = Boolean.parseBoolean(props.getProperty("showAttunement", String.valueOf(showAttunement)));
        autoAttunementEnabled = Boolean.parseBoolean(
                props.getProperty("autoAttunementEnabled", String.valueOf(autoAttunementEnabled)));
        attunementSwitchDelayMillis = clamp(
                parseInt(props.getProperty("attunementSwitchDelayMillis"), attunementSwitchDelayMillis),
                AttunementController.MIN_CONFIRM_WINDOW_MILLIS, AttunementController.MAX_CONFIRM_WINDOW_MILLIS);
        autoSoulcryEnabled = Boolean.parseBoolean(
                props.getProperty("autoSoulcryEnabled", String.valueOf(autoSoulcryEnabled)));
        autoCakeEnabled = Boolean.parseBoolean(props.getProperty("autoCakeEnabled", String.valueOf(autoCakeEnabled)));
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("healThresholdPercent", String.valueOf(healThresholdPercent));
        props.setProperty("cooldownSeconds", String.valueOf(cooldownSeconds));
        props.setProperty("soundEnabled", String.valueOf(soundEnabled));
        props.setProperty("hudEnabled", String.valueOf(hudEnabled));
        props.setProperty("hudPosition", hudPosition.name());
        props.setProperty("avoidRagnarock", String.valueOf(avoidRagnarock));
        props.setProperty("panicEnabled", String.valueOf(panicEnabled));
        props.setProperty("panicThresholdPercent", String.valueOf(panicThresholdPercent));
        props.setProperty("showAttunement", String.valueOf(showAttunement));
        props.setProperty("autoAttunementEnabled", String.valueOf(autoAttunementEnabled));
        props.setProperty("attunementSwitchDelayMillis", String.valueOf(attunementSwitchDelayMillis));
        props.setProperty("autoSoulcryEnabled", String.valueOf(autoSoulcryEnabled));
        props.setProperty("autoCakeEnabled", String.valueOf(autoCakeEnabled));

        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            props.store(out, "Nexora HP settings");
        } catch (IOException ignored) {
            // Not fatal: settings just won't persist across restarts this time.
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static HudPosition parseHudPosition(String value, HudPosition fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return HudPosition.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
