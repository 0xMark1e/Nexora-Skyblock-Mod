package com.nexora.hp;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Camera-movement helpers. Rotations are set on the client player exactly as mouse movement
 * would be; vanilla syncs them to the server on its own per-tick schedule, so callers only think
 * in terms of "look here" / "look down" and "put it back".
 */
final class View {

    private View() {
    }

    /**
     * Turns the view a bounded number of degrees per tick toward the given point, like a player
     * glancing over at it, rather than snapping instantly (which both looks broken and can still
     * miss the interact raycast on the tick it fires if the snap overshoots). Returns true once
     * the view is within the given tolerance, i.e. a click aimed at the target will land.
     */
    static boolean turnTowards(LocalPlayer player, Vec3 target, float degreesPerTick, float toleranceDegrees) {
        float fromYaw = player.getYRot();
        float fromPitch = player.getXRot();
        float[] rotations = targetRotations(player, target);

        float newYaw = Mth.approachDegrees(fromYaw, rotations[0], degreesPerTick);
        float newPitch = Mth.approachDegrees(fromPitch, rotations[1], degreesPerTick);
        player.setYRot(newYaw);
        player.setXRot(newPitch);

        return Mth.degreesDifferenceAbs(newYaw, rotations[0]) <= toleranceDegrees
                && Mth.degreesDifferenceAbs(newPitch, rotations[1]) <= toleranceDegrees;
    }

    /**
     * How far (in degrees, largest of the yaw/pitch differences) the view currently is from
     * pointing at the target -- lets callers ease their turn speed by remaining distance.
     */
    static float angleTo(LocalPlayer player, Vec3 target) {
        float[] rotations = targetRotations(player, target);
        return Math.max(Mth.degreesDifferenceAbs(player.getYRot(), rotations[0]),
                Mth.degreesDifferenceAbs(player.getXRot(), rotations[1]));
    }

    /**
     * The yaw/pitch that would point the eyes at the target. Borrows vanilla's own look-at math
     * (the same used by e.g. "/execute facing") instead of re-deriving the trig: point there,
     * read back the angles it computed, then undo it so the caller applies its own bounded turn.
     */
    private static float[] targetRotations(LocalPlayer player, Vec3 target) {
        float fromYaw = player.getYRot();
        float fromPitch = player.getXRot();
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        float targetYaw = player.getYRot();
        float targetPitch = player.getXRot();
        player.setYRot(fromYaw);
        player.setXRot(fromPitch);
        return new float[] {targetYaw, targetPitch};
    }

    /**
     * Snaps the view straight down (e.g. to place something at the player's feet) and returns the
     * pitch the caller must restore afterwards via {@link #setPitch}.
     */
    static float lookDown(LocalPlayer player) {
        float originalPitch = player.getXRot();
        player.setXRot(90f);
        return originalPitch;
    }

    static void setPitch(LocalPlayer player, float pitch) {
        player.setXRot(pitch);
    }
}
