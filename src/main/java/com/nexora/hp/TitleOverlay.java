package com.nexora.hp;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2fStack;

/**
 * A big centered announcement title (the "PERSONAL BEST!" style) in two flavors: celebrations
 * (neon pink and fully animated -- overshoot-bounce pop-in, breathing, a shimmer wave across the
 * letters over a pulsing two-layer glow, sparkles, decorative side rails, upward drift-fade) and
 * {@link #alert alerts} (plain bold red with a hard shadow plus a chime -- deliberately calmer).
 * /testtext and /testdrop drive it manually; features call {@link #show}/{@link #showDrop}/
 * {@link #alert} for their own announcements.
 *
 * All alpha math bails below 8: the vanilla font/fill renderers treat near-zero alpha as fully
 * opaque, so a "faded out" element would otherwise snap back to full brightness.
 */
final class TitleOverlay {

    private static final int DISPLAY_MILLIS = 3000;
    private static final int POP_IN_MILLIS = 260;
    private static final int FADE_MILLIS = 600;
    private static final float SCALE = 3.0f;
    private static final int SPARKLE_COUNT = 12;

    // RGB only -- alpha is composed per frame from the animation state.
    private static final int PINK_CORE = 0xFF4FDE;
    private static final int PINK_BRIGHT = 0xFFC9F0;
    private static final int PINK_GLOW = 0xA3128F;
    private static final int GOLD_CORE = 0xFFAA22;
    private static final int GOLD_BRIGHT = 0xFFE87A;
    private static final int GOLD_GLOW = 0x8F5500;
    private static final int RED_CORE = 0xFF4040;
    private static final int RED_BRIGHT = 0xFF9C9C;
    private static final int RED_GLOW = 0x7A0D0D;
    private static final int SPARKLE_RGB = 0xFFE3F8;

    /** A run of text with its own shimmer colors, so one title can mix e.g. name and price. */
    record Segment(String text, int coreRgb, int brightRgb, int glowRgb) {
        static Segment pink(String text) {
            return new Segment(text, PINK_CORE, PINK_BRIGHT, PINK_GLOW);
        }

        static Segment gold(String text) {
            return new Segment(text, GOLD_CORE, GOLD_BRIGHT, GOLD_GLOW);
        }

        static Segment red(String text) {
            return new Segment(text, RED_CORE, RED_BRIGHT, RED_GLOW);
        }
    }

    private static List<Segment> segments = null;
    private static boolean alertStyle = false;
    private static long shownAt = 0L;
    private static long showUntil = 0L;

    private TitleOverlay() {
    }

    static void show(String message) {
        show(List.of(Segment.pink(message)));
    }

