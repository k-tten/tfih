package com.k_tten.tfih.fabric.features

import com.k_tten.tfih.fabric.TfihFabric
import com.k_tten.tfih.fabric.Utilities.screen
import com.k_tten.tfih.fabric.helper.ChatHelper
import com.k_tten.tfih.fabric.model.Feature
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import kotlin.math.atan2
import kotlin.math.sqrt

object GoldenFish : Feature {
    private const val SEARCH_RADIUS = 8.0
    private const val LAVA_RADIUS = 2
    private const val AIM_DURATION_NANOS = 400_000_000L
    private const val ROTATION_RESTORE_DURATION_NANOS = 400_000_000L
    private const val CAST_WAIT_NANOS = 300_000_000L
    private const val RESIST_TIMEOUT_NANOS = 700_000_000L
    private const val CLICK_INTERVAL_NANOS = 500_000_000L
    private const val TROPHY_TIMEOUT_NANOS = 1_200_000_000L
    private const val TARGET_SEARCH_TIMEOUT_NANOS = 2_000_000_000L

    private enum class State {
        IDLE,
        FINDING_TARGET,
        WAITING_FOR_UMBERELLA,
        CHECKPOINT,
        AIMING,
        REELING,
        WAITING_TO_CLICK,
        WAITING_FOR_RESISTANCE,
        RESISTING,
        CLICKING_AFTER_WEAK,
        WAITING_FOR_TROPHY,
        RESTORING_ROTATION
    }

    private var state = State.IDLE
    private var target: ArmorStand? = null
    private var deadlineNanos = 0L
    private var aimStartedAtNanos = 0L
    private var startYaw = 0f
    private var startPitch = 0f
    private var originalYaw = 0f
    private var originalPitch = 0f
    private var rotationRestoreStartedAtNanos = 0L
    private var rotationRestoreStartYaw = 0f
    private var rotationRestoreStartPitch = 0f
    private var rotationChanged = false
    private var caughtGoldenFish = false
    private var warnedAboutJumpMode = false
    private var fishingPaused = false

    private val isEnabled
        get() = TfihFabric.INSTANCE.configManager.config.goldenFish

    override fun init(mc: Minecraft) {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            mc.execute {
                if (!isEnabled) return@execute
                handleGameMessage(mc, message.string)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick(::onTick))
    }

    private fun handleGameMessage(mc: Minecraft, text: String) {
        when {
            text.contains("You spot a Golden Fish surface from beneath the lava!") -> startSearch(mc)
            state == State.IDLE -> Unit
            text.contains("The Golden Fish is resisting...") -> {
                state = State.RESISTING
                deadlineNanos = System.nanoTime()
            }
            text.contains("The Golden Fish escapes your hook but looks weakened.") -> {
                state = State.CHECKPOINT
            }
            text.contains("The Golden Fish is weak!") -> {
                state = State.CLICKING_AFTER_WEAK
                deadlineNanos = System.nanoTime() + CLICK_INTERVAL_NANOS
            }
            text.contains("TROPHY FISH! You caught a Golden Fish") -> {
                caughtGoldenFish = true
                startRotationRestore(mc)
            }
        }
    }

    private fun startSearch(mc: Minecraft) {
        if (state != State.IDLE) return
        val player = mc.player ?: return
        originalYaw = player.yRot
        originalPitch = player.xRot
        rotationChanged = false
        Fishing.pauseForGoldenFish()
        fishingPaused = true
        state = State.FINDING_TARGET
        target = null
        deadlineNanos = System.nanoTime() + TARGET_SEARCH_TIMEOUT_NANOS
    }

    private fun onTick(mc: Minecraft) {
        val config = TfihFabric.INSTANCE.configManager.config
        if (config.goldenFish && config.fishing.jumpMode && !warnedAboutJumpMode) {
            ChatHelper.sendToChat("§eWarning: Golden Fish automation is lava-only, while leap/jump mode is water-only. Disable one to avoid conflicting automation.")
            warnedAboutJumpMode = true
        } else if (!config.goldenFish || !config.fishing.jumpMode) {
            warnedAboutJumpMode = false
        }

        if (!isEnabled) {
            finish()
            return
        }

        if (state == State.IDLE) return
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || mc.isPaused || mc.screen() != null) {
            finish()
            return
        }

