package com.k_tten.tfih.fabric.features

import com.k_tten.tfih.fabric.TfihFabric
import com.k_tten.tfih.fabric.Utilities.screen
import com.k_tten.tfih.fabric.config.Config.Range
import com.k_tten.tfih.fabric.helper.ChatHelper
import com.k_tten.tfih.fabric.helper.ChatHelper.launchWithSafeguard
import com.k_tten.tfih.fabric.model.Feature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

object Fishing : Feature {

    private val COROUTINE_SCOPE = CoroutineScope(Dispatchers.Default)
    private const val BOBBER_CHECK_VARIATION_MILLIS = 2_000L
    private const val NEARBY_FLUID_RADIUS = 4
    private var wasEnabledForBobberCheck = false
    private var nextBobberCheckAtNanos = 0L
    private var liquidHookId: UUID? = null
    private var liquidSinceNanos = 0L
    @Volatile
    private var pausedForGoldenFish = false

    val config
        get() = TfihFabric.INSTANCE.configManager.config.fishing
    var fishMutex: Mutex = Mutex()
    var fishJob: Job? = null

    fun pauseForGoldenFish() {
        pausedForGoldenFish = true
        fishJob?.cancel()
        fishJob = null
    }

    fun resumeAfterGoldenFish(recastAfterCatch: Boolean = false) {
        pausedForGoldenFish = false
        if (recastAfterCatch) ensureBobberOut()
    }

    @JvmStatic
    fun launchFishJob(
        range: Range?,
        slugfishDelayMillis: Long = 0L,
        expectedHookId: UUID? = null
    ): Job = COROUTINE_SCOPE.launchWithSafeguard {
        val mc = Minecraft.getInstance()
        if (pausedForGoldenFish) return@launchWithSafeguard

        if (range == null) {
            delay(spread(config.timings.castAverage).getRandomValue().milliseconds)
            mc.execute {
                if (pausedForGoldenFish) return@execute
                val player = mc.player ?: return@execute
                if (player.mainHandItem.item == Items.FISHING_ROD && player.fishing == null)
                    mc.startUseItem()
            }
            return@launchWithSafeguard
        }

        if (slugfishDelayMillis > 0) delay(slugfishDelayMillis.milliseconds)
        if (expectedHookId != null && mc.player?.fishing?.uuid != expectedHookId)
            return@launchWithSafeguard

        delay(range.getRandomValue().milliseconds)
        if (pausedForGoldenFish) return@launchWithSafeguard
        if (mc.player?.mainHandItem?.item != Items.FISHING_ROD)
            return@launchWithSafeguard
        if (config.jumpMode) {
            mc.execute {
                if (!pausedForGoldenFish) mc.options.keyJump.setDown(true)
            }
            try {
                delay(75.milliseconds)
            } finally {
                mc.execute {
                    mc.options.keyJump.setDown(false)
                }
            }
            delay(Range(75, 126).getRandomValue().milliseconds)
            if (mc.player?.mainHandItem?.item != Items.FISHING_ROD)
                return@launchWithSafeguard
        }
        mc.execute {
            if (!pausedForGoldenFish) mc.startUseItem()
        }

        delay(spread(config.timings.castAverage).getRandomValue().milliseconds)
        if (mc.player?.mainHandItem?.item != Items.FISHING_ROD)
            return@launchWithSafeguard
        mc.execute {
            if (!pausedForGoldenFish) mc.startUseItem()
        }
    }

