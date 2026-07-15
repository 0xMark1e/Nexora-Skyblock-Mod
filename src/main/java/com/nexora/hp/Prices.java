package com.nexora.hp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item price lookups, entirely off-thread (plain HTTPS to public APIs -- no game packets):
 *
 * <ul>
 * <li>Bazaar: Hypixel's official keyless endpoint, fetched in bulk and cached for a few minutes.
 * Values use quick_status.sellPrice -- what you'd actually get insta-selling the drop.</li>
 * <li>Auction house: Coflnet's per-item lowest-BIN endpoint as the fallback for anything not on
 * bazaar (verified: bazaar-only items like Chimera books return 0 there, AH items like
 * JUDGEMENT_CORE return the live lowest BIN).</li>
 * </ul>
 *
 * Names are resolved to Skyblock item IDs the same way the rest of the mod thinks: normalized
 * upper-snake ("judgement core" -> JUDGEMENT_CORE), with enchant-book variants tried for bazaar
 * ("chimera" -> ENCHANTMENT_ULTIMATE_CHIMERA_1 -- tier 1, which is what actually drops).
 *
 * Uses blocking HttpURLConnection on a small daemon pool rather than java.net.http.HttpClient:
 * HttpClient's selector needs an internal loopback pipe that this machine's JDK setup refuses to
 * create ("Unable to establish loopback connection"), while plain sockets work everywhere.
 */