        val now = System.nanoTime()
        when (state) {
            State.IDLE -> Unit
            State.FINDING_TARGET -> {
                target = findTarget(mc)
                if (target != null) {
                    if (TfihFabric.INSTANCE.configManager.config.umberella) {
                        Umberella.resetTimer()
                    }
                    Umberella.requestForcedRun()
                    state = State.WAITING_FOR_UMBERELLA
                } else if (now >= deadlineNanos) {
                    ChatHelper.sendToChat("Golden Fish automation: no armor stand with a head item was found near lava within 8 blocks.")
                    finish()
                }
            }
            State.WAITING_FOR_UMBERELLA -> {
                if (!Umberella.isActionInProgress) state = State.CHECKPOINT
            }
            State.CHECKPOINT -> {
                val stand = target
                if (stand == null || stand.isRemoved || !isNearLava(mc, stand)) {
                    finish()
                    return
                }
                if (player.mainHandItem.item != Items.FISHING_ROD) {
                    ChatHelper.sendToChat("Golden Fish automation stopped: hold a fishing rod in your main hand.")
                    finish()
                    return
                }
                startYaw = player.yRot
                startPitch = player.xRot
                aimStartedAtNanos = now
                state = State.AIMING
            }
            State.AIMING -> {
                val stand = target
                if (stand == null || stand.isRemoved) {
                    finish()
                    return
                }
                val progress = ((now - aimStartedAtNanos).toDouble() / AIM_DURATION_NANOS)
                    .coerceIn(0.0, 1.0).toFloat()
                val (yaw, pitch) = getLookAngles(player, stand)
                rotationChanged = true
                setPlayerRotation(
                    player,
                    lerpAngle(startYaw, yaw, progress),
                    startPitch + (pitch - startPitch) * progress
                )
                if (progress >= 1.0f) {
                    if (player.fishing != null) {
                        mc.startUseItem()
                        deadlineNanos = now + CAST_WAIT_NANOS
                        state = State.REELING
                    } else {
                        mc.startUseItem()
                        deadlineNanos = now + CAST_WAIT_NANOS
                        state = State.WAITING_TO_CLICK
                    }
                }
            }
            State.REELING -> {
                if (now >= deadlineNanos) {
                    if (player.mainHandItem.item != Items.FISHING_ROD) {
                        finish()
                        return
                    }
                    mc.startUseItem()
                    deadlineNanos = now + CAST_WAIT_NANOS
                    state = State.WAITING_TO_CLICK
                }
            }
            State.WAITING_TO_CLICK -> {
                if (now >= deadlineNanos) {
                    mc.startUseItem()
                    deadlineNanos = now + RESIST_TIMEOUT_NANOS
                    state = State.WAITING_FOR_RESISTANCE
                }
            }
            State.WAITING_FOR_RESISTANCE -> {
                if (now >= deadlineNanos) state = State.CHECKPOINT
            }
            State.RESISTING -> {
                if (now >= deadlineNanos) {
                    mc.startUseItem()
                    deadlineNanos = now + CLICK_INTERVAL_NANOS
                }
            }
            State.CLICKING_AFTER_WEAK -> {
                if (now >= deadlineNanos) {
                    mc.startUseItem()
                    deadlineNanos = now + TROPHY_TIMEOUT_NANOS
                    state = State.WAITING_FOR_TROPHY
                }
            }
            State.WAITING_FOR_TROPHY -> {
                if (now >= deadlineNanos) state = State.CHECKPOINT
            }
            State.RESTORING_ROTATION -> {
                val progress = ((now - rotationRestoreStartedAtNanos).toDouble() /
                    ROTATION_RESTORE_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
                setPlayerRotation(
                    player,
                    lerpAngle(rotationRestoreStartYaw, originalYaw, progress),
                    rotationRestoreStartPitch + (originalPitch - rotationRestoreStartPitch) * progress
                )
                if (progress >= 1.0f) {
                    setPlayerRotation(player, originalYaw, originalPitch)
                    finish()
                }
            }
        }
    }

