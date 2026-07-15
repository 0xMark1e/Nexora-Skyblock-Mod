package com.nexora.hp;

import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Auto-cake: gifted cakes show up as an invisible armor stand wearing the cake slice as its head
 * equipment, stacked with a "From: <sender>" nametag and, for whichever cake is actually
 * addressed to you, a "CLICK TO EAT" nametag in place of the usual "To: <name>" one (confirmed
 * via entity dumps, now /dumpentities -- other players' pending cakes show "To: <name>" instead,
 * so Hypixel already filters recipiency server-side and no username matching is needed).
 *
 * The whole interaction is deliberately human-shaped rather than mechanical: a randomized
 * reaction delay before the camera starts moving, an eased turn whose speed is proportional to
 * the remaining angle (fast start, slowing as it settles -- how real mouse aim looks) with
 * per-tick speed jitter, a slightly different aim point on the stand for every cake, and jittered
 * time between attack clicks. A constant-speed turn firing perfectly-timed clicks at the exact
 * same pixel every time is what a watching player would read as a macro.
 */
final class AutoCake {

    private static final String CLICK_TO_EAT_TEXT = "CLICK TO EAT";
    private static final double SCAN_RADIUS = 4.0;
    private static final float AIM_TOLERANCE_DEGREES = 3.5f;
    private static final int REACTION_DELAY_MIN_TICKS = 3;
    private static final int REACTION_DELAY_MAX_TICKS = 8;
    private static final int CLICK_DELAY_MIN_TICKS = 4;
    private static final int CLICK_DELAY_MAX_TICKS = 8;
    private static final float TURN_EASE_FACTOR = 0.35f;
    private static final float TURN_MIN_SPEED_DEGREES = 2.0f;
    private static final float TURN_MAX_SPEED_DEGREES = 26.0f;

    private static final Random RANDOM = new Random();

    private static int reactionDelayTicks = -1; // -1: no cake currently being tracked
    private static Vec3 aimOffset = Vec3.ZERO;
    private static int clickDelayTicks = 0;

    private AutoCake() {
    }

    static void tick(Minecraft client, LocalPlayer player) {
        if (!NexoraHpConfig.autoCakeEnabled || NexoraHpMod.panicActive) {
            reset();
            return;
        }

        List<ArmorStand> stands = EntityScan.namedArmorStands(client, player, SCAN_RADIUS,
                CLICK_TO_EAT_TEXT::equals);
        if (stands.isEmpty()) {
            reset();
            return;
        }

        // A cake just came into range: roll this cake's "noticing it" pause and its aim point.
        if (reactionDelayTicks == -1) {
            reactionDelayTicks = REACTION_DELAY_MIN_TICKS
                    + RANDOM.nextInt(REACTION_DELAY_MAX_TICKS - REACTION_DELAY_MIN_TICKS + 1);
            aimOffset = new Vec3(
                    (RANDOM.nextDouble() - 0.5) * 0.24,
                    (RANDOM.nextDouble() - 0.5) * 0.16,
                    (RANDOM.nextDouble() - 0.5) * 0.24);
            return;
        }
        if (reactionDelayTicks > 0) {
            reactionDelayTicks--;
            return;
        }

        Vec3 target = stands.get(0).getEyePosition().add(aimOffset);

        // Ease-out turn: speed scales with how far there is left to go, clamped and jittered.
        float remaining = View.angleTo(player, target);
        float speed = Math.max(TURN_MIN_SPEED_DEGREES,
                Math.min(TURN_MAX_SPEED_DEGREES, remaining * TURN_EASE_FACTOR))
                * (0.85f + RANDOM.nextFloat() * 0.3f);
        if (!View.turnTowards(player, target, speed, AIM_TOLERANCE_DEGREES)) {
            return;
        }

        if (clickDelayTicks > 0) {
            clickDelayTicks--;
            return;
        }

        Input.clickAttack(client);
        clickDelayTicks = CLICK_DELAY_MIN_TICKS
                + RANDOM.nextInt(CLICK_DELAY_MAX_TICKS - CLICK_DELAY_MIN_TICKS + 1);
    }

    private static void reset() {
        reactionDelayTicks = -1;
        clickDelayTicks = 0;
    }
}
