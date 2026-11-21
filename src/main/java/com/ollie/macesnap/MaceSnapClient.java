package com.ollie.macesnap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

public class MaceSnapClient implements ClientModInitializer {

    private static KeyBinding snapKey;
    
    // State Machine buat Combo
    private static int comboState = 0; // 0 = idle, 1 = swap ke axe, 2 = hit axe, 3 = swap ke mace, 4 = hit mace
    private static int tickDelay = 0;
    private static Entity targetEntity = null;
    
    private static int axeSlot = -1;
    private static int maceSlot = -1;

    @Override
    public void onInitializeClient() {
        snapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.macesnap.snap",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.macesnap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Logic Combo State Machine
            if (comboState > 0) {
                if (tickDelay > 0) {
                    tickDelay--;
                    return;
                }

                if (targetEntity == null || !targetEntity.isAlive()) {
                    comboState = 0;
                    return;
                }

                switch (comboState) {
                    case 1: // Pindah ke Axe
                        if (axeSlot != -1) {
                            client.player.getInventory().selectedSlot = axeSlot;
                            client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(axeSlot));
                        }
                        comboState = 2;
                        tickDelay = 1; // Delay 1 tick biar server sadar lu ganti item
                        break;
                    case 2: // Hit pake Axe (Pecahin Shield)
                        client.interactionManager.attackEntity(client.player, targetEntity);
                        client.player.swingHand(Hand.MAIN_HAND);
                        comboState = 3;
                        tickDelay = 1;
                        break;
                    case 3: // Pindah balik ke Mace
                        if (maceSlot != -1) {
                            client.player.getInventory().selectedSlot = maceSlot;
                            client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(maceSlot));
                        }
                        comboState = 4;
                        tickDelay = 2; // Kasih jeda 2 tick biar gak terlalu cepat
                        break;
                    case 4: // Hit Mace (Smash)
                        client.interactionManager.attackEntity(client.player, targetEntity);
                        client.player.swingHand(Hand.MAIN_HAND);
                        comboState = 0; // Selesai
                        targetEntity = null;
                        break;
                }
            }

            // Trigger Keybind
            while (snapKey.wasPressed()) {
                if (comboState > 0) return; // Lagi kombo, jangan diganggu
                if (!client.player.getMainHandStack().isOf(Items.MACE)) return;
                if (client.player.fallDistance < 1.5f) return; 

                Entity target = raycastEntity(client, 4.5f);
                if (target == null) return;

                // Smooth Aim dulu ke target
                smoothAim(client.player, target, 0.5f);
                targetEntity = target;

                // Cek apakah target lagi megang shield
                boolean isBlocking = target instanceof LivingEntity living && living.isBlocking();
                
                if (isBlocking) {
                    // Cari Axe dan Mace di Hotbar
                    axeSlot = findItemInHotbar(client, "axe");
                    maceSlot = client.player.getInventory().selectedSlot;

                    if (axeSlot != -1) {
                        comboState = 1; // Mulai kombo Axe -> Mace
                    } else {
                        // Kalo gak ada axe, yaudah hajar pake mace aja (tembus partial)
                        comboState = 4; 
                    }
                } else {
                    // Kalo gak nge-block, langsung hit pake Mace
                    comboState = 4;
                }
                tickDelay = 1; // Jeda awal sebelum eksekusi
            }
        });
    }

    // Fungsi buat nyari Axe di Hotbar (slot 0-8)
    private int findItemInHotbar(MinecraftClient client, String type) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            if (type.equals("axe") && stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private Entity raycastEntity(MinecraftClient client, float maxDist) {
        Vec3d camPos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVec(1.0f);
        Vec3d end = camPos.add(lookVec.multiply(maxDist));

        HitResult hit = client.world.raycast(new RaycastContext(
                camPos, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            end = hit.getPos();
        }

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;
        
        for (Entity e : client.world.getOtherEntities(client.player, client.player.getBoundingBox().stretch(lookVec.multiply(maxDist)).expand(1.0))) {
            if (!(e instanceof LivingEntity) || !e.isAlive()) continue;
            var box = e.getBoundingBox().expand(0.3);
            var optional = box.raycast(camPos, end);
            if (optional.isPresent()) {
                double d = camPos.distanceTo(optional.get());
                if (d < closestDist) {
                    closestDist = d;
                    closest = e;
                }
            }
        }
        return closest;
    }

    private void smoothAim(net.minecraft.entity.player.PlayerEntity player, Entity target, float speed) {
        Vec3d eye = player.getEyePos();
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() / 2.0, 0);
        Vec3d diff = targetCenter.subtract(eye);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float deltaPitch = targetPitch - player.getPitch();

        float sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 8.0f * 0.15f;

        float newYaw = player.getYaw() + Math.round((deltaYaw * speed) / gcd) * gcd;
        float newPitch = player.getPitch() + Math.round((deltaPitch * speed) / gcd) * gcd;

        float jitter = (float) (Math.random() * 0.05 - 0.025);

        player.setYaw(newYaw);
        player.setPitch(MathHelper.clamp(newPitch + jitter, -90.0f, 90.0f));
    }
                      }