    private fun findTarget(mc: Minecraft): ArmorStand? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val nearbyStands = level.getEntities(
            //? if >= 26.2 {
            net.minecraft.world.entity.EntityTypes.ARMOR_STAND,
            //? } else
            //net.minecraft.world.entity.EntityType.ARMOR_STAND,
            player.boundingBox.inflate(SEARCH_RADIUS)
        ) { stand ->
            !stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty && isNearLava(mc, stand)
        }
        return nearbyStands
            .filter { player.distanceToSqr(it) <= SEARCH_RADIUS * SEARCH_RADIUS }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun isNearLava(mc: Minecraft, stand: ArmorStand): Boolean {
        val level = mc.level ?: return false
        val position = stand.blockPosition()
        for (x in -LAVA_RADIUS..LAVA_RADIUS) {
            for (y in -LAVA_RADIUS..LAVA_RADIUS) {
                for (z in -LAVA_RADIUS..LAVA_RADIUS) {
                    val fluid = level.getBlockState(position.offset(x, y, z)).fluidState.type
                    if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) return true
                }
            }
        }
        return false
    }

    private fun getLookAngles(player: net.minecraft.client.player.LocalPlayer, stand: ArmorStand): Pair<Float, Float> {
        val targetPosition = stand.position().add(0.0, 1.5, 0.0)
        val dx = targetPosition.x - player.x
        val dy = targetPosition.y - player.eyeY
        val dz = targetPosition.z - player.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0f
        val pitch = -Math.toDegrees(atan2(dy, horizontalDistance)).toFloat()
        return yaw to pitch
    }

    private fun lerpAngle(from: Float, to: Float, amount: Float): Float {
        var difference = (to - from) % 360.0f
        if (difference >= 180.0f) difference -= 360.0f
        if (difference < -180.0f) difference += 360.0f
        return from + difference * amount
    }

    private fun ensureBobberOut(mc: Minecraft) {
        val player = mc.player ?: return
        if (player.fishing == null && player.mainHandItem.item == Items.FISHING_ROD) {
            mc.startUseItem()
        } else if (player.mainHandItem.item != Items.FISHING_ROD) {
            ChatHelper.sendToChat("Golden Fish caught, but no fishing rod is held to cast the bobber.")
        }
    }

    private fun startRotationRestore(mc: Minecraft) {
        val player = mc.player
        if (player == null) {
            finish()
            return
        }
        rotationRestoreStartYaw = player.yRot
        rotationRestoreStartPitch = player.xRot
        rotationRestoreStartedAtNanos = System.nanoTime()
        state = State.RESTORING_ROTATION
    }

    private fun setPlayerRotation(
        player: net.minecraft.client.player.LocalPlayer,
        yaw: Float,
        pitch: Float
    ) {
        player.setYRot(yaw)
        player.setXRot(pitch)
        player.setYHeadRot(yaw)
        player.setYBodyRot(yaw)
    }

    private fun finish() {
        if (rotationChanged) {
            Minecraft.getInstance().player?.let { setPlayerRotation(it, originalYaw, originalPitch) }
        }
        if (caughtGoldenFish) {
            ensureBobberOut(Minecraft.getInstance())
            caughtGoldenFish = false
        }
        if (fishingPaused) {
            Fishing.resumeAfterGoldenFish()
            fishingPaused = false
        }
        state = State.IDLE
        target = null
        deadlineNanos = 0L
        aimStartedAtNanos = 0L
        rotationRestoreStartedAtNanos = 0L
        rotationChanged = false
    }
}
