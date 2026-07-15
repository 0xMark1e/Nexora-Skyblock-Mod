package com.nexora.hp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * Flashes the big title when Hypixel announces a top-tier drop in chat. Only the two highest
 * announcement tiers trigger it -- CRAZY RARE DROP! (shown in the usual neon pink) and INSANE
 * DROP! (shown in red) -- and the item's live price is appended in gold via the same lookup
 * /testdrop uses.
 *
 * Message shapes handled (per SkyHanni's captured-message corpus, matched after stripping
 * formatting codes since the bold color prefix varies by tier):
 * "CRAZY RARE DROP!  (Pocket Espresso Machine) (+220% ✯ Magic Find)" -- parenthesized, note the
 * double space; "INSANE DROP! Necron's Handle (+78% ✯ Magic Find)" -- plain; both with optional
 * "Nx " stack counts and an optional "(+...)" suffix (magic find / pet luck / coins).
 */
final class DropAnnouncer {

    private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
    private static final Pattern DROP_PATTERN = Pattern.compile(
            "(?<tier>CRAZY RARE|INSANE) DROP!\\s+\\(?(?:(?<count>\\d+)x )?(?<item>[^()]+?)\\)?"
                    + "(?:\\s*\\(\\+[^)]*\\))?\\s*$");

    private DropAnnouncer() {
    }

    static void onMessage(String rawText) {
        if (!NexoraHpConfig.dropAnnouncementsEnabled) {
            return;
        }

        Matcher matcher = DROP_PATTERN.matcher(FORMATTING_CODE.matcher(rawText).replaceAll(""));
        if (!matcher.find()) {
            return;
        }

        boolean insane = "INSANE".equals(matcher.group("tier"));
        String itemName = matcher.group("item").trim();
        if (itemName.isEmpty()) {
            return;
        }
        int count = matcher.group("count") != null ? Integer.parseInt(matcher.group("count")) : 1;
        String displayName = count > 1 ? count + "x " + itemName : itemName;

        Minecraft client = Minecraft.getInstance();
        Prices.lookup(itemName).thenAccept(quote -> client.execute(() -> {
            if (quote.isPresent()) {
                String price = Prices.formatCoins(quote.get().price() * count);
                TitleOverlay.show(List.of(
                        colored(insane, displayName + " "),
                        TitleOverlay.Segment.gold("(" + price + ")")));
            } else {
                TitleOverlay.show(List.of(colored(insane, displayName)));
            }
        }));
    }

    private static TitleOverlay.Segment colored(boolean insane, String text) {
        return insane ? TitleOverlay.Segment.red(text) : TitleOverlay.Segment.pink(text);
    }
}
