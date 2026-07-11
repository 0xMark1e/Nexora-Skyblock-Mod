package com.nexora.hp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class NexoraHpMod implements ClientModInitializer {

    // KeyMapping has no public getter for its currently bound key, so we read
    // the "key" field reflectively to simulate a press of whatever key/button
    // the player has it bound to (instead of hardcoding a keycode).
    private static final Field KEY_MAPPING_KEY_FIELD;

    static {
        try {
            KEY_MAPPING_KEY_FIELD = KeyMapping.class.getDeclaredField("key");
            KEY_MAPPING_KEY_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static InputConstants.Key boundKey(KeyMapping mapping) {
        try {
            return (InputConstants.Key) KEY_MAPPING_KEY_FIELD.get(mapping);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean needHeal = false;
    private static boolean canHeal = true;
    private static long cooldownEndsAt = 0L;
    private static long cooldownDurationMillis = 0L;
    private static boolean releasePending = false;
    private static int originalSlot = -1;
    private static int wandSlot = -1;

    // Panic heal: spams the "Instant Heal" sword's charges until the server tells us we're out,
    // then waits until the reported recharge time before it's willing to try again.
    private static final String SWORD_ID = "FLORID_ZOMBIE_SWORD";
    private static final int PANIC_RETRY_DELAY_TICKS = 3;
    private static final Pattern NO_CHARGES_PATTERN = Pattern.compile("next one in ([0-9.]+)s");

    private static int swordSlot = -1;
    private static boolean panicActive = false;
    private static boolean panicReleasePending = false;
    private static boolean panicRestorePending = false;
    private static int panicRetryDelayTicks = 0;
    private static int panicOriginalSlot = -1;
    private static boolean panicCanHeal = true;
    private static long panicCooldownEndsAt = 0L;
    private static long panicCooldownDurationMillis = 0L;
    private static volatile Double panicDepletedCooldownSeconds = null;

    // Blaze Slayer Hellion Shield: the boss (and its split demons) carry an invisible armor
    // stand whose nametag directly names the currently-required dagger attunement, or "IMMUNE"
    // while a split demon can't be damaged yet. Scanning for that nametag is how Skyblocker's
    // own attunement highlighter works too -- there's no packet/NBT field for it otherwise.
    private static final Pattern ATTUNEMENT_PATTERN = Pattern.compile("ASHEN|SPIRIT|CRYSTAL|AURIC|IMMUNE");
    private static final double ATTUNEMENT_SCAN_RADIUS = 30.0;

    private static String currentAttunement = null;

    // Auto-attunement: the two Blaze Slayer daggers each toggle between two forms on right-click.
    // The form is signaled by a "td_attune_mode" NBT tag (0-3, see AttunementController for the
    // mapping) -- the item's vanilla material was documented as swapping between Stone/Golden/
    // Iron/Diamond Sword to indicate it, but that turned out to be stale; the material never
    // actually changes anymore, confirmed via item dumps (now /dumpitem). So we switch to whichever dagger
    // the current attunement calls for, then tap right-click (a toggle, not a hold) if its tag
    // doesn't already match. The decision logic itself lives in AttunementController, which is
    // unit-tested and simulation-tested independently of Minecraft (see AttunementControllerTest)
    // -- this class only gathers game state into an Input and turns the resulting IO calls into
    // real key presses.
    private static final String FIRE_DAGGER_ID = "HEARTFIRE_DAGGER";
    private static final String TWILIGHT_DAGGER_ID = "HEARTMAW_DAGGER";

    private static final AttunementController attunementController = new AttunementController();
    private static int fireDaggerSlot = -1;
    private static int twilightDaggerSlot = -1;

    // Auto Soulcry: the three Voidgloom katanas share a right-click ability ("Soulcry", 4s
    // duration, 4s cooldown) that should stay up for the whole boss fight. The item's data does
    // not change at all while it's active (confirmed via /dumpitem component dumps taken in
    // both states), so there is no state to read -- instead, while a Voidgloom Seraph nametag is
    // nearby and a katana is held, tap right-click once a second: taps during the active window
    // are rejected by the server at no cost, and the first tap after expiry re-activates it.
    private static final Set<String> KATANA_IDS = Set.of("VOIDEDGE_KATANA", "VOIDWALKER_KATANA", "VORPAL_KATANA");
    private static final String VOIDGLOOM_BOSS_NAME = "Voidgloom Seraph";
    private static final double VOIDGLOOM_SCAN_RADIUS = 30.0;
    private static final int SOULCRY_RETRY_DELAY_TICKS = 20;
    private static int soulcryDelayTicks = 0;
    private static boolean soulcryReleasePending = false;
    private static boolean soulcryBossNearby = false;
    private static boolean holdingKatana = false;

    // Auto-cake: gifted cakes show up as an invisible armor stand wearing the cake slice as its
    // head equipment, stacked with a "From: <sender>" nametag and, for whichever cake is actually
    // addressed to you, a "CLICK TO EAT" nametag in place of the usual "To: <name>" one (confirmed
    // via entity dumps (now /dumpentities) -- other players' pending cakes show "To: <name>" instead, so Hypixel already
    // filters this server-side and no username matching is needed). Spams attack+use taps while
    // one's in range, same "click until it's gone" approach as panic heal.
    private static final String CLICK_TO_EAT_TEXT = "CLICK TO EAT";
    private static final double CAKE_SCAN_RADIUS = 4.0;
    private static final int CAKE_RETRY_DELAY_TICKS = 5;
    private static final float CAKE_TURN_SPEED_DEG = 12.0f;
    private static final float CAKE_AIM_TOLERANCE_DEG = 4.0f;
    private static int cakeRetryDelayTicks = 0;

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
            NexoraDebugCommands.register(dispatcher);
        });

        ClientTickEvents.END_CLIENT_TICK.register(NexoraHpMod::onClientTick);

        // The item only tells us "no charges left" via a chat message (there's no NBT/component
        // field for remaining charges), so that's what drives the panic-heal cooldown.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message.getString(), overlay));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, type, receptionTimestamp) ->
                onChatMessage(message.getString(), false));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-heal", "heal_indicator"),
                (graphics, deltaTracker) -> renderHealIndicator(graphics));
    }

    private static void onChatMessage(String text, boolean actionBar) {
        NexoraDebugCommands.onMessage(text, actionBar);

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

        // Release the simulated right-click, then switch back to whatever slot we were on before healing.
        if (releasePending) {
            releasePending = false;
            releaseUseAndRestoreSlot(client, originalSlot);
            originalSlot = -1;
        }

        // Unconditional for the same reason as attunement's flushRelease: if a heal or panic
        // claims the next tick, a pending Soulcry release must still happen or the use-key stays
        // held and corrupts whatever that subsystem does with it.
        if (soulcryReleasePending) {
            soulcryReleasePending = false;
            KeyMapping.set(boundKey(client.options.keyUse), false);
        }

        tickPanic(client, player);
        scanAttunement(client, player);
        tickAutoCake(client, player);

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

        wandSlot = findWandSlot(player.getInventory());

        // Always let a pending attunement tap release on schedule, even on ticks where a heal is
        // about to claim the use-key -- otherwise a heal firing right after a tap orphans that
        // release, and the heal's own key handling can corrupt the in-flight dagger toggle
        // (lands on the wrong mode) or desync our retry bookkeeping from the real key state
        // (looks like spam/flicker). Only *starting new* attunement actions is gated below.
        attunementController.flushRelease(attunementIo(client));

        // Never interrupt a held Ragnarock -- wait until the player switches away from it
        // themselves before switching to the heal item. Also don't fight the panic sequence
        // over which hotbar slot is selected while it's mid-flight.
        boolean willHeal = needHeal && canHeal && wandSlot >= 0 && !panicActive
                && !(NexoraHpConfig.avoidRagnarock && isHoldingRagnarock(player));
        if (willHeal) {
            triggerHeal(client, player, now, wandSlot);
        } else {
            // Only lets the attunement switcher and Soulcry touch the hotbar/use-key when nothing
            // more important (panic heal, wand heal) is about to claim them this tick. The two
            // can't collide with each other: attunement only acts around Blaze bosses holding
            // daggers, Soulcry only around Voidgloom bosses holding katanas.
            tickAttunementSwitch(client, player);
            tickAutoSoulcry(client, player);
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
            KeyMapping.set(boundKey(client.options.keyUse), false);
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
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[panicOriginalSlot]));
            }
            panicOriginalSlot = -1;
        }

        if (!panicCanHeal && now >= panicCooldownEndsAt) {
            panicCanHeal = true;
        }

        swordSlot = findSwordSlot(player.getInventory());

        if (!NexoraHpConfig.enabled || !NexoraHpConfig.panicEnabled) {
            return;
        }

        float maxHp = player.getMaxHealth();
        boolean needPanic = maxHp > 0f && player.getHealth() < maxHp * (NexoraHpConfig.panicThresholdPercent / 100f);

        if (!panicActive && needPanic && panicCanHeal && swordSlot >= 0) {
            int selectedSlot = player.getInventory().getSelectedSlot();
            if (selectedSlot != swordSlot) {
                panicOriginalSlot = selectedSlot;
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[swordSlot]));
            }
            panicActive = true;
            KeyMapping.set(boundKey(client.options.keyUse), true);
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
                KeyMapping.set(boundKey(client.options.keyUse), true);
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

        AABB box = player.getBoundingBox().inflate(ATTUNEMENT_SCAN_RADIUS);
        List<ArmorStand> stands = client.level.getEntities(EntityTypeTest.forClass(ArmorStand.class), box,
                ArmorStand::hasCustomName);

        List<AttunementController.Reading> readings = new ArrayList<>();
        for (ArmorStand stand : stands) {
            Matcher matcher = ATTUNEMENT_PATTERN.matcher(stand.getCustomName().getString());
            if (matcher.find()) {
                readings.add(new AttunementController.Reading(matcher.group(), stand.distanceToSqr(player)));
            }
        }

        String detected = AttunementController.selectAttunement(readings);
        currentAttunement = attunementController.debounceAttunement(detected);
    }

    /**
     * Auto-cake: while a "CLICK TO EAT" armor stand is nearby, turns the view towards it and taps
     * attack (left click) every few ticks -- exactly what a player would do by hand -- until it's gone
     * (collected) or out of range. No target *selection* logic needed: the stand only shows this
     * text to the actual recipient and only while standing right next to it, so proximity alone
     * identifies it -- but the click still only lands if the crosshair is actually on it, hence
     * turnTowards. Skipped during panic heal so the two don't fight over the use-key on the same tick.
     */
    private static void tickAutoCake(Minecraft client, LocalPlayer player) {
        if (!NexoraHpConfig.autoCakeEnabled || panicActive) {
            cakeRetryDelayTicks = 0;
            return;
        }

        AABB box = player.getBoundingBox().inflate(CAKE_SCAN_RADIUS);
        List<ArmorStand> stands = client.level.getEntities(EntityTypeTest.forClass(ArmorStand.class), box,
                stand -> stand.hasCustomName() && CLICK_TO_EAT_TEXT.equals(stand.getCustomName().getString()));

        if (stands.isEmpty()) {
            cakeRetryDelayTicks = 0;
            return;
        }

        if (!turnTowards(player, stands.get(0).getEyePosition())) {
            return;
        }

        if (cakeRetryDelayTicks > 0) {
            cakeRetryDelayTicks--;
            return;
        }

        KeyMapping.click(boundKey(client.options.keyAttack));
        cakeRetryDelayTicks = CAKE_RETRY_DELAY_TICKS;
    }

    /**
     * Turns the player's view a bounded number of degrees per tick toward the given point, like a
     * player glancing over at it, rather than snapping instantly (which both looks broken and can
     * still miss the interact raycast on the tick it fires if the snap overshoots). Returns true
     * once the view is close enough that the click below will actually land.
     */
    private static boolean turnTowards(LocalPlayer player, Vec3 target) {
        float fromYaw = player.getYRot();
        float fromPitch = player.getXRot();

        // Borrow vanilla's own look-at math (the same used by e.g. "/execute facing") instead of
        // re-deriving the yaw/pitch trig ourselves: point there, read back the angles it computed,
        // then undo it so the bounded turn below is applied deliberately instead of snapping.
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        float targetYaw = player.getYRot();
        float targetPitch = player.getXRot();
        player.setYRot(fromYaw);
        player.setXRot(fromPitch);

        float newYaw = Mth.approachDegrees(fromYaw, targetYaw, CAKE_TURN_SPEED_DEG);
        float newPitch = Mth.approachDegrees(fromPitch, targetPitch, CAKE_TURN_SPEED_DEG);
        player.setYRot(newYaw);
        player.setXRot(newPitch);

        return Mth.degreesDifferenceAbs(newYaw, targetYaw) <= CAKE_AIM_TOLERANCE_DEG
                && Mth.degreesDifferenceAbs(newPitch, targetPitch) <= CAKE_AIM_TOLERANCE_DEG;
    }

    /**
     * Auto Soulcry: while a Voidgloom Seraph is up and a katana is held, taps right-click once a
     * second so the ability re-activates the moment its 4s duration/cooldown lets it -- see the
     * KATANA_IDS comment for why this is timer-driven instead of state-driven. The release is
     * flushed unconditionally at the top of onClientTick, not here, so a heal/panic tick can't
     * orphan it.
     */
    private static void tickAutoSoulcry(Minecraft client, LocalPlayer player) {
        soulcryBossNearby = false;
        holdingKatana = KATANA_IDS.contains(extraAttributesId(player.getInventory().getSelectedItem()));

        if (!NexoraHpConfig.autoSoulcryEnabled || panicActive) {
            soulcryDelayTicks = 0;
            return;
        }

        // The boss scan runs before the held-item gate (not after, which would be cheaper) so
        // the HUD can show "you're at the boss but not holding a katana" as its own state.
        AABB box = player.getBoundingBox().inflate(VOIDGLOOM_SCAN_RADIUS);
        List<ArmorStand> stands = client.level.getEntities(EntityTypeTest.forClass(ArmorStand.class), box,
                stand -> stand.hasCustomName()
                        && stand.getCustomName().getString().contains(VOIDGLOOM_BOSS_NAME));
        soulcryBossNearby = !stands.isEmpty();
        if (!soulcryBossNearby || !holdingKatana) {
            soulcryDelayTicks = 0;
            return;
        }

        if (soulcryDelayTicks > 0) {
            soulcryDelayTicks--;
            return;
        }

        KeyMapping.set(boundKey(client.options.keyUse), true);
        soulcryReleasePending = true;
        soulcryDelayTicks = SOULCRY_RETRY_DELAY_TICKS;
    }

    /**
     * Gathers game state into an AttunementController.Input and turns the resulting IO calls into
     * real key presses. All the actual decision-making (when to switch, when to retry, when to
     * back off) lives in AttunementController -- see AttunementControllerTest for its coverage.
     */
    private static void tickAttunementSwitch(Minecraft client, LocalPlayer player) {
        attunementController.setConfirmWindowMillis(NexoraHpConfig.attunementSwitchDelayMillis);

        fireDaggerSlot = findItemSlot(player.getInventory(), FIRE_DAGGER_ID);
        twilightDaggerSlot = findItemSlot(player.getInventory(), TWILIGHT_DAGGER_ID);

        String heldMode = daggerModeAttunement(player.getInventory().getSelectedItem());

        AttunementController.Input input = new AttunementController.Input(
                NexoraHpConfig.enabled,
                NexoraHpConfig.autoAttunementEnabled,
                NexoraHpConfig.avoidRagnarock,
                isHoldingRagnarock(player),
                panicActive,
                currentAttunement,
                fireDaggerSlot,
                twilightDaggerSlot,
                player.getInventory().getSelectedSlot(),
                heldMode,
                client.options.keyAttack.isDown(),
                System.currentTimeMillis());

        attunementController.tick(input, attunementIo(client));
    }

    private static AttunementController.IO attunementIo(Minecraft client) {
        return new AttunementController.IO() {
            @Override
            public void releaseUseKey() {
                KeyMapping.set(boundKey(client.options.keyUse), false);
            }

            @Override
            public void switchToSlot(int slot) {
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[slot]));
            }

            @Override
            public void tapUseKey() {
                KeyMapping.set(boundKey(client.options.keyUse), true);
            }
        };
    }

    /**
     * Reads the dagger's current toggle form off its "td_attune_mode" NBT tag. This used to read
     * the item's vanilla material (documented as swapping between Stone/Golden/Iron/Diamond
     * Sword), but item dumps (now /dumpitem) proved that's stale -- the material never actually changes
     * anymore, and Hypixel signals the mode through this dedicated tag instead.
     */
    private static String daggerModeAttunement(ItemStack stack) {
        CustomData customData = stack.getComponents().get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("td_attune_mode")) {
            return null;
        }
        return AttunementController.daggerModeAttunement(tag.getIntOr("td_attune_mode", -1));
    }

    private static int findItemSlot(Inventory inventory, String id) {
        for (int slot = 0; slot < 9; slot++) {
            if (id.equals(extraAttributesId(inventory.getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    /** Exact-ID match: unlike the wand, there's only the one "Instant Heal" sword variant so far. */
    private static int findSwordSlot(Inventory inventory) {
        return findItemSlot(inventory, SWORD_ID);
    }

    private static final String RAGNAROCK_ID = "RAGNAROCK_AXE";
    private static final String WAND_ID_PREFIX = "WAND_OF_";

    private static boolean isHoldingRagnarock(LocalPlayer player) {
        return RAGNAROCK_ID.equals(extraAttributesId(player.getInventory().getSelectedItem()));
    }

    /**
     * Scans the hotbar (slots 0-8) for an item whose internal ID starts with "WAND_OF_" --
     * there are several wand variants (Wand of Restoration, Wand of Mending, ...) that all
     * share this prefix, so it's a prefix match rather than an exact one. This replaces the
     * old fixed-slot config: the wand can now sit in whatever hotbar slot the player likes.
     */
    private static int findWandSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            String id = extraAttributesId(inventory.getItem(slot));
            if (id.startsWith(WAND_ID_PREFIX)) {
                return slot;
            }
        }
        return -1;
    }

    /** Releases the simulated right-click and, if we switched slots to heal, switches back. */
    private static void releaseUseAndRestoreSlot(Minecraft client, int originalSlot) {
        KeyMapping.set(boundKey(client.options.keyUse), false);
        if (originalSlot >= 0) {
            KeyMapping.click(boundKey(client.options.keyHotbarSlots[originalSlot]));
        }
    }

    private static void triggerHeal(Minecraft client, LocalPlayer player, long now, int healSlotIndex) {
        int selectedSlot = player.getInventory().getSelectedSlot();

        // Simulate pressing the heal-item hotbar key, then the use-item (right click) key.
        if (selectedSlot != healSlotIndex) {
            originalSlot = selectedSlot;
            KeyMapping.click(boundKey(client.options.keyHotbarSlots[healSlotIndex]));
        }
        KeyMapping.set(boundKey(client.options.keyUse), true);
        releasePending = true;

        canHeal = false;
        cooldownDurationMillis = NexoraHpConfig.cooldownSeconds * 1000L + 500L;
        cooldownEndsAt = now + cooldownDurationMillis;

        if (NexoraHpConfig.soundEnabled) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
        }
    }

    private static final int COLOR_HEADER = 0xFF8A8A9A;
    private static final int COLOR_GREEN = 0xFF5CE65C;
    private static final int COLOR_RED = 0xFFE65C5C;
    private static final int COLOR_GRAY = 0xFF9A9AA5;
    private static final int COLOR_AMBER = 0xFFF2B33D;
    private static final int COLOR_ASHEN = 0xFFC2C2CC;
    private static final int COLOR_SPIRIT = 0xFFF5F5F5;
    private static final int COLOR_CRYSTAL = 0xFF5CE6E6;
    private static final int COLOR_ACCENT = 0xFF5CE6C7;

    /** One row of the HUD: a colored label, and optionally a colored progress bar beneath it. */
    private record HudRow(String label, int color, boolean hasBar, float progress) {
        private static HudRow text(String label, int color) {
            return new HudRow(label, color, false, 0f);
        }

        private static HudRow bar(String label, int color, float progress) {
            return new HudRow(label, color, true, progress);
        }
    }

    private static void renderHealIndicator(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!NexoraHpConfig.hudEnabled || player == null || client.level == null || client.options.hideGui) {
            return;
        }

        boolean enabled = NexoraHpConfig.enabled;
        List<HudRow> rows = new ArrayList<>();

        if (enabled && wandSlot < 0) {
            rows.add(HudRow.bar("NO WAND", COLOR_AMBER, 0f));
        } else {
            rows.add(HudRow.bar(statusLabel(enabled, canHeal, "HEAL", cooldownEndsAt),
                    statusColor(enabled, canHeal, cooldownEndsAt, cooldownDurationMillis),
                    progressFor(enabled, canHeal, cooldownEndsAt, cooldownDurationMillis)));
        }

        if (NexoraHpConfig.panicEnabled) {
            if (enabled && swordSlot < 0) {
                rows.add(HudRow.bar("NO SWORD", COLOR_AMBER, 0f));
            } else if (enabled && panicActive) {
                rows.add(HudRow.bar("PANIC USING", COLOR_GREEN, 1f));
            } else {
                rows.add(HudRow.bar(statusLabel(enabled, panicCanHeal, "PANIC", panicCooldownEndsAt),
                        statusColor(enabled, panicCanHeal, panicCooldownEndsAt, panicCooldownDurationMillis),
                        progressFor(enabled, panicCanHeal, panicCooldownEndsAt, panicCooldownDurationMillis)));
            }
        }

        if (NexoraHpConfig.showAttunement && currentAttunement != null) {
            String label = "IMMUNE".equals(currentAttunement) ? "BOSS IMMUNE" : "ATTUNEMENT: " + currentAttunement;
            rows.add(HudRow.text(label, attunementColor(currentAttunement)));
        }

        if (enabled && NexoraHpConfig.autoAttunementEnabled && currentAttunement != null
                && !"IMMUNE".equals(currentAttunement)) {
            boolean fireFamily = "ASHEN".equals(currentAttunement) || "AURIC".equals(currentAttunement);
            if ((fireFamily ? fireDaggerSlot : twilightDaggerSlot) < 0) {
                rows.add(HudRow.text("NO DAGGER", COLOR_AMBER));
            }
        }

        if (enabled && NexoraHpConfig.autoSoulcryEnabled && soulcryBossNearby) {
            rows.add(holdingKatana
                    ? HudRow.text("SOULCRY: ACTIVE", COLOR_GREEN)
                    : HudRow.text("NO KATANA", COLOR_AMBER));
        }

        float hpRatio = player.getMaxHealth() > 0f ? player.getHealth() / player.getMaxHealth() : 0f;
        int hpColor = lerpColor(COLOR_RED, COLOR_GREEN, Math.max(0f, Math.min(1f, hpRatio)));
        String hpText = Math.round(hpRatio * 100f) + "%";

        Font font = client.font;
        int padding = 6;
        int barHeight = 3;
        int margin = 8;
        int rowGap = 4;
        int ledSize = 4;
        int ledGap = 4;
        int textIndent = ledSize + ledGap;
        // Measure the header with its actual "§l" bold prefix -- bold glyphs render wider than
        // the plain text, so measuring the plain string undersized the box and let the HP% text
        // crowd into the title.
        int headerText = font.width("§lNEXORA");

        int contentWidth = Math.max(headerText + font.width(hpText) + 10, 100);
        for (HudRow row : rows) {
            contentWidth = Math.max(contentWidth, textIndent + font.width(row.label()));
        }
        int boxWidth = contentWidth + padding * 2;

        // Sizing mirrors the exact draw sequence below step for step (same increments, same
        // order) instead of separately counting "elements" and gaps, so the two can never drift
        // out of sync -- that drift previously undercounted a gap and packed rows too tightly.
        int contentHeight = font.lineHeight + 2; // header line + clearance before the divider
        contentHeight += rowGap + barHeight; // divider-to-HP-bar gap, then the HP bar itself
        for (HudRow row : rows) {
            contentHeight += rowGap + font.lineHeight;
            if (row.hasBar()) {
                contentHeight += rowGap + barHeight;
            }
        }
        int boxHeight = padding * 2 + contentHeight;

        boolean right = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.TOP_RIGHT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;
        boolean bottom = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_LEFT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;

        int x1 = right ? graphics.guiWidth() - margin - boxWidth : margin;
        int x2 = x1 + boxWidth;
        int y1 = bottom ? graphics.guiHeight() - margin - boxHeight : margin;
        int y2 = y1 + boxHeight;

        // Panel: dark gradient fill, a faint full outline for structure, and pulsing accent
        // corner brackets on top for a tactical-HUD look.
        graphics.fillGradient(x1, y1, x2, y2, 0xEC12121C, 0xEC08080D);
        graphics.outline(x1, y1, boxWidth, boxHeight, 0x22FFFFFF);

        float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 260.0);
        int bracketColor = (Math.round(pulse * 0xFF) << 24) | (COLOR_ACCENT & 0x00FFFFFF);
        drawCornerBrackets(graphics, x1, y1, x2, y2, Math.min(10, boxWidth / 4), bracketColor);

        int barX1 = x1 + padding;
        int barX2 = x2 - padding;

        int rowY = y1 + padding;
        graphics.text(font, "§lNEXORA", x1 + padding, rowY, COLOR_ACCENT);
        graphics.text(font, hpText, x2 - padding - font.width(hpText), rowY, hpColor);
        rowY += font.lineHeight + 2;
        graphics.fill(barX1, rowY - 1, barX2, rowY, (COLOR_ACCENT & 0x00FFFFFF) | 0x50000000);
        rowY += rowGap;
        rowY = drawSegmentedBar(graphics, barX1, barX2, rowY, barHeight, hpColor, hpRatio);

        for (HudRow row : rows) {
            rowY += rowGap;
            drawLed(graphics, x1 + padding, rowY + 1, ledSize, row.color());
            graphics.text(font, row.label(), x1 + padding + textIndent, rowY, row.color());
            rowY += font.lineHeight;
            if (row.hasBar()) {
                rowY += rowGap;
                rowY = drawSegmentedBar(graphics, barX1, barX2, rowY, barHeight, row.color(), row.progress());
            }
        }
    }

    /** A small square status LED with a soft glow behind it, instead of relying on font glyph coverage. */
    private static void drawLed(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        int glow = (color & 0x00FFFFFF) | 0x40000000;
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, glow);
        graphics.fill(x, y, x + size, y + size, color | 0xFF000000);
    }

    /** Four accent-colored corner brackets instead of a full border -- reads as HUD, not a dialog box. */
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

    private static int attunementColor(String attunement) {
        return switch (attunement) {
            case "ASHEN" -> COLOR_ASHEN;
            case "SPIRIT" -> COLOR_SPIRIT;
            case "CRYSTAL" -> COLOR_CRYSTAL;
            case "AURIC" -> COLOR_AMBER;
            case "IMMUNE" -> COLOR_RED;
            default -> COLOR_GRAY;
        };
    }

    private static String statusLabel(boolean enabled, boolean ready, String name, long cooldownEndsAt) {
        if (!enabled) {
            return name + " OFF";
        }
        if (ready) {
            return name + " READY";
        }
        long remainingMillis = Math.max(0L, cooldownEndsAt - System.currentTimeMillis());
        return String.format("%s IN %.1fs", name, remainingMillis / 1000f);
    }

    private static int statusColor(boolean enabled, boolean ready, long cooldownEndsAt, long cooldownDurationMillis) {
        if (!enabled) {
            return COLOR_GRAY;
        }
        if (ready) {
            return COLOR_GREEN;
        }
        return lerpColor(COLOR_RED, COLOR_GREEN, progressFor(true, false, cooldownEndsAt, cooldownDurationMillis));
    }

    private static float progressFor(boolean enabled, boolean ready, long cooldownEndsAt, long cooldownDurationMillis) {
        if (!enabled) {
            return 0f;
        }
        if (ready) {
            return 1f;
        }
        long remainingMillis = Math.max(0L, cooldownEndsAt - System.currentTimeMillis());
        return cooldownDurationMillis > 0 ? 1f - (remainingMillis / (float) cooldownDurationMillis) : 1f;
    }

    /** A "tech" tick-bar: small lit/unlit blocks instead of one solid fill, sized to the given width. */
    private static int drawSegmentedBar(GuiGraphicsExtractor graphics, int x1, int x2, int y, int height, int color,
            float progress) {
        int width = x2 - x1;
        int segments = Math.max(6, width / 7);
        int gap = 1;
        float segWidth = (width - gap * (segments - 1)) / (float) segments;
        int lit = Math.round(segments * Math.max(0f, Math.min(1f, progress)));

        for (int i = 0; i < segments; i++) {
            int sx1 = x1 + Math.round(i * (segWidth + gap));
            int sx2 = Math.round(x1 + i * (segWidth + gap) + segWidth);
            if (i < lit) {
                boolean tip = i == lit - 1;
                graphics.fill(sx1, y, sx2, y + height, tip ? brighten(color) : color);
            } else {
                graphics.fill(sx1, y, sx2, y + height, 0xFF25252F);
            }
        }
        return y + height;
    }

    private static int brighten(int color) {
        int a = (color >> 24) & 0xFF, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (a << 24) | (Math.min(255, r + 40) << 16) | (Math.min(255, g + 40) << 8) | Math.min(255, b + 40);
    }

    private static int lerpColor(int colorA, int colorB, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aA = (colorA >> 24) & 0xFF, rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int aB = (colorB >> 24) & 0xFF, rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int a = Math.round(aA + (aB - aA) * t);
        int r = Math.round(rA + (rB - rA) * t);
        int g = Math.round(gA + (gB - gA) * t);
        int b = Math.round(bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
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
        String id = extraAttributesId(held);

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

    /** Reads the Skyblock internal item ID (ExtraAttributes.id NBT tag) from an item stack. */
    static String extraAttributesId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }

        CustomData customData = stack.getComponents().get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return "";
        }

        CompoundTag tag = customData.copyTag();

        // Newer Hypixel data stores "id" directly on the custom-data root; older items nest it
        // under "ExtraAttributes" instead. Try the direct key first, then fall back.
        String directId = tag.getStringOr("id", "");
        if (!directId.isEmpty()) {
            return directId;
        }
        return tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
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
