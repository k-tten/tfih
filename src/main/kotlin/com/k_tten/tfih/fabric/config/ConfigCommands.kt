package com.k_tten.tfih.fabric.config

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.k_tten.tfih.fabric.features.Umberella
import com.k_tten.tfih.fabric.helper.ChatHelper
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object ConfigCommands {
    fun register(
        dispatcher: com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource>,
        configManager: ConfigManager
    ) {
        val root = LiteralArgumentBuilder.literal<FabricClientCommandSource>("tfih")
            .executes { showConfig(configManager) }
            .then(
                LiteralArgumentBuilder.literal<FabricClientCommandSource>("view")
                    .executes { showConfig(configManager) }
            )
            .then(
                LiteralArgumentBuilder.literal<FabricClientCommandSource>("testumb")
                    .executes {
                        if (Umberella.requestTestRun()) {
                            ChatHelper.sendToChat("Starting one Umberella test run.")
                            Command.SINGLE_SUCCESS
                        } else {
                            ChatHelper.sendToChat("Umberella is already running an action.")
                            0
                        }
                    }
            )
            .then(
                LiteralArgumentBuilder.literal<FabricClientCommandSource>("set")
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("enabled")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                    "value",
                                    BoolArgumentType.bool()
                                ).executes {
                                    configManager.config.fishing.enabled =
                                        BoolArgumentType.getBool(it, "value")
                                    saveAndReport(configManager, "Fishing enabled: ${configManager.config.fishing.enabled}")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("umberella")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                    "value",
                                    BoolArgumentType.bool()
                                ).executes {
                                    configManager.config.umberella =
                                        BoolArgumentType.getBool(it, "value")
                                    saveAndReport(configManager, "Umberella enabled: ${configManager.config.umberella}")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("auto-gg")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                    "value",
                                    BoolArgumentType.bool()
                                ).executes {
                                    configManager.config.autoGg =
                                        BoolArgumentType.getBool(it, "value")
                                    saveAndReport(
                                        configManager,
                                        "Auto-gg enabled: ${configManager.config.autoGg}"
                                    )
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("slugfish")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Double>(
                                    "seconds",
                                    DoubleArgumentType.doubleArg(0.0)
                                ).executes {
                                    configManager.config.fishing.slugfishSeconds =
                                        DoubleArgumentType.getDouble(it, "seconds")
                                    saveAndReport(
                                        configManager,
                                        if (configManager.config.fishing.slugfishSeconds == 0.0) {
                                            "Slugfish delay disabled."
                                        } else {
                                            "Slugfish delay: ${configManager.config.fishing.slugfishSeconds} seconds."
                                        }
                                    )
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("jump")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                    "value",
                                    BoolArgumentType.bool()
                                ).executes {
                                    configManager.config.fishing.jumpMode =
                                        BoolArgumentType.getBool(it, "value")
                                    saveAndReport(configManager, "Fishing jump mode: ${configManager.config.fishing.jumpMode}")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("golden-fish")
                            .then(
                                com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                    "value",
                                    BoolArgumentType.bool()
                                ).executes {
                                    configManager.config.goldenFish =
                                        BoolArgumentType.getBool(it, "value")
                                    saveAndReport(
                                        configManager,
                                        if (configManager.config.goldenFish) {
                                            "WARNING: Golden Fish automation is risky; it automatically aims, casts, and repeatedly clicks. Golden Fish enabled."
                                        } else {
                                            "Golden Fish disabled."
                                        }
                                    )
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("bobber-check")
                            .then(
                                doubleArgument("seconds", 5.0, 3600.0).executes {
                                    configManager.config.fishing.bobberCheckAverageSeconds =
                                        DoubleArgumentType.getDouble(it, "seconds")
                                    saveAndReport(
                                        configManager,
                                        "Bobber check interval: ${configManager.config.fishing.bobberCheckAverageSeconds} seconds average (±2 seconds)"
                                    )
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("distribution")
                            .then(
                                doubleArgument("value", 0.0, 1.0).executes {
                                    configManager.config.fishing.timings.distribution =
                                        DoubleArgumentType.getDouble(it, "value")
                                    saveAndReport(configManager, "Timing distribution: ${configManager.config.fishing.timings.distribution}")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("water")
                            .then(
                                doubleArgument("milliseconds", 0.0, 1000.0).executes {
                                    configManager.config.fishing.timings.waterAverage =
                                        DoubleArgumentType.getDouble(it, "milliseconds").toFloat()
                                    saveAndReport(configManager, "Water catch delay: ${configManager.config.fishing.timings.waterAverage} ms")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("lava")
                            .then(
                                doubleArgument("milliseconds", 0.0, 1000.0).executes {
                                    configManager.config.fishing.timings.lavaAverage =
                                        DoubleArgumentType.getDouble(it, "milliseconds").toFloat()
                                    saveAndReport(configManager, "Lava catch delay: ${configManager.config.fishing.timings.lavaAverage} ms")
                                }
                            )
                    )
                    .then(
                        LiteralArgumentBuilder.literal<FabricClientCommandSource>("cast")
                            .then(
                                doubleArgument("milliseconds", 0.0, 1000.0).executes {
                                    configManager.config.fishing.timings.castAverage =
                                        DoubleArgumentType.getDouble(it, "milliseconds").toFloat()
                                    saveAndReport(configManager, "Rod cast delay: ${configManager.config.fishing.timings.castAverage} ms")
                                }
                            )
                    )
            )
        dispatcher.register(root)
    }

    private fun doubleArgument(name: String, min: Double, max: Double) =
        com.mojang.brigadier.builder.RequiredArgumentBuilder.argument<FabricClientCommandSource, Double>(
            name,
            DoubleArgumentType.doubleArg(min, max)
        )

    private fun showConfig(configManager: ConfigManager): Int {
        val fishing = configManager.config.fishing
        ChatHelper.sendToChat("§6tfih fishing configuration")
        ChatHelper.sendToChat("Umberella enabled: ${configManager.config.umberella}")
        ChatHelper.sendToChat("Auto-gg enabled: ${configManager.config.autoGg}")
        ChatHelper.sendToChat("Golden Fish enabled: ${configManager.config.goldenFish}")
        ChatHelper.sendToChat("Enabled: ${fishing.enabled}")
        ChatHelper.sendToChat("Jump mode: ${fishing.jumpMode}")
        ChatHelper.sendToChat("Slugfish delay: ${fishing.slugfishSeconds} seconds (0 = disabled)")
        ChatHelper.sendToChat("Bobber check interval: ${fishing.bobberCheckAverageSeconds} seconds average (±2 seconds)")
        ChatHelper.sendToChat("Timing distribution: ${fishing.timings.distribution}")
        ChatHelper.sendToChat("Water catch delay: ${fishing.timings.waterAverage} ms")
        ChatHelper.sendToChat("Lava catch delay: ${fishing.timings.lavaAverage} ms")
        ChatHelper.sendToChat("Rod cast delay: ${fishing.timings.castAverage} ms")
        ChatHelper.sendToChat("Use /tfih set <enabled|umberella|auto-gg|golden-fish|jump|slugfish|bobber-check|distribution|water|lava|cast> <value> to change a setting.")
        if (configManager.config.goldenFish) {
            ChatHelper.sendToChat("§cWARNING: Golden Fish automation is risky; it automatically aims, casts, and repeatedly clicks.")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun saveAndReport(configManager: ConfigManager, message: String): Int {
        configManager.save()
        ChatHelper.sendToChat(message)
        return Command.SINGLE_SUCCESS
    }
}
