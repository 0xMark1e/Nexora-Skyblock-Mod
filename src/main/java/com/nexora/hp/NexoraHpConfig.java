package com.nexora.hp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class NexoraHpConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("nexora-wand.properties");

    public enum HudPosition {
        TOP_RIGHT, TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public HudPosition next() {
            HudPosition[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    public static boolean enabled = true;
    public static int healThresholdPercent = 70;
    public static int hotbarSlot = 3; // 1-indexed, as shown to the player
    public static int cooldownSeconds = 7;
    public static boolean soundEnabled = true;
    public static HudPosition hudPosition = HudPosition.TOP_RIGHT;

    public static boolean panicEnabled = true;
    public static int panicThresholdPercent = 30;
    public static int panicHotbarSlot = 4; // 1-indexed, as shown to the player
    public static int panicCooldownSeconds = 5;

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
        hotbarSlot = clamp(parseInt(props.getProperty("hotbarSlot"), hotbarSlot), 1, 9);
        cooldownSeconds = clamp(parseInt(props.getProperty("cooldownSeconds"), cooldownSeconds), 1, 60);
        soundEnabled = Boolean.parseBoolean(props.getProperty("soundEnabled", String.valueOf(soundEnabled)));
        hudPosition = parseHudPosition(props.getProperty("hudPosition"), hudPosition);

        panicEnabled = Boolean.parseBoolean(props.getProperty("panicEnabled", String.valueOf(panicEnabled)));
        panicThresholdPercent = clamp(parseInt(props.getProperty("panicThresholdPercent"), panicThresholdPercent), 5, 90);
        panicHotbarSlot = clamp(parseInt(props.getProperty("panicHotbarSlot"), panicHotbarSlot), 1, 9);
        panicCooldownSeconds = clamp(parseInt(props.getProperty("panicCooldownSeconds"), panicCooldownSeconds), 1, 60);
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("healThresholdPercent", String.valueOf(healThresholdPercent));
        props.setProperty("hotbarSlot", String.valueOf(hotbarSlot));
        props.setProperty("cooldownSeconds", String.valueOf(cooldownSeconds));
        props.setProperty("soundEnabled", String.valueOf(soundEnabled));
        props.setProperty("hudPosition", hudPosition.name());

        props.setProperty("panicEnabled", String.valueOf(panicEnabled));
        props.setProperty("panicThresholdPercent", String.valueOf(panicThresholdPercent));
        props.setProperty("panicHotbarSlot", String.valueOf(panicHotbarSlot));
        props.setProperty("panicCooldownSeconds", String.valueOf(panicCooldownSeconds));

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
