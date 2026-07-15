package com.nexora.hp;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Entry point and per-tick orchestrator. The mod's structure:
 *
 * <ul>
 * <li>{@link Input} / {@link View} -- the only places synthetic key presses and camera turns
 * happen; every feature goes through them.</li>
 * <li>{@link SkyblockItems} / {@link EntityScan} / {@link Sidebar} -- shared game-state readers
 * (item IDs, named armor stands, scoreboard lines).</li>
 * <li>{@link AutoCake}, {@link AutoSoulcry}, {@link AutoDeployable}, {@link SeaCreatureAlert},
 * {@link DropAnnouncer} -- one class per feature, each with its own tick/message hook and (where
 * it holds a key across ticks) an unconditional flush.</li>
 * <li>{@link AttunementController} -- the Blaze dagger decision logic, Minecraft-free and
 * unit-tested; this class only feeds it inputs and executes its IO.</li>
 * <li>{@link Prices} -- async bazaar/AH price lookups (plain HTTPS to public APIs).</li>
 * <li>{@link Hud} / {@link TitleOverlay} -- rendering.</li>
 * <li>{@link NexoraDebugCommands} -- the /dump* and /watch* reverse-engineering toolkit.</li>
 * </ul>
 *
 * This class keeps the two core healing behaviors (auto-heal and panic heal), whose state the
 * HUD reads directly, and decides per tick which subsystem may touch the shared keys: panic heal
 * outranks the wand heal, which outranks everything else.
 */
public class NexoraHpMod implements ClientModInitializer {

    // Auto-heal: below the configured HP%, switch to the wand, right-click it, switch back.
    // Package-private state is read by Hud for the HEAL row.
    private static boolean needHeal = false;
    static boolean canHeal = true;
    static long cooldownEndsAt = 0L;
    static long cooldownDurationMillis = 0L;
    private static boolean releasePending = false;
    private static int originalSlot = -1;
    static int wandSlot = -1;

    // Panic heal: spams the "Instant Heal" sword's charges until the server tells us we're out,
    // then waits until the reported recharge time before it's willing to try again.
    private static final int PANIC_RETRY_DELAY_TICKS = 3;
    private static final Pattern NO_CHARGES_PATTERN = Pattern.compile("next one in ([0-9.]+)s");

    static int swordSlot = -1;
    static boolean panicActive = false;
    private static boolean panicReleasePending = false;
    private static boolean panicRestorePending = false;
    private static int panicRetryDelayTicks = 0;
    private static int panicOriginalSlot = -1;
    static boolean panicCanHeal = true;
    static long panicCooldownEndsAt = 0L;
    static long panicCooldownDurationMillis = 0L;
    private static volatile Double panicDepletedCooldownSeconds = null;

    // Blaze Slayer Hellion Shield: the boss (and its split demons) carry an invisible armor
    // stand whose nametag directly names the currently-required dagger attunement, or "IMMUNE"
    // while a split demon can't be damaged yet. Scanning for that nametag is how Skyblocker's
    // own attunement highlighter works too -- there's no packet/NBT field for it otherwise.
    // The dagger's own toggle state comes from its "td_attune_mode" tag (see SkyblockItems).
    private static final Pattern ATTUNEMENT_PATTERN = Pattern.compile("ASHEN|SPIRIT|CRYSTAL|AURIC|IMMUNE");
    private static final double ATTUNEMENT_SCAN_RADIUS = 30.0;

    private static final AttunementController attunementController = new AttunementController();
    static String currentAttunement = null;
    static int fireDaggerSlot = -1;
    static int twilightDaggerSlot = -1;

