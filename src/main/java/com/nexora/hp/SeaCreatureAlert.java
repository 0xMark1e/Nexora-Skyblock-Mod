package com.nexora.hp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

/**
 * Watches for notable fishing creatures spawning nearby (identified by their nametag, wherever
 * it lives -- on the mob itself or on a hologram armor stand above it). On a new spawn it fires
 * one red {@link TitleOverlay#alert} -- once per creature: while the locator below is showing
 * where it is, there is nothing left to announce, so an entity only re-alerts after a world
 * change resets the ledger. While any tracked creature is up, a locator is drawn mid-screen
 * (name, distance, and which way to turn), a tracer line runs from the crosshair toward the
 * creature's on-screen position, and the mob is kept vanilla-glowing.
 */
final class SeaCreatureAlert {

    /**
     * Creatures the alert knows about, matched as a substring of the nametag text; the settings
     * screen renders one checkbox per entry, and unchecked names are skipped entirely (no alert,
     * no locator, no glow -- see NexoraHpConfig.disabledCreatures). Names verified against
     * SkyHanni's boss list / message corpus, except Puddle Jumper which came from live play.
     * Note "Yeti" can also appear in other players' pet nametags -- if that ever false-alerts,
     * unchecking it in the list is the intended remedy.
     */
    static final List<String> CREATURE_NAMES = List.of(
            "Puddle Jumper", "Thunder", "Lord Jawbus", "Ragnarok", "Plhlegblast",
            "Sea Emperor", "Water Hydra", "Reindrake", "Yeti");

    private static final double GLOW_RADIUS = 4.0;
    private static final long ALERT_COOLDOWN_MILLIS = 3000L;
    private static final int LOCATOR_RED = 0xFF4040;
    private static final int LOCATOR_SHADOW = 0x7A0D0D;

    // Alert ledger: entity ids that already announced. Deliberately never pruned while in the
    // same world (a creature wandering out of scan range and back must not re-alert) -- only a
    // world change clears it, which also handles entity-id reuse across worlds. The cooldown
    // covers multi-line holograms announcing several matching stands for one creature at once.
    private static final Set<Integer> alertedEntityIds = new HashSet<>();
    private static Level lastLevel = null;
    private static long lastAlertAt = 0L;

    // Locator target (nearest tracked creature), read by renderLocator.
    private static String trackedName = null;
    private static Vec3 trackedPos = null;

    private SeaCreatureAlert() {
    }

