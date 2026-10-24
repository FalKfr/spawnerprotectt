package com.spawnerprotect;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Spawner Protect (Minecraft 26.2, official Mojang mappings)
 *
 * Every few ticks: if a skeleton spawner is within SCAN_RADIUS and another player is
 * loaded in the world, take over: hold sneak, walk to / aim at / break every skeleton
 * spawner in range, then disconnect once none are left. Press P to toggle on/off.
 */
public class SpawnerProtectClient implements ClientModInitializer {

    // ---- settings (edit these) ----
    private static final int SCAN_RADIUS = 24;               // blocks around you to look for spawners
    private static final double REACH = 4.5;                 // survival block reach
    private static final int TIMEOUT_TICKS = 20 * 60 * 3;    // give up + log off after 3 minutes
    private static final Set<String> WHITELIST = Set.of();   // names that DON'T trigger it, e.g. Set.of("MyAlt")
    private static final String TARGET_MOB = "minecraft:skeleton";

    private boolean enabled = true;   // toggled with the P key
    private boolean active = false;
    private int ticks = 0;
    private KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spawnerprotect.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spawnerprotect", "main"))));

        ClientTickEvents.START_CLIENT_TICK.register(this::tick);

        // always-visible ON/OFF indicator, top-left of the screen
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("spawnerprotect", "status"),
                (graphics, deltaTracker) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.options.hideGui) return;
                    graphics.text(mc.font, statusText(), 4, 4, 0xFFFFFFFF, true);
                });
    }

    private Component statusText() {
        Component state = enabled
                ? Component.literal("ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("OFF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        return Component.literal("Spawner Protect: ").append(state);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) {
                active = false;
                releaseKeys(mc);
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(statusText(), true);
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
            if (level.getGameTime() % 10 != 0) return;
            if (!otherPlayerLoaded(mc)) return;
            if (findSpawners(mc).isEmpty()) return;
            active = true;
            ticks = 0;
            player.displayClientMessage(
                    Component.literal("[Spawner Protect] Player detected, breaking skeleton spawners..."), false);
        }

        ticks++;
        mc.options.keyShift.setDown(true);

        List<BlockPos> spawners = findSpawners(mc);
        if (spawners.isEmpty() || ticks > TIMEOUT_TICKS) {
            finish(mc);
            return;
        }

        Vec3 eye = player.getEyePosition();
        BlockPos target = spawners.stream()
                .min(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(eye)))
                .get();

        aimAt(player, target);
        selectBestPickaxe(mc);

        double dist = Vec3.atCenterOf(target).distanceTo(eye);
        boolean lookingAtIt = mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);

        if (dist <= REACH) {
            // only mine when the crosshair is actually on the spawner (never digs random blocks)
            mc.options.keyAttack.setDown(lookingAtIt);
            mc.options.keyUp.setDown(!lookingAtIt && dist > 2.0);
        } else {
            mc.options.keyAttack.setDown(false);
            mc.options.keyUp.setDown(true);
        }
        mc.options.keyJump.setDown(player.horizontalCollision && dist > REACH);
    }

    private void finish(Minecraft mc) {
        active = false;
        releaseKeys(mc);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection()
                    .disconnect(Component.literal("Spawner Protect: spawners cleared, logged off."));
        }
    }

    private void releaseKeys(Minecraft mc) {
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private boolean otherPlayerLoaded(Minecraft mc) {
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == mc.player) continue;
            if (other.isSpectator()) continue;
            if (WHITELIST.contains(other.getName().getString())) continue;
            return true;
        }
        return false;
    }

    private List<BlockPos> findSpawners(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
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
                    if (isTargetSpawner(be, level)) found.add(be.getBlockPos());
                }
            }
        }
        return found;
    }

    private boolean isTargetSpawner(BlockEntity be, ClientLevel level) {
        CompoundTag tag = be.getUpdateTag(level.registryAccess());
        String id = tag.getCompoundOrEmpty("SpawnData")
                .getCompoundOrEmpty("entity")
                .getStringOr("id", "");
        return TARGET_MOB.equals(id);
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

    /** Prefers a silk touch pickaxe (so the spawner drops), otherwise any pickaxe. */
    private void selectBestPickaxe(Minecraft mc) {
        var inv = mc.player.getInventory();
        var silk = mc.level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH);

        int best = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(ItemTags.PICKAXES)) continue;
            if (EnchantmentHelper.getItemEnchantmentLevel(silk, stack) > 0) {
                best = i;
                break;
            }
            if (best == -1) best = i;
        }
        if (best != -1 && inv.getSelectedSlot() != best) {
            inv.setSelectedSlot(best);
        }
    }
}
