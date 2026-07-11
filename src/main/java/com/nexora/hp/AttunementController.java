package com.nexora.hp;

import java.util.List;

/**
 * Pure decision logic for the Blaze Slayer auto-attunement switcher, kept free of Minecraft types
 * so it can be unit tested in isolation from the game. NexoraHpMod feeds it a per-tick snapshot of
 * game state and it calls back into an IO implementation for the actual key/slot actions.
 */
public final class AttunementController {

    public static final long ATTACK_GRACE_MILLIS = 600L;
    public static final int TICK_MILLIS = 50;
    public static final int MIN_CONFIRM_WINDOW_MILLIS = 100;
    public static final int MAX_CONFIRM_WINDOW_MILLIS = 1000;
    public static final int DEFAULT_CONFIRM_WINDOW_MILLIS = 1000;
    /** The confirm window in ticks at the default setting -- kept for tests that assumed the old fixed value. */
    public static final int CONFIRM_WINDOW_TICKS = millisToTicks(DEFAULT_CONFIRM_WINDOW_MILLIS);
    public static final int MAX_ATTEMPTS = 3;
    public static final int BACKOFF_TICKS = 40;

    private static int millisToTicks(int millis) {
        return Math.max(1, Math.round(millis / (float) TICK_MILLIS));
    }

    /** Executes the actual key/slot actions the controller decides on. */
    public interface IO {
        void releaseUseKey();

        void switchToSlot(int slot);

        void tapUseKey();
    }

    /** Per-tick snapshot of everything the controller needs to decide what to do. */
    public record Input(
            boolean enabled,
            boolean autoAttunementEnabled,
            boolean avoidRagnarock,
            boolean holdingRagnarock,
            boolean panicActive,
            String currentAttunement,
            int fireDaggerSlot,
            int twilightDaggerSlot,
            int selectedSlot,
            String heldMode,
            boolean attackKeyDown,
            long nowMillis) {
    }

    private boolean releasePending = false;
    private int confirmTicksLeft = 0;
    private int attempts = 0;
    private int backoffTicksLeft = 0;
    private long lastAttackMillis = 0L;
    private int confirmWindowMillis = DEFAULT_CONFIRM_WINDOW_MILLIS;
    // "recentlyAttacking" must be false until a real attack is observed, regardless of what
    // nowMillis happens to start at. Relying on lastAttackMillis's default of 0L for that (i.e.
    // "now - 0 is always > grace window") only works because System.currentTimeMillis() is a huge
    // epoch number in production -- it's correct by accident of magnitude, not by construction,
    // and breaks under any clock that starts near zero (as this class's own tests found).
    private boolean everAttacked = false;

    /** How long to wait after a tap before it's willing to tap again -- see {@link #tick}. */
    public void setConfirmWindowMillis(int millis) {
        this.confirmWindowMillis = Math.max(MIN_CONFIRM_WINDOW_MILLIS, Math.min(MAX_CONFIRM_WINDOW_MILLIS, millis));
    }

    public int getConfirmWindowMillis() {
        return confirmWindowMillis;
    }

    /**
     * Releases a pending tap if one is outstanding. Must be called every tick regardless of
     * whether this tick is actually "attunement's turn" (see {@link #tick}) -- if a heal or panic
     * action preempts a tick right after a tap and this doesn't run, the tap's release gets
     * orphaned until the next full tick(), by which point another subsystem may have already
     * taken over the same physical use-key. That corrupts the toggle (it can land on the wrong
     * mode) and desyncs our retry bookkeeping from the real key state (looks like spam/flicker).
     */
    public void flushRelease(IO io) {
        if (releasePending) {
            releasePending = false;
            io.releaseUseKey();
        }
    }

