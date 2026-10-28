package com.spawnerprotect;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spawner Protect (Minecraft 1.21.11, official Mojang mappings)
 *
 * Starts when another player is loaded near you and a skeleton spawner is in range, and you have a
 * SILK TOUCH pickaxe in your hotbar. Then: hold sneak, walk to / aim at / break the spawners.
 *
 * Settings (Mod Menu):
 *  - Auto detect ON : keeps breaking until no players are detected anymore (then stops, stays online).
 *                     Logs off if every spawner is gone.
 *  - Auto detect OFF: manual - breaks exactly N times (4 stacked spawners = 4 breaks), then logs off.
 *
 * Press P to toggle on/off. The grey line under the ON/OFF text shows what the mod currently sees.
 */
public class SpawnerProtectClient implements ClientModInitializer {

    // ---- settings (edit these) ----
    private static final int SCAN_RADIUS = 24;               // blocks around you to look for spawners
    private static final double RENDER_RANGE = 128.0;        // another player this close (and loaded) counts
    private static final double REACH = 4.5;                 // survival block reach
    private static final int TIMEOUT_TICKS = 20 * 60 * 5;    // manual mode: give up + log off after 5 minutes
    private static final int STUCK_TICKS = 100;              // skip a spawner we can't reach for 5 seconds
    private static final int GONE_TICKS = 20;                // block must stay gone this long to count as destroyed
    private static final int NO_PLAYER_TICKS = 40;           // auto mode: no players for 2 seconds = stop
    private static final Set<String> WHITELIST = Set.of();   // names that DON'T trigger it, e.g. Set.of("MyAlt")
    private static final String TARGET_MOB = "minecraft:skeleton";

    private record SpawnerInfo(BlockPos pos, String id) {}

    private boolean enabled = true;   // toggled with the P key
    private boolean active = false;
    private boolean testRun = false;
    private boolean warnedNoPickaxe = false;
    private boolean forceStart = false;
    private int ticks = 0;
    private int stuckTicks = 0;
    private int breaksDone = 0;
    private int noPlayerTicks = 0;

    private final Set<BlockPos> runTargets = new HashSet<>();
    private final Map<BlockPos, Boolean> wasPresent = new HashMap<>();
    private final Map<BlockPos, Integer> goneFor = new HashMap<>();
    private final Set<BlockPos> skipped = new HashSet<>();

    private String debugLine = "";
    private KeyMapping toggleKey;
    private KeyMapping testKey;