    fun onTick(minecraftClient: Minecraft) = runBlocking {
        if (pausedForGoldenFish) return@runBlocking
        if (!config.enabled) {
            wasEnabledForBobberCheck = false
            return@runBlocking
        }

        maintainBobber(minecraftClient)
        val fishHook = minecraftClient.player?.fishing ?: run {
            liquidHookId = null
            liquidSinceNanos = 0L
            return@runBlocking
        }
        val now = System.nanoTime()
        if (isHookInLiquid(minecraftClient, fishHook)) {
            if (liquidHookId != fishHook.uuid) {
                liquidHookId = fishHook.uuid
                liquidSinceNanos = now
            }
        } else {
            liquidHookId = null
            liquidSinceNanos = 0L
        }

        val catching = fishHook.level().getEntities(
            //? if >= 26.2 {
            net.minecraft.world.entity.EntityTypes.ARMOR_STAND,
            //? } else
            //EntityType.ARMOR_STAND,
            fishHook.boundingBox.inflate(.0, 0.5, .0)
        ) {
            it.displayName!!.string == "!!!"
        }.isNotEmpty()

        if (!catching) return@runBlocking

        val fluid = getFluidFromHook(fishHook) ?: let {
            ChatHelper.sendToChat("Could not find a fluid block!")
            return@runBlocking
        }

        fishMutex.withLock {
            if (fishJob?.isActive == true) return@withLock
            TfihFabric.LOGGER.debug("Starting job using armor stand method")
            val configuredDelayNanos =
                (config.slugfishSeconds.coerceAtLeast(0.0) * 1_000_000_000.0).toLong()
            val elapsedNanos = if (liquidHookId == fishHook.uuid) now - liquidSinceNanos else 0L
            val remainingMillis =
                ((configuredDelayNanos - elapsedNanos).coerceAtLeast(0L) + 999_999L) / 1_000_000L
            fishJob = launchFishJob(fluid.getRange(), remainingMillis, fishHook.uuid)
        }
    }

    private fun isHookInLiquid(client: Minecraft, fishHook: FishingHook): Boolean {
        val level = client.level ?: return false
        val fluid = level.getBlockState(fishHook.blockPosition()).fluidState.type
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER ||
            fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA
    }

    private fun maintainBobber(client: Minecraft) {
        if (!wasEnabledForBobberCheck) {
            wasEnabledForBobberCheck = true
            scheduleBobberCheck()
            return
        }
        if (System.nanoTime() < nextBobberCheckAtNanos) return

        scheduleBobberCheck()
        val player = client.player ?: return
        if (client.level == null || client.screen() != null || client.isPaused ||
            player.mainHandItem.item != Items.FISHING_ROD || player.fishing != null ||
            !hasNearbyWaterOrLava(client, player.blockPosition())
        ) return

        client.startUseItem()
    }

    private fun hasNearbyWaterOrLava(client: Minecraft, center: BlockPos): Boolean {
        val level = client.level ?: return false
        for (x in -NEARBY_FLUID_RADIUS..NEARBY_FLUID_RADIUS) {
            for (y in -10..1) {
                for (z in -NEARBY_FLUID_RADIUS..NEARBY_FLUID_RADIUS) {
                    val fluid = level.getBlockState(center.offset(x, y, z)).fluidState.type
                    if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER ||
                        fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA
                    ) return true
                }
            }
        }
        return false
    }

    private fun scheduleBobberCheck() {
        val averageMillis = (config.bobberCheckAverageSeconds.coerceIn(5.0, 3600.0) * 1000).roundToLong()
        val intervalMillis = TfihFabric.RANDOM.nextLong(
            averageMillis - BOBBER_CHECK_VARIATION_MILLIS,
            averageMillis + BOBBER_CHECK_VARIATION_MILLIS + 1
        )
        nextBobberCheckAtNanos = System.nanoTime() + intervalMillis * 1_000_000L
    }

    enum class FluidType(val getRange: () -> Range) {
        WATER({ spread(config.timings.waterAverage) }), LAVA({ spread(config.timings.lavaAverage) })
    }

    private fun getFluidFromHook(fishHook: FishingHook): FluidType? {
        val minecraftClient = Minecraft.getInstance()
        for (i in -1..4) {
            val block = minecraftClient.level!!.getBlockState(fishHook.blockPosition().below(i))
            if (block.fluidState.type == Fluids.WATER || block.fluidState.type == Fluids.FLOWING_WATER) {
                return FluidType.WATER
            } else if (block.fluidState.type == Fluids.LAVA || block.fluidState.type == Fluids.FLOWING_LAVA) {
                return FluidType.LAVA
            }
        }
        return null
    }

    private fun spread(value: Float): Range {
        val distribution = config.timings.distribution
        return Range(
            (value - value * distribution).roundToLong(),
            (value + value * distribution).roundToLong()
        )
    }

    override fun init(mc: Minecraft) {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (message.string.contains("The Golden Fish swims back beneath the lava"))
                ensureBobberOut()
        }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
            onTick(it)
        })
    }

    private fun ensureBobberOut() {
        if (pausedForGoldenFish) return
        val player = Minecraft.getInstance().player ?: return
        if (!config.enabled || player.mainHandItem.item != Items.FISHING_ROD || player.fishing != null)
            return
        if (fishJob?.isActive == true)
            return

        fishJob = launchFishJob(null)
    }
}