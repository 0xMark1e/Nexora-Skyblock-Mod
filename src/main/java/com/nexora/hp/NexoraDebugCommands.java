package com.nexora.hp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reverse-engineering commands for figuring out how a Hypixel mechanic is actually represented
 * (which is rarely how it looks -- see the daggers' td_attune_mode tag, the cakes' "CLICK TO EAT"
 * armor stand, or the katanas whose "active" state exists nowhere on the item). Every dump is
 * appended to a file under config/nexora-debug/ rather than printed, both because the data is far
 * too large for chat scrollback and so that states captured at different times can be diffed.
 * Chat only gets a one-line confirmation with the file path.
 */
final class NexoraDebugCommands {

    private static final Path DEBUG_DIR = FabricLoader.getInstance().getConfigDir().resolve("nexora-debug");

    private static final int WATCH_ITEM_TICKS = 200;
    private static final int WATCH_CHAT_TICKS = 600;

    private static int watchItemTicksLeft = 0;
    private static int watchItemTickCounter = 0;
    private static String watchItemLastDump = "";
    private static int watchChatTicksLeft = 0;

    private NexoraDebugCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("dumpitem").executes(NexoraDebugCommands::dumpItem));
        dispatcher.register(ClientCommands.literal("watchitem").executes(NexoraDebugCommands::watchItem));
        dispatcher.register(ClientCommands.literal("dumpentities")
                .executes(context -> dumpEntities(context, 8))
                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(context -> dumpEntities(context, IntegerArgumentType.getInteger(context, "radius")))));
        dispatcher.register(ClientCommands.literal("dumpinventory").executes(NexoraDebugCommands::dumpInventory));
        dispatcher.register(ClientCommands.literal("dumpequipment").executes(NexoraDebugCommands::dumpEquipment));
        dispatcher.register(ClientCommands.literal("watchchat").executes(NexoraDebugCommands::watchChat));
        dispatcher.register(ClientCommands.literal("dumpscoreboard").executes(NexoraDebugCommands::dumpScoreboard));
        dispatcher.register(ClientCommands.literal("dumptablist").executes(NexoraDebugCommands::dumpTabList));
        dispatcher.register(ClientCommands.literal("dumptarget").executes(NexoraDebugCommands::dumpTarget));
        dispatcher.register(ClientCommands.literal("dumpeffects").executes(NexoraDebugCommands::dumpEffects));
    }

    /** Called every client tick (even with a screen open -- watches record, they don't act). */
    static void tick(LocalPlayer player) {
        tickItemWatch(player);
        if (watchChatTicksLeft > 0) {
            watchChatTicksLeft--;
            if (watchChatTicksLeft == 0) {
                append("chat.txt", "=== WATCH END ===\n\n");
            }
        }
    }

    /** Fed every chat and action-bar message so active watches can record them as evidence. */
    static void onMessage(String text, boolean actionBar) {
        if (watchChatTicksLeft > 0) {
            append("chat.txt", (actionBar ? "[bar]  " : "[chat] ") + text + "\n");
        }
        if (watchItemTicksLeft > 0) {
            append("items.txt", "[msg] " + text + "\n");
        }
    }

    /** Held item's vanilla ID plus every data component -- run once per item state and diff. */
    private static int dumpItem(CommandContext<FabricClientCommandSource> context) {
        ItemStack held = context.getSource().getPlayer().getInventory().getSelectedItem();
        if (held.isEmpty()) {
            fail(context, "You're not holding anything");
            return 1;
        }
        append("items.txt", "=== " + held.getItem() + " ===\n" + dumpStack(held) + "\n");
        ok(context, "Appended " + held.getItem() + " -> " + DEBUG_DIR.resolve("items.txt"));
        return 1;
    }

    /**
     * Samples the held item every tick for 10s and logs only per-tick diffs, plus any messages
     * received in the window -- catches transient states too brief to capture with /dumpitem.
     */
    private static int watchItem(CommandContext<FabricClientCommandSource> context) {
        watchItemTicksLeft = WATCH_ITEM_TICKS;
        watchItemTickCounter = 0;
        watchItemLastDump = "";
        append("items.txt", "=== ITEM WATCH START ===\n");
        ok(context, "Watching held item for 10s -- trigger the mechanic now");
        return 1;
    }

    private static void tickItemWatch(LocalPlayer player) {
        if (watchItemTicksLeft <= 0) {
            return;
        }
        watchItemTicksLeft--;
        watchItemTickCounter++;

        String dump = dumpStack(player.getInventory().getSelectedItem());
        if (!dump.equals(watchItemLastDump)) {
            // HashSet, not Set.of: duplicate lines in a dump would make Set.of throw, and this
            // runs inside the client tick where an exception takes the whole game down with it.
            Set<String> oldLines = new HashSet<>(List.of(watchItemLastDump.split("\n")));
            Set<String> newLines = new HashSet<>(List.of(dump.split("\n")));
            StringBuilder sb = new StringBuilder("--- tick ").append(watchItemTickCounter).append(" ---\n");
            for (String line : newLines) {
                if (!oldLines.contains(line)) {
                    sb.append("+ ").append(line).append('\n');
                }
            }
            for (String line : oldLines) {
                if (!newLines.contains(line)) {
                    sb.append("- ").append(line).append('\n');
                }
            }
            append("items.txt", sb.toString());
            watchItemLastDump = dump;
        }

        if (watchItemTicksLeft == 0) {
            append("items.txt", "=== ITEM WATCH END ===\n\n");
        }
    }

    /** Every entity within the given radius (players excluded), with custom name and full NBT. */
    private static int dumpEntities(CommandContext<FabricClientCommandSource> context, int radius) {
        LocalPlayer player = context.getSource().getPlayer();
        AABB box = player.getBoundingBox().inflate(radius);
        List<Entity> nearby = player.level().getEntities(player, box, e -> !(e instanceof Player));

        StringBuilder sb = new StringBuilder("=== ").append(nearby.size())
                .append(" entities within ").append(radius).append(" blocks ===\n");
        for (Entity entity : nearby) {
            sb.append(describeEntity(entity)).append('\n');
            sb.append(entityNbt(entity)).append("\n\n");
        }
        append("entities.txt", sb.toString());
        ok(context, "Dumped " + nearby.size() + " entities -> " + DEBUG_DIR.resolve("entities.txt"));
        return 1;
    }

    /** One line per occupied inventory slot: index, vanilla item, count, Skyblock ID, name. */
    private static int dumpInventory(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();
        StringBuilder sb = new StringBuilder("=== INVENTORY ===\n");
        int occupied = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            occupied++;
            String id = NexoraHpMod.extraAttributesId(stack);
            sb.append("slot ").append(slot).append(": ").append(stack.getItem())
                    .append(" x").append(stack.getCount())
                    .append(id.isEmpty() ? "" : " id=" + id)
                    .append(" \"").append(stack.getHoverName().getString()).append("\"\n");
        }
        sb.append('\n');
        append("inventory.txt", sb.toString());
        ok(context, "Dumped " + occupied + " slots -> " + DEBUG_DIR.resolve("inventory.txt"));
        return 1;
    }

    /** Armor and offhand with full components -- set bonuses and abilities live on these. */
    private static int dumpEquipment(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();
        StringBuilder sb = new StringBuilder("=== EQUIPMENT ===\n");
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            sb.append("--- ").append(slot).append(" ---\n");
            sb.append(dumpStack(player.getItemBySlot(slot)));
        }
        sb.append('\n');
        append("equipment.txt", sb.toString());
        ok(context, "Dumped equipment -> " + DEBUG_DIR.resolve("equipment.txt"));
        return 1;
    }

    /** Logs every chat and action-bar message for 30s -- abilities often announce themselves there. */
    private static int watchChat(CommandContext<FabricClientCommandSource> context) {
        watchChatTicksLeft = WATCH_CHAT_TICKS;
        append("chat.txt", "=== CHAT WATCH START ===\n");
        ok(context, "Logging chat + action bar for 30s -> " + DEBUG_DIR.resolve("chat.txt"));
        return 1;
    }

    /** The sidebar scoreboard, composed the same way vanilla renders it (team prefix + suffix). */
    private static int dumpScoreboard(CommandContext<FabricClientCommandSource> context) {
        Scoreboard scoreboard = Minecraft.getInstance().level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            fail(context, "No sidebar objective is displayed");
            return 1;
        }

        StringBuilder sb = new StringBuilder("=== SIDEBAR: ")
                .append(sidebar.getDisplayName().getString()).append(" ===\n");
        scoreboard.listPlayerScores(sidebar).stream()
                .filter(entry -> !entry.isHidden())
                .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
                .forEach(entry -> {
                    PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
                    String line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
                    sb.append(line).append("  [").append(entry.value()).append("]\n");
                });
        sb.append('\n');
        append("scoreboard.txt", sb.toString());
        ok(context, "Dumped sidebar -> " + DEBUG_DIR.resolve("scoreboard.txt"));
        return 1;
    }

    /** Tab list entries -- Skyblock exposes area, profile, and stats through display names here. */
    private static int dumpTabList(CommandContext<FabricClientCommandSource> context) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            fail(context, "Not connected to a server");
            return 1;
        }

        StringBuilder sb = new StringBuilder("=== TAB LIST ===\n");
        int count = 0;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            count++;
            Component display = info.getTabListDisplayName();
            sb.append(info.getProfile().name()).append(" | ")
                    .append(display != null ? display.getString() : "(no display name)").append('\n');
        }
        sb.append('\n');
        append("tablist.txt", sb.toString());
        ok(context, "Dumped " + count + " tab entries -> " + DEBUG_DIR.resolve("tablist.txt"));
        return 1;
    }

    /** Whatever the crosshair is on: the picked entity (name + NBT) and/or the targeted block. */
    private static int dumpTarget(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder("=== TARGET ===\n");

        Entity picked = client.crosshairPickEntity;
        if (picked != null) {
            sb.append(describeEntity(picked)).append('\n');
            sb.append(entityNbt(picked)).append('\n');
        }
        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) client.hitResult;
            sb.append("block @ ").append(blockHit.getBlockPos()).append(": ")
                    .append(client.level.getBlockState(blockHit.getBlockPos())).append('\n');
        }
        if (picked == null && (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS)) {
            fail(context, "Crosshair isn't on anything");
            return 1;
        }

        sb.append('\n');
        append("target.txt", sb.toString());
        ok(context, "Dumped crosshair target -> " + DEBUG_DIR.resolve("target.txt"));
        return 1;
    }

    /** Active potion/status effects on the player. */
    private static int dumpEffects(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();
        StringBuilder sb = new StringBuilder("=== EFFECTS ===\n");
        int count = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            count++;
            sb.append(effect).append('\n');
        }
        sb.append('\n');
        append("effects.txt", sb.toString());
        ok(context, "Dumped " + count + " effects -> " + DEBUG_DIR.resolve("effects.txt"));
        return 1;
    }

    private static String describeEntity(Entity entity) {
        String name = entity.hasCustomName() ? " \"" + entity.getCustomName().getString() + "\"" : "";
        return entity.getType() + name + String.format(" @ (%.1f, %.1f, %.1f)",
                entity.position().x, entity.position().y, entity.position().z);
    }

    private static String entityNbt(Entity entity) {
        TagValueOutput out = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        entity.saveWithoutId(out);
        return out.buildResult().toString();
    }

    private static String dumpStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "(empty)\n";
        }
        StringBuilder sb = new StringBuilder(stack.getItem().toString()).append('\n');
        for (TypedDataComponent<?> component : stack.getComponents()) {
            sb.append(component).append('\n');
        }
        return sb.toString();
    }

    private static void append(String fileName, String text) {
        try {
            Files.createDirectories(DEBUG_DIR);
            Files.writeString(DEBUG_DIR.resolve(fileName), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostics only -- losing a log line is not worth interrupting the game over.
        }
    }

    private static void ok(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(
                Component.literal("[NEXORA] " + message).withStyle(ChatFormatting.GREEN));
    }

    private static void fail(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(
                Component.literal("[NEXORA] " + message).withStyle(ChatFormatting.RED));
    }
}