final class Prices {

    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BIN_URL = "https://sky.coflnet.com/api/item/price/%s/bin";
    private static final long BAZAAR_REFRESH_MILLIS = 5 * 60 * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "nexora-prices");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Map<String, Double> bazaarSellPrices = Map.of();
    private static volatile long bazaarFetchedAt = 0L;
    private static volatile boolean bazaarRefreshInFlight = false;

    record Quote(String itemId, double price, String source) {
    }

    private Prices() {
    }

    /** Kicks an async bazaar refresh if the cache is older than the refresh window. */
    static void refreshBazaarIfStale() {
        if (bazaarRefreshInFlight || System.currentTimeMillis() - bazaarFetchedAt < BAZAAR_REFRESH_MILLIS) {
            return;
        }
        bazaarRefreshInFlight = true;

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject products = JsonParser.parseString(httpGet(BAZAAR_URL))
                        .getAsJsonObject().getAsJsonObject("products");
                Map<String, Double> prices = new HashMap<>();
                for (var entry : products.entrySet()) {
                    JsonObject quickStatus = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                    if (quickStatus != null && quickStatus.has("sellPrice")) {
                        prices.put(entry.getKey(), quickStatus.get("sellPrice").getAsDouble());
                    }
                }
                bazaarSellPrices = prices;
                bazaarFetchedAt = System.currentTimeMillis();
            } catch (Exception ignored) {
                // Network hiccup: keep whatever data we had; the next lookup retries.
            } finally {
                bazaarRefreshInFlight = false;
            }
        }, EXECUTOR);
    }

    /**
     * Resolves an item name to a price: bazaar cache first, then Coflnet lowest BIN. Completes on
     * a worker thread -- hop back to the client thread (Minecraft.execute) before touching game
     * state with the result.
     */
    static CompletableFuture<Optional<Quote>> lookup(String name) {
        refreshBazaarIfStale();
        List<String> candidates = candidateIds(name);

        Map<String, Double> bazaar = bazaarSellPrices;
        for (String candidate : candidates) {
            String bazaarId = resolveBazaarId(bazaar, candidate);
            if (bazaarId != null) {
                return CompletableFuture.completedFuture(
                        Optional.of(new Quote(bazaarId, bazaar.get(bazaarId), "Bazaar insta-sell")));
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            for (String candidate : candidates) {
                try {
                    JsonObject body = JsonParser.parseString(httpGet(LOWEST_BIN_URL.formatted(candidate)))
                            .getAsJsonObject();
                    double lowest = body.has("lowest") ? body.get("lowest").getAsDouble() : 0;
                    if (lowest > 0) {
                        return Optional.of(new Quote(candidate, lowest, "AH lowest BIN"));
                    }
                } catch (Exception ignored) {
                    // Not found or network hiccup for this candidate -- try the next one.
                }
            }
            return Optional.empty();
        }, EXECUTOR);
    }

    /**
     * Candidate item IDs for a typed name. Hypixel is inconsistent about possessives -- "Necron's
     * Handle" is NECRON_HANDLE (the 's dropped) but "Giant's Sword" is GIANTS_SWORD (the s kept)
     * -- so both variants are tried; for names without apostrophes this is a single candidate.
     */
    private static List<String> candidateIds(String name) {
        String upper = name.trim().toUpperCase(Locale.ROOT);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(sanitize(upper.replace("'S", "S")));
        candidates.add(sanitize(upper.replace("'S", "")));
        candidates.add(sanitize(upper));
        return List.copyOf(candidates);
    }

    /** Upper-snake with anything that can't appear in a Skyblock ID stripped. */
    private static String sanitize(String upper) {
        return upper.replace(' ', '_').replaceAll("[^A-Z0-9_]", "");
    }

    private static String resolveBazaarId(Map<String, Double> bazaar, String normalized) {
        if (bazaar.containsKey(normalized)) {
            return normalized;
        }
        String[] enchantVariants = {
                "ENCHANTMENT_ULTIMATE_" + normalized + "_1",
                "ENCHANTMENT_" + normalized + "_1",
        };
        for (String variant : enchantVariants) {
            if (bazaar.containsKey(variant)) {
                return variant;
            }
        }
        // Last resort: any product containing the name. String-sorted so tier 1 books win over
        // higher tiers (..._1 sorts before ..._2).
        return bazaar.keySet().stream()
                .filter(key -> key.contains(normalized))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static final Pattern ENCHANTMENT_ID = Pattern.compile("ENCHANTMENT_(?:ULTIMATE_)?([A-Z_]+)_(\\d+)");
    private static final String[] ROMAN_NUMERALS = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /**
     * Display name preferring what the player typed when it fully described the item -- the ID
     * loses punctuation ("NECRON_HANDLE" would render as "Necron Handle"), so if the resolved ID
     * is one of the typed name's own candidates, title-case the typed name (keeping its
     * apostrophes) instead. Falls back to {@link #displayName(String)} when the ID says more than
     * the typed name did (e.g. "chimera" resolving to a tier-1 enchantment book).
     */
    static String displayName(String typedName, String itemId) {
        if (candidateIds(typedName).contains(itemId)) {
            return titleCase(typedName.trim());
        }
        return displayName(itemId);
    }

    /**
     * Pretty display name for an item ID: enchantment books become "Name Tier" with a Roman
     * numeral tier ("ENCHANTMENT_ULTIMATE_CHIMERA_1" -> "Chimera I"), everything else is just
     * title-cased ("JUDGEMENT_CORE" -> "Judgement Core").
     */
    static String displayName(String itemId) {
        Matcher matcher = ENCHANTMENT_ID.matcher(itemId);
        if (matcher.matches()) {
            int tier = Integer.parseInt(matcher.group(2));
            String numeral = tier >= 1 && tier <= ROMAN_NUMERALS.length
                    ? ROMAN_NUMERALS[tier - 1]
                    : String.valueOf(tier);
            return titleCase(matcher.group(1).replace('_', ' ')) + " " + numeral;
        }
        return titleCase(itemId.replace('_', ' '));
    }

    private static String titleCase(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean startOfWord = true;
        for (char c : text.toCharArray()) {
            sb.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = c == ' ';
        }
        return sb.toString();
    }

    /** "142.0m" style: billions with 2 decimals, millions with 1, thousands whole, else raw. */
    static String formatCoins(double price) {
        if (price >= 1_000_000_000) {
            return String.format("%.2fb", price / 1_000_000_000);
        }
        if (price >= 1_000_000) {
            return String.format("%.1fm", price / 1_000_000);
        }
        if (price >= 1_000) {
            return String.format("%.0fk", price / 1_000);
        }
        return String.format("%.0f", price);
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("Accept", "application/json");
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