    /**
     * A warning: plain red text (hard shadow, no sparkles/rails/shimmer -- deliberately calmer
     * than the drop celebration) plus an alert chime.
     */
    static void alert(String message) {
        display(List.of(Segment.red(message)), true);
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 0.7F));
    }

    /** The drop announcement layout: item name in neon pink, "(price)" in orange-gold. */
    static void showDrop(String itemName, String price) {
        show(List.of(Segment.pink(itemName + " "), Segment.gold("(" + price + ")")));
    }

    static void show(List<Segment> titleSegments) {
        display(titleSegments, false);
    }

    private static void display(List<Segment> titleSegments, boolean alert) {
        segments = titleSegments;
        alertStyle = alert;
        shownAt = System.currentTimeMillis();
        showUntil = shownAt + DISPLAY_MILLIS;
    }

    static void render(GuiGraphicsExtractor graphics) {
        if (segments == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = showUntil - now;
        if (remaining <= 0) {
            segments = null;
            return;
        }
        long elapsed = now - shownAt;

        // Entry: overshoot bounce on scale, quick fade-in. Exit: fade plus a slight upward drift.
        float in = Math.min(1f, elapsed / (float) POP_IN_MILLIS);
        float out = remaining >= FADE_MILLIS ? 1f : remaining / (float) FADE_MILLIS;
        float drift = (1f - out) * -10f;
        int alpha = Math.round(Math.min(1f, elapsed / 150f) * out * 255f);
        if (alpha < 8) {
            return;
        }

        float breath = 1f + 0.015f * (float) Math.sin(now / 180.0);
        float scale = (alertStyle ? 2.5f : SCALE) * easeOutBack(in) * breath;
        if (scale < 0.05f) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int totalWidth = 0;
        for (Segment segment : segments) {
            totalWidth += font.width("§l" + segment.text());
        }
        int left = -totalWidth / 2;
        int right = left + totalWidth;

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() * 0.25f + drift);
        pose.scale(scale);

        if (alertStyle) {
            drawAlertText(graphics, font, left, alpha);
        } else {
            drawRails(graphics, font, left, right, totalWidth, in, alpha);
            drawGlow(graphics, font, left, now, alpha);
            drawShimmeringCore(graphics, font, left, now, alpha);
            drawSparkles(graphics, font, left, totalWidth, now, alpha);
        }

        pose.popMatrix();
    }

    /** The alert look: bold red text over one hard dark-red shadow copy, nothing else. */
    private static void drawAlertText(GuiGraphicsExtractor graphics, Font font, int left, int alpha) {
        int x = left;
        for (Segment segment : segments) {
            String display = "§l" + segment.text();
            int shadow = Math.round(alpha * 0.6f);
            if (shadow >= 8) {
                graphics.text(font, display, x + 1, 1, (shadow << 24) | segment.glowRgb());
            }
            graphics.text(font, display, x, 0, (alpha << 24) | segment.coreRgb());
            x += font.width(display);
        }
    }

    /** Horizontal accent rails growing outward from the text edges, tipped with small diamonds. */
    private static void drawRails(GuiGraphicsExtractor graphics, Font font, int left, int right, int totalWidth,
            float in, int alpha) {
        int railAlpha = Math.round(alpha * 0.55f);
        if (railAlpha < 8) {
            return;
        }
        int railColor = (railAlpha << 24) | PINK_CORE;
        int tipColor = (Math.min(255, railAlpha + 60) << 24) | PINK_BRIGHT;

        int y = font.lineHeight / 2;
        int gap = 8;
        int length = Math.round((totalWidth * 0.4f + 12f) * in);
        if (length < 2) {
            return;
        }

        graphics.fill(left - gap - length, y, left - gap, y + 1, railColor);
        graphics.fill(right + gap, y, right + gap + length, y + 1, railColor);
        // Diamond tips: a 3x3 dim square with a bright center reads as a diamond at this scale.
        drawTip(graphics, left - gap - length, y, railColor, tipColor);
        drawTip(graphics, right + gap + length, y, railColor, tipColor);
    }

    private static void drawTip(GuiGraphicsExtractor graphics, int x, int y, int dim, int bright) {
        graphics.fill(x - 1, y - 1, x + 2, y + 2, dim);
        graphics.fill(x, y, x + 1, y + 1, bright);
    }

    /** Two glow layers behind the text, pulsing slowly: tight bright ring, wide faint ring. */
    private static void drawGlow(GuiGraphicsExtractor graphics, Font font, int left, long now, int alpha) {
        float pulse = 0.8f + 0.2f * (float) Math.sin(now / 200.0);
        int inner = Math.round(alpha * 0.45f * pulse);
        int outer = Math.round(alpha * 0.18f * pulse);

        int x = left;
        for (Segment segment : segments) {
            String display = "§l" + segment.text();
            if (inner >= 8) {
                int color = (inner << 24) | segment.glowRgb();
                graphics.text(font, display, x - 1, -1, color);
                graphics.text(font, display, x + 1, -1, color);
                graphics.text(font, display, x - 1, 1, color);
                graphics.text(font, display, x + 1, 1, color);
            }
            if (outer >= 8) {
                int color = (outer << 24) | segment.glowRgb();
                graphics.text(font, display, x - 2, 0, color);
                graphics.text(font, display, x + 2, 0, color);
                graphics.text(font, display, x, -2, color);
                graphics.text(font, display, x, 2, color);
            }
            x += font.width(display);
        }
    }

    /**
     * The core text, drawn per character so a brightness wave can sweep across the letters. The
     * wave index runs across segment boundaries so it flows through the whole title unbroken.
     */
    private static void drawShimmeringCore(GuiGraphicsExtractor graphics, Font font, int left, long now, int alpha) {
        int x = left;
        int charIndex = 0;
        for (Segment segment : segments) {
            for (int i = 0; i < segment.text().length(); i++, charIndex++) {
                String glyph = "§l" + segment.text().charAt(i);
                float wave = 0.5f + 0.5f * (float) Math.sin(now / 120.0 - charIndex * 0.55);
                int color = (alpha << 24) | lerpRgb(segment.coreRgb(), segment.brightRgb(), wave);
                graphics.text(font, glyph, x, 0, color);
                x += font.width(glyph);
            }
        }
    }

    /** Deterministic twinkling sparkles scattered around the text box. */
    private static void drawSparkles(GuiGraphicsExtractor graphics, Font font, int left, int totalWidth, long now,
            int alpha) {
        for (int i = 0; i < SPARKLE_COUNT; i++) {
            int x = Math.round(left - 10 + hash(i * 3 + 1) * (totalWidth + 20));
            int y = Math.round(-8 + hash(i * 3 + 2) * (font.lineHeight + 16));
            float twinkle = 0.5f + 0.5f * (float) Math.sin(now / 150.0 + hash(i * 3 + 3) * (float) (Math.PI * 2));
            int sparkleAlpha = Math.round(alpha * twinkle * 0.9f);
            if (sparkleAlpha < 8) {
                continue;
            }
            graphics.fill(x, y, x + 1, y + 1, (sparkleAlpha << 24) | SPARKLE_RGB);
            int armAlpha = Math.round(sparkleAlpha * 0.5f);
            if (armAlpha >= 8) {
                int arm = (armAlpha << 24) | PINK_CORE;
                graphics.fill(x - 1, y, x, y + 1, arm);
                graphics.fill(x + 1, y, x + 2, y + 1, arm);
                graphics.fill(x, y - 1, x + 1, y, arm);
                graphics.fill(x, y + 1, x + 1, y + 2, arm);
            }
        }
    }

    /** Standard ease-out-back: overshoots slightly past 1 before settling, for the pop-in bounce. */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** Deterministic 0..1 hash so sparkle placement is stable across frames without stored state. */
    private static float hash(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 16;
        return (h & 0x7FFF) / 32767f;
    }

    private static int lerpRgb(int rgbA, int rgbB, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((rgbA >> 16) & 0xFF) + ((((rgbB >> 16) & 0xFF) - ((rgbA >> 16) & 0xFF)) * t));
        int g = Math.round(((rgbA >> 8) & 0xFF) + ((((rgbB >> 8) & 0xFF) - ((rgbA >> 8) & 0xFF)) * t));
        int b = Math.round((rgbA & 0xFF) + (((rgbB & 0xFF) - (rgbA & 0xFF)) * t));
        return (r << 16) | (g << 8) | b;
    }
}