    public void tick(Input in, IO io) {
        if (in.attackKeyDown()) {
            lastAttackMillis = in.nowMillis();
            everAttacked = true;
        }
        boolean recentlyAttacking = everAttacked && in.nowMillis() - lastAttackMillis <= ATTACK_GRACE_MILLIS;

        flushRelease(io);

        if (!in.enabled() || !in.autoAttunementEnabled()
                || in.currentAttunement() == null || "IMMUNE".equals(in.currentAttunement())) {
            return;
        }

        boolean fireFamily = "ASHEN".equals(in.currentAttunement()) || "AURIC".equals(in.currentAttunement());
        int targetSlot = fireFamily ? in.fireDaggerSlot() : in.twilightDaggerSlot();
        if (targetSlot < 0) {
            return;
        }

        // Checked once here, not just in the slot-switch branch below: panic can activate (and
        // start claiming the same physical use-key for its own emergency heal) on a tick where
        // we're already sitting on the target dagger from an earlier tick, not just when we'd be
        // switching onto it. A tap fired on that same tick would collide with panic's own key use.
        if (in.panicActive()) {
            return;
        }

        if (in.selectedSlot() != targetSlot) {
            if (!recentlyAttacking || (in.avoidRagnarock() && in.holdingRagnarock())) {
                return;
            }
            io.switchToSlot(targetSlot);
            return;
        }

        if (in.heldMode() == null) {
            return;
        }

        if (in.heldMode().equals(in.currentAttunement())) {
            // Correct -- clear any leftover retry state so a stale attempt count doesn't linger
            // into the next mismatch (e.g. from the boss cycling attunement again).
            confirmTicksLeft = 0;
            attempts = 0;
            backoffTicksLeft = 0;
            return;
        }

        if (!recentlyAttacking) {
            return;
        }
        if (backoffTicksLeft > 0) {
            backoffTicksLeft--;
            return;
        }
        if (confirmTicksLeft > 0) {
            confirmTicksLeft--;
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            attempts = 0;
            backoffTicksLeft = BACKOFF_TICKS;
            return;
        }

        io.tapUseKey();
        releasePending = true;
        attempts++;
        confirmTicksLeft = millisToTicks(confirmWindowMillis);
    }

    public int attemptsSoFar() {
        return attempts;
    }

    public int confirmTicksRemaining() {
        return confirmTicksLeft;
    }

    public int backoffTicksRemaining() {
        return backoffTicksLeft;
    }

    public boolean isReleasePending() {
        return releasePending;
    }

    /** Consecutive matching readings required before a new value is trusted -- see {@link #debounceAttunement}. */
    public static final int ATTUNEMENT_STABLE_TICKS = 3;

    private String confirmedAttunement = null;
    private String pendingAttunement = null;
    private int pendingStableTicks = 0;

    /**
     * Filters out single-tick flicker in the raw per-tick attunement reading before trusting it.
     * A stale armor stand that hasn't despawned yet as a phase transitions, or another player's
     * overlapping Demonlord fight, can briefly read as "nearest" for a tick or two -- without this,
     * that one-tick blip looks like a real requirement change and causes a real (wrong) toggle,
     * which then looks like the mod "undoing" a correct switch. Only commits to a new value once
     * it's been seen for ATTUNEMENT_STABLE_TICKS ticks in a row; until then keeps returning
     * whatever was last confirmed.
     */
    public String debounceAttunement(String detected) {
        if (!java.util.Objects.equals(detected, pendingAttunement)) {
            pendingAttunement = detected;
            pendingStableTicks = 0;
        }
        pendingStableTicks++;
        if (pendingStableTicks >= ATTUNEMENT_STABLE_TICKS) {
            confirmedAttunement = detected;
        }
        return confirmedAttunement;
    }

    public record Reading(String word, double distanceSq) {
    }

    /**
     * Picks the closest matching Hellion Shield attunement nametag, preferring a real attunement
     * over "IMMUNE". Distance-based selection (rather than "first found") avoids flicker when two
     * armor stands are up at once during a Demonsplit.
     */
    public static String selectAttunement(List<Reading> readings) {
        String nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        String nearestImmune = null;
        double nearestImmuneDistSq = Double.MAX_VALUE;

        for (Reading r : readings) {
            if (!"IMMUNE".equals(r.word())) {
                if (r.distanceSq() < nearestDistSq) {
                    nearestDistSq = r.distanceSq();
                    nearest = r.word();
                }
            } else if (r.distanceSq() < nearestImmuneDistSq) {
                nearestImmuneDistSq = r.distanceSq();
                nearestImmune = r.word();
            }
        }
        return nearest != null ? nearest : nearestImmune;
    }

    /**
     * Maps a dagger's "td_attune_mode" NBT tag to its Hellion Shield attunement. The item's
     * vanilla material was originally documented as swapping between Stone/Golden/Iron/Diamond
     * Sword to indicate the mode, but that turned out to be stale -- confirmed via item component
     * dumps (now /dumpitem) that the material never actually changes anymore (both HEARTFIRE_DAGGER and
     * HEARTMAW_DAGGER stay on one fixed material regardless of mode); this dedicated integer tag
     * is the real, current signal.
     */
    public static String daggerModeAttunement(int attuneMode) {
        return switch (attuneMode) {
            case 0 -> "ASHEN";
            case 1 -> "AURIC";
            case 2 -> "SPIRIT";
            case 3 -> "CRYSTAL";
            default -> null;
        };
    }
}