    @Override
    public void onInitializeClient() {
        NexoraHpConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) -> {
            dispatcher.register(ClientCommands.literal("showhp")
                    .executes(NexoraHpMod::showHp));
            dispatcher.register(ClientCommands.literal("nexora")
                    .executes(NexoraHpMod::openConfigScreen));
            dispatcher.register(ClientCommands.literal("getid")
                    .executes(NexoraHpMod::getId));
            dispatcher.register(ClientCommands.literal("testtext")
                    .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes(context -> {
                                TitleOverlay.show(StringArgumentType.getString(context, "text"));
                                return 1;
                            })));
            dispatcher.register(ClientCommands.literal("testdrop")
                    .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                            .executes(NexoraHpMod::testDrop)));
            NexoraDebugCommands.register(dispatcher);
        });

        // Prewarm the bazaar price cache so the first drop announcement doesn't wait on a 3MB fetch.
        Prices.refreshBazaarIfStale();

        ClientTickEvents.END_CLIENT_TICK.register(NexoraHpMod::onClientTick);

        // The panic item only tells us "no charges left" via a chat message (there's no
        // NBT/component field for remaining charges), so that's what drives its cooldown.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message.getString(), overlay));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, type, receptionTimestamp) ->
                onChatMessage(message.getString(), false));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-heal", "heal_indicator"),
                (graphics, deltaTracker) -> Hud.render(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-heal", "title_overlay"),
                (graphics, deltaTracker) -> TitleOverlay.render(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-heal", "creature_locator"),
                (graphics, deltaTracker) -> SeaCreatureAlert.renderLocator(graphics));
    }

    private static void onChatMessage(String text, boolean actionBar) {
        NexoraDebugCommands.onMessage(text, actionBar);

        // Drop announcements only ever come as real chat lines; the action bar is per-tick spam.
        if (!actionBar) {
            DropAnnouncer.onMessage(text);
        }

        if (!panicActive || !text.contains("No more charges")) {
            return;
        }
        Matcher matcher = NO_CHARGES_PATTERN.matcher(text);
        panicDepletedCooldownSeconds = matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    private static void onClientTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }

        // Before the screen gate on purpose: debug watches should keep sampling even while chat
        // or a menu is open, since they record evidence rather than producing input.
        NexoraDebugCommands.tick(player);

        // Vanilla only consumes queued hotbar-slot clicks and the use-item key while no screen is
        // open (Minecraft.tick() gates its call to handleKeybinds() on screen == null). Our own
        // per-tick logic isn't gated like that by default, so if we kept advancing our state
        // machine while e.g. the player's inventory or a menu is open, we'd queue a restore click
        // that sits unconsumed until the screen closes. Pausing everything here keeps our
        // synthetic input in lockstep with when the game will actually consume it.
        if (client.screen != null || client.getOverlay() != null) {
            return;
        }

        // Every pending release runs before anything else, no matter which subsystem is about to
        // claim this tick -- an orphaned press or un-restored slot/view corrupts whoever uses the
        // shared keys next (see AttunementController.flushRelease for the original war story).
        if (releasePending) {
            releasePending = false;
            Input.releaseUse(client);
            if (originalSlot >= 0) {
                Input.clickSlot(client, originalSlot);
            }
            originalSlot = -1;
        }
        AutoSoulcry.flushRelease(client);
        AutoDeployable.flushRelease(client, player);

        tickPanic(client, player);
        scanAttunement(client, player);
        AutoCake.tick(client, player);
        SeaCreatureAlert.tick(client, player);

        if (!NexoraHpConfig.enabled) {
            needHeal = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (!canHeal && now >= cooldownEndsAt) {
            canHeal = true;
        }

        float currentHp = player.getHealth();
        float maxHp = player.getMaxHealth();
        needHeal = maxHp > 0f && currentHp < maxHp * (NexoraHpConfig.healThresholdPercent / 100f);

        wandSlot = SkyblockItems.findWandSlot(player.getInventory());

        // Always let a pending attunement tap release on schedule, even on ticks where a heal is
        // about to claim the use-key. Only *starting new* attunement actions is gated below.
        attunementController.flushRelease(attunementIo(client));

        // Never interrupt a held Ragnarock -- wait until the player switches away from it
        // themselves before switching to the heal item. Also don't fight the panic sequence
        // over which hotbar slot is selected while it's mid-flight.
        boolean willHeal = needHeal && canHeal && wandSlot >= 0 && !panicActive
                && !(NexoraHpConfig.avoidRagnarock && SkyblockItems.isRagnarock(player.getInventory().getSelectedItem()));
        if (willHeal) {
            triggerHeal(client, player, now, wandSlot);
        } else {
            // Only lets the lower-priority features touch the hotbar/use-key when nothing more
            // important (panic heal, wand heal) is about to claim them this tick. They can't
            // collide with each other: attunement only acts around Blaze bosses holding daggers,
            // Soulcry only around Voidgloom bosses holding katanas, and the deployable fires
            // exactly once per boss-spawn edge.
            tickAttunementSwitch(client, player);
            AutoSoulcry.tick(client, player);
            AutoDeployable.tick(client, player);
        }
    }

    /**
     * Panic heal: below a (lower) HP%, switch to the "Instant Heal" sword and spam its use key
     * once every few ticks -- exactly like a player mashing right-click -- until the server's own
     * "No more charges, next one in Xs!" message tells us to stop. That message is also the only
     * place the recharge timer is exposed (there's no charges field in the item's data), so it
     * drives the cooldown directly instead of us guessing a duration.
     */
    private static void tickPanic(Minecraft client, LocalPlayer player) {
        long now = System.currentTimeMillis();

        if (panicReleasePending) {
            panicReleasePending = false;
            Input.releaseUse(client);
            panicRetryDelayTicks = PANIC_RETRY_DELAY_TICKS;
        }

        if (panicDepletedCooldownSeconds != null) {
            double seconds = panicDepletedCooldownSeconds;
            panicDepletedCooldownSeconds = null;
            panicActive = false;
            panicCanHeal = false;
            panicCooldownDurationMillis = Math.round(seconds * 1000) + 500L;
            panicCooldownEndsAt = now + panicCooldownDurationMillis;
            panicRestorePending = true;
        }

        if (panicRestorePending) {
            panicRestorePending = false;
            if (panicOriginalSlot >= 0) {
                Input.clickSlot(client, panicOriginalSlot);
            }
            panicOriginalSlot = -1;
        }

        if (!panicCanHeal && now >= panicCooldownEndsAt) {
            panicCanHeal = true;
        }

        swordSlot = SkyblockItems.findSwordSlot(player.getInventory());

        if (!NexoraHpConfig.enabled || !NexoraHpConfig.panicEnabled) {
            return;
        }

        float maxHp = player.getMaxHealth();
        boolean needPanic = maxHp > 0f && player.getHealth() < maxHp * (NexoraHpConfig.panicThresholdPercent / 100f);

        if (!panicActive && needPanic && panicCanHeal && swordSlot >= 0) {
            int selectedSlot = player.getInventory().getSelectedSlot();
            if (selectedSlot != swordSlot) {
                panicOriginalSlot = selectedSlot;
                Input.clickSlot(client, swordSlot);
            }
            panicActive = true;
            Input.pressUse(client);
            panicReleasePending = true;

            if (NexoraHpConfig.soundEnabled) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6F));
            }
        } else if (panicActive && !panicReleasePending) {
            // If the player manually switched off the sword mid-panic, stop spamming right-click
            // on whatever they switched to -- respect the override instead of fighting it.
            if (swordSlot < 0 || player.getInventory().getSelectedSlot() != swordSlot) {
                panicActive = false;
                panicRetryDelayTicks = 0;
                panicOriginalSlot = -1;
            } else if (panicRetryDelayTicks > 0) {
                panicRetryDelayTicks--;
            } else {
                Input.pressUse(client);
                panicReleasePending = true;
            }
        }
    }

    /**
     * Scans nearby armor stands for a Hellion Shield attunement nametag, preferring whichever
     * matching one is physically closest, then debounces the result so a single stray tick (e.g.
     * a stale armor stand not yet despawned during a phase transition) can't masquerade as a real
     * requirement change. During a Demonsplit there can be two demons up with two different
     * armor stands at once (one attunement, one "IMMUNE"); picking arbitrarily between them is
     * what originally made this flicker even when nothing had actually changed.
     */
    private static void scanAttunement(Minecraft client, LocalPlayer player) {
        if (!NexoraHpConfig.showAttunement && !NexoraHpConfig.autoAttunementEnabled) {
            currentAttunement = null;
            return;
        }

        List<AttunementController.Reading> readings = new ArrayList<>();
        for (ArmorStand stand : EntityScan.namedArmorStands(client, player, ATTUNEMENT_SCAN_RADIUS, name -> true)) {
            Matcher matcher = ATTUNEMENT_PATTERN.matcher(stand.getCustomName().getString());
            if (matcher.find()) {
                readings.add(new AttunementController.Reading(matcher.group(), stand.distanceToSqr(player)));
            }
        }

        String detected = AttunementController.selectAttunement(readings);
        currentAttunement = attunementController.debounceAttunement(detected);
    }

    /**
     * Gathers game state into an AttunementController.Input and turns the resulting IO calls into
     * real key presses. All the actual decision-making (when to switch, when to retry, when to
     * back off) lives in AttunementController -- see AttunementControllerTest for its coverage.
     */
    private static void tickAttunementSwitch(Minecraft client, LocalPlayer player) {
        attunementController.setConfirmWindowMillis(NexoraHpConfig.attunementSwitchDelayMillis);

        fireDaggerSlot = SkyblockItems.findSlot(player.getInventory(), SkyblockItems.FIRE_DAGGER_ID);
        twilightDaggerSlot = SkyblockItems.findSlot(player.getInventory(), SkyblockItems.TWILIGHT_DAGGER_ID);

        AttunementController.Input input = new AttunementController.Input(
                NexoraHpConfig.enabled,
                NexoraHpConfig.autoAttunementEnabled,
                NexoraHpConfig.avoidRagnarock,
                SkyblockItems.isRagnarock(player.getInventory().getSelectedItem()),
                panicActive,
                currentAttunement,
                fireDaggerSlot,
                twilightDaggerSlot,
                player.getInventory().getSelectedSlot(),
                SkyblockItems.daggerMode(player.getInventory().getSelectedItem()),
                Input.attackKeyDown(client),
                System.currentTimeMillis());

        attunementController.tick(input, attunementIo(client));
    }

    private static AttunementController.IO attunementIo(Minecraft client) {
        return new AttunementController.IO() {
            @Override
            public void releaseUseKey() {
                Input.releaseUse(client);
            }

            @Override
            public void switchToSlot(int slot) {
                Input.clickSlot(client, slot);
            }

            @Override
            public void tapUseKey() {
                Input.pressUse(client);
            }
        };
    }

    private static void triggerHeal(Minecraft client, LocalPlayer player, long now, int healSlotIndex) {
        int selectedSlot = player.getInventory().getSelectedSlot();

        // Simulate pressing the heal-item hotbar key, then the use-item (right click) key.
        if (selectedSlot != healSlotIndex) {
            originalSlot = selectedSlot;
            Input.clickSlot(client, healSlotIndex);
        }
        Input.pressUse(client);
        releasePending = true;

        canHeal = false;
        cooldownDurationMillis = NexoraHpConfig.cooldownSeconds * 1000L + 500L;
        cooldownEndsAt = now + cooldownDurationMillis;

        if (NexoraHpConfig.soundEnabled) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
        }
    }

    private static int showHp(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();

        float currentHp = player.getHealth();
        float maxHp = player.getMaxHealth();

        context.getSource().sendFeedback(
                Component.literal("[NEXORA] Current HP -> " + formatHp(currentHp))
                        .withStyle(ChatFormatting.RED));
        context.getSource().sendFeedback(
                Component.literal("[NEXORA] Max HP -> " + formatHp(maxHp))
                        .withStyle(ChatFormatting.GREEN));

        return 1;
    }

    private static int getId(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();
        ItemStack held = player.getInventory().getSelectedItem();
        String id = SkyblockItems.id(held);

        if (id.isEmpty()) {
            context.getSource().sendFeedback(
                    Component.literal("[NEXORA] No internal ID found on held item")
                            .withStyle(ChatFormatting.RED));
        } else {
            context.getSource().sendFeedback(
                    Component.literal("[NEXORA] Item ID -> " + id)
                            .withStyle(ChatFormatting.GREEN));
        }

        return 1;
    }

    /**
     * Dry run of the rare-drop announcement: looks the item up on bazaar/AH and flashes the title
     * with its price. The lookup completes on an HTTP thread, so the result hops back onto the
     * client thread before touching the overlay or chat.
     */
    private static int testDrop(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "item");
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();

        Prices.lookup(name).thenAccept(quote -> client.execute(() -> {
            if (quote.isPresent()) {
                TitleOverlay.showDrop(Prices.displayName(name, quote.get().itemId()),
                        Prices.formatCoins(quote.get().price()));
                source.sendFeedback(Component.literal(
                        "[NEXORA] " + quote.get().itemId() + " -> " + String.format("%,.0f", quote.get().price())
                                + " coins (" + quote.get().source() + ")")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                TitleOverlay.show(Prices.displayName(name));
                source.sendFeedback(Component.literal(
                        "[NEXORA] No bazaar or AH price found for \"" + name + "\"")
                        .withStyle(ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int openConfigScreen(CommandContext<FabricClientCommandSource> context) {
        // The chat screen closes itself (setScreen(null)) right after a command is sent,
        // which would immediately wipe out our screen if we set it synchronously here.
        // Deferring to the next client tick lets that close happen first.
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreen(new NexoraHpConfigScreen(null)));
        return 1;
    }

    private static String formatHp(float value) {
        if (value == Math.floor(value)) {
            return String.valueOf((int) value);
        }
        return String.format("%.1f", value);
    }
}