    @Override
    public void onInitializeClient() {
        SpawnerProtectConfig.load();

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spawnerprotect", "main"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spawnerprotect.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, category));
        testKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "Spawner Protect: test run", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientTickEvents.START_CLIENT_TICK.register(this::tick);

        // always-visible ON/OFF indicator + debug line, top-left of the screen
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("spawnerprotect", "status"),
                (graphics, deltaTracker) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return;
                    graphics.drawString(mc.font, statusText(), 4, 4, 0xFFFFFFFF, true);
                    if (enabled && !debugLine.isEmpty()) {
                        graphics.drawString(mc.font, Component.literal(debugLine), 4, 16, 0xFFAAAAAA, true);
                    }
                });
    }

    private Component statusText() {
        Component state = enabled
                ? Component.literal("ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("OFF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        MutableComponent out = Component.literal("Spawner Protect: ").append(state);
        if (enabled) {
            String mode = SpawnerProtectConfig.autoDetect
                    ? " (Auto)"
                    : " (Manual x" + SpawnerProtectConfig.breakCount + ")";
            out.append(Component.literal(mode).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private void chat(Minecraft mc, String text) {
        mc.gui.getChat().addMessage(Component.literal("[Spawner Protect] " + text));
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) {
                active = false;
                clearRun();
                releaseKeys(mc);
            }
            if (mc.player != null) {
                mc.gui.setOverlayMessage(statusText(), false);
            }
        }
        while (testKey.consumeClick()) {
            if (enabled && mc.player != null && !active) {
                forceStart = true;
                chat(mc, "Test run: ignoring the player check.");
            }
        }
        if (!enabled) return;

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.getConnection() == null) {
            active = false;
            return;
        }

        if (!active) {
            if (!forceStart && level.getGameTime() % 10 != 0) return;

            List<SpawnerInfo> all = scanSpawners(mc);
            List<BlockPos> skeletons = targets(all);
            int players = countOtherPlayers(mc);
            int slot = findSilkPickaxe(mc);
            debugLine = buildDebug(all, skeletons, players, slot, player);

            boolean test = forceStart;
            boolean trigger = test || (players > 0 && !skeletons.isEmpty());
            forceStart = false;

            if (!trigger) {
                warnedNoPickaxe = false;
                return;
            }
            if (skeletons.isEmpty()) {
                chat(mc, "No skeleton spawners in range (" + SCAN_RADIUS + " blocks).");
                return;
            }
            if (slot == -1) {
                if (!warnedNoPickaxe) {
                    warnedNoPickaxe = true;
                    chat(mc, "Spawner found, but you have no silk touch pickaxe in your hotbar.");
                }
                return;
            }

            // start a run
            clearRun();
            runTargets.addAll(skeletons);
            for (BlockPos p : skeletons) wasPresent.put(p, true);
            active = true;
            testRun = test;
            ticks = 0;
            stuckTicks = 0;
            breaksDone = 0;
            noPlayerTicks = 0;
            chat(mc, players > 0 ? "Player detected, breaking spawners..." : "Breaking spawners...");
        }

        runTick(mc);
    }

    private void runTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        // silk touch pickaxe is required the whole time
        int slot = findSilkPickaxe(mc);
        if (slot == -1) {
            warnedNoPickaxe = true;
            stopRun(mc, "Silk touch pickaxe missing, stopped.");
            return;
        }
        player.getInventory().setSelectedSlot(slot);

        ticks++;
        mc.options.keyShift.setDown(true);

        // track our spawners and count every break (stacked spawners: block disappears, server puts it back)
        Iterator<BlockPos> it = runTargets.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            boolean present = level.getBlockState(p).is(Blocks.SPAWNER);
            Boolean was = wasPresent.put(p, present);
            if (was != null && was && !present) breaksDone++;
            if (present) {
                goneFor.remove(p);
            } else if (goneFor.merge(p, 1, Integer::sum) >= GONE_TICKS) {
                it.remove();            // really gone (last one of the stack broken)
                goneFor.remove(p);
                wasPresent.remove(p);
            }
        }

        boolean auto = SpawnerProtectConfig.autoDetect;

        // manual mode: done after exactly N breaks
        if (!auto && breaksDone >= SpawnerProtectConfig.breakCount) {
            chat(mc, "Broke " + breaksDone + " time(s), done.");
            finish(mc);
            return;
        }

        // auto mode: stop once no players are detected anymore
        if (auto && !testRun) {
            if (countOtherPlayers(mc) == 0) noPlayerTicks++;
            else noPlayerTicks = 0;
            if (noPlayerTicks >= NO_PLAYER_TICKS) {
                stopRun(mc, "No players detected anymore, stopped after " + breaksDone + " break(s).");
                return;
            }
        }

        List<BlockPos> spawners = new ArrayList<>(runTargets);
        spawners.removeAll(skipped);
        debugLine = "RUNNING | breaks: " + breaksDone + (auto ? "" : "/" + SpawnerProtectConfig.breakCount)
                + " | spawners left: " + spawners.size();

        if (spawners.isEmpty() || (!auto && ticks > TIMEOUT_TICKS)) {
            finish(mc);
            return;
        }

        Vec3 eye = player.getEyePosition();
        BlockPos target = spawners.stream()
                .min(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(eye)))
                .get();

        aimAt(player, target);

        double dist = Vec3.atCenterOf(target).distanceTo(eye);
        BlockPos hitPos = null;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            hitPos = hit.getBlockPos();
        }
        // mine any of our spawners under the crosshair (never other blocks)
        boolean lookingAtSpawner = hitPos != null && spawners.contains(hitPos);

        if (lookingAtSpawner) {
            stuckTicks = 0;
            mc.options.keyAttack.setDown(true);
            mc.options.keyUp.setDown(false);
        } else if (dist <= REACH) {
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(dist > 2.0);
            if (++stuckTicks > STUCK_TICKS) {
                skipped.add(target);
                stuckTicks = 0;
                chat(mc, "Can't reach the spawner at " + target.toShortString() + " (blocked), skipping it.");
            }
        } else {
            stuckTicks = 0;
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(true);
        }
        mc.options.keyJump.setDown(player.horizontalCollision && dist > REACH);
    }

    /** Stop without logging off. */
    private void stopRun(Minecraft mc, String message) {
        active = false;
        clearRun();
        releaseKeys(mc);
        chat(mc, message);
    }

    /** Stop and log off. */
    private void finish(Minecraft mc) {
        active = false;
        clearRun();
        releaseKeys(mc);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection()
                    .disconnect(Component.literal("Spawner Protect: done, logged off."));
        }
    }

    private void clearRun() {
        runTargets.clear();
        wasPresent.clear();
        goneFor.clear();
        skipped.clear();
    }

    private void releaseKeys(Minecraft mc) {
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private String buildDebug(List<SpawnerInfo> all, List<BlockPos> skeletons, int players, int slot, LocalPlayer player) {
        BlockPos me = player.blockPosition();
        String nearestId = all.stream()
                .min(Comparator.comparingDouble(s -> s.pos().distSqr(me)))
                .map(s -> s.id().isEmpty() ? "?" : s.id())
                .orElse("none");
        return "Players: " + players
                + " | Spawners: " + all.size() + " (skeleton " + skeletons.size() + ")"
                + " | Silk pickaxe: " + (slot != -1 ? "yes" : "NO")
                + " | nearest: " + nearestId;
    }

    /** Number of other (non-spectator, non-whitelisted) players loaded within RENDER_RANGE. */
    private int countOtherPlayers(Minecraft mc) {
        int count = 0;
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == mc.player) continue;
            if (other.isSpectator()) continue;
            if (WHITELIST.contains(other.getName().getString())) continue;
            if (other.distanceTo(mc.player) > RENDER_RANGE) continue;
            count++;
        }
        return count;
    }

    private List<BlockPos> targets(List<SpawnerInfo> all) {
        List<BlockPos> out = new ArrayList<>();
        for (SpawnerInfo s : all) {
            if (TARGET_MOB.equals(s.id())) out.add(s.pos());
        }
        return out;
    }

    /** Every spawner block entity within SCAN_RADIUS, with the mob type it holds. */
    private List<SpawnerInfo> scanSpawners(Minecraft mc) {
        List<SpawnerInfo> found = new ArrayList<>();
        ClientLevel level = mc.level;
        BlockPos center = mc.player.blockPosition();
        int chunkRange = (SCAN_RADIUS >> 4) + 1;
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;
        double maxSq = (double) SCAN_RADIUS * SCAN_RADIUS;

        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                LevelChunk chunk = level.getChunk(ccx + dx, ccz + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof SpawnerBlockEntity)) continue;
                    if (be.getBlockPos().distSqr(center) > maxSq) continue;
                    found.add(new SpawnerInfo(be.getBlockPos(), spawnerMobId(be, level)));
                }
            }
        }
        return found;
    }

    private String spawnerMobId(BlockEntity be, ClientLevel level) {
        CompoundTag tag = be.getUpdateTag(level.registryAccess());
        return tag.getCompoundOrEmpty("SpawnData")
                .getCompoundOrEmpty("entity")
                .getStringOr("id", "");
    }

    private void aimAt(LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 t = Vec3.atCenterOf(pos);
        double dx = t.x - eye.x;
        double dy = t.y - eye.y;
        double dz = t.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        player.setXRot((float) -(Mth.atan2(dy, horiz) * 180.0 / Math.PI));
    }

    /** Hotbar slot (0-8) of a pickaxe with Silk Touch, or -1 if you don't have one. */
    private int findSilkPickaxe(Minecraft mc) {
        var inv = mc.player.getInventory();
        var silk = mc.level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(ItemTags.PICKAXES)) continue;
            if (EnchantmentHelper.getItemEnchantmentLevel(silk, stack) > 0) return i;
        }
        return -1;
    }
}