    static void tick(Minecraft client, LocalPlayer player) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            alertedEntityIds.clear();
        }

        trackedName = null;
        trackedPos = null;
        if (!NexoraHpConfig.seaCreatureAlertEnabled) {
            return;
        }

        // Scan out to render distance: as long as the creature exists client-side at all, it
        // stays tracked. A fixed radius made creatures flicker in and out of "existence" for the
        // locator/glow whenever they crossed the boundary.
        double scanRadius = client.options.getEffectiveRenderDistance() * 16;
        List<Entity> nameHolders = EntityScan.namedEntities(client, player, scanRadius,
                name -> matchedCreature(name) != null);
        if (nameHolders.isEmpty()) {
            return;
        }

        // Creatures that already have an announced nametag stand present: any further stands of
        // the same creature (multi-line holograms, lines appearing a tick late) get absorbed into
        // the ledger without a fresh alert.
        Set<String> announcedCreatures = new HashSet<>();
        for (Entity holder : nameHolders) {
            if (alertedEntityIds.contains(holder.getId())) {
                announcedCreatures.add(matchedCreature(holder.getCustomName().getString()));
            }
        }

        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (Entity holder : nameHolders) {
            String creature = matchedCreature(holder.getCustomName().getString());
            if (!announcedCreatures.contains(creature) && now - lastAlertAt >= ALERT_COOLDOWN_MILLIS) {
                lastAlertAt = now;
                announcedCreatures.add(creature);
                TitleOverlay.alert(creature.toUpperCase() + " SPAWNED!");
            }
            // Marking is tied to the creature being announced, not to this stand having been
            // seen: a *different* creature suppressed by the alert cooldown must stay unmarked
            // so it still alerts once the cooldown lets it -- marking it here would silence it
            // forever.
            if (announcedCreatures.contains(creature)) {
                alertedEntityIds.add(holder.getId());
            }
            glowMobsAround(client, holder);

            double distSq = holder.distanceToSqr(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = holder;
            }
        }

        trackedName = matchedCreature(nearest.getCustomName().getString());
        trackedPos = nearest.position();
    }

    /**
     * Mid-screen locator while a tracked creature is up: "<<< PUDDLE JUMPER 23m" style, with the
     * arrows on whichever side to turn toward (more arrows = bigger turn, ^ when it's ahead),
     * plus a tracer line from the crosshair toward where the creature sits on screen.
     */
    static void renderLocator(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (trackedName == null || trackedPos == null || player == null || client.options.hideGui) {
            return;
        }

        // Aim below the tracked nametag (it's a hologram floating above the body) and measure
        // from the eyes, not the feet -- both otherwise bias the tracer upward, badly at close
        // range.
        Vec3 aimPoint = trackedPos.add(0, -1.25, 0);
        Vec3 toMob = aimPoint.subtract(player.getEyePosition());
        int distance = (int) Math.round(toMob.length());
        double yawToMob = Math.toDegrees(Math.atan2(-toMob.x, toMob.z));
        float relativeYaw = Mth.wrapDegrees((float) (yawToMob - player.getYRot()));

        drawTracer(graphics, client, player, toMob, relativeYaw);

        String label = trackedName.toUpperCase() + " " + distance + "m";
        if (Math.abs(relativeYaw) <= 15f) {
            label = "^ " + label + " ^";
        } else {
            int arrows = 1 + Math.min(3, (int) (Math.abs(relativeYaw) / 45f));
            label = relativeYaw < 0
                    ? "<".repeat(arrows) + " " + label
                    : label + " " + ">".repeat(arrows);
        }

        Font font = client.font;
        String display = "§l" + label;
        float pulse = 0.8f + 0.2f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        int alpha = Math.round(pulse * 255);

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() * 0.36f);
        pose.scale(1.5f);
        int left = -font.width(display) / 2;
        graphics.text(font, display, left + 1, 1, (Math.round(alpha * 0.6f) << 24) | LOCATOR_SHADOW);
        graphics.text(font, display, left, 0, (alpha << 24) | LOCATOR_RED);
        pose.popMatrix();
    }

    /**
     * A line from the crosshair toward the creature's position on screen. The endpoint comes
     * from a perspective projection built out of the yaw/pitch offsets to the mob and the FOV
     * setting (there's no world-render hook in this Fabric version to draw a true 3D line, and
     * this HUD approximation reads the same in practice). Angles are clamped just short of 90°,
     * so a creature behind the camera projects to a huge offset that the screen-bounds clamp
     * turns into "line pointing at the correct screen edge".
     */
    private static void drawTracer(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player,
            Vec3 toMob, float relativeYaw) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double horizontal = Math.sqrt(toMob.x * toMob.x + toMob.z * toMob.z);
        double pitchToMob = -Math.toDegrees(Math.atan2(toMob.y, horizontal));
        float relativePitch = Mth.clamp((float) (pitchToMob - player.getXRot()), -88f, 88f);
        float clampedYaw = Mth.clamp(relativeYaw, -88f, 88f);

        double tanHalfFovY = Math.tan(Math.toRadians(client.options.fov().get() / 2.0));
        double aspect = width / (double) height;
        double xNdc = Math.tan(Math.toRadians(clampedYaw)) / (tanHalfFovY * aspect);
        double yNdc = Math.tan(Math.toRadians(relativePitch)) / tanHalfFovY;

        float endX = Mth.clamp((float) (width / 2.0 + xNdc * width / 2.0), 8f, width - 8f);
        float endY = Mth.clamp((float) (height / 2.0 + yNdc * height / 2.0), 8f, height - 8f);

        float dx = endX - width / 2f;
        float dy = endY - height / 2f;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 6f) {
            return; // crosshair is basically on it -- a stub line would just be noise
        }

        float pulse = 0.65f + 0.2f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        int color = (Math.round(pulse * 255) << 24) | LOCATOR_RED;

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width / 2f, height / 2f);
        pose.rotate((float) Math.atan2(dy, dx));
        graphics.fill(4, -1, Math.round(length), 1, color);
        pose.popMatrix();
    }

    private static String matchedCreature(String nametag) {
        for (String creature : CREATURE_NAMES) {
            if (!NexoraHpConfig.disabledCreatures.contains(creature) && nametag.contains(creature)) {
                return creature;
            }
        }
        return null;
    }

    /**
     * Glows whatever the creature is physically made of. Hypixel builds these model creatures out
     * of stacked block_display entities on one anchor point (confirmed via /dumpentities on the
     * look-alike small frogs: 27 block_displays, no living entity at all -- and their NBT carries
     * glow_color_override, so displays take the outline natively). Both display parts and any
     * real mob get the tag, re-applied every tick so a server metadata update can't quietly
     * switch it back off.
     */
    private static void glowMobsAround(Minecraft client, Entity nameHolder) {
        if (nameHolder instanceof LivingEntity mob && !(nameHolder instanceof ArmorStand)) {
            mob.setGlowingTag(true);
        }
        AABB box = nameHolder.getBoundingBox().inflate(GLOW_RADIUS);
        for (Entity part : client.level.getEntities(nameHolder, box,
                entity -> entity instanceof Display
                        || (entity instanceof LivingEntity && !(entity instanceof Player)
                                && !(entity instanceof ArmorStand)))) {
            part.setGlowingTag(true);
        }
    }
}
