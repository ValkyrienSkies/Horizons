package org.valkyrienskies.horizons

import net.minecraft.world.level.Level
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.content.HorizonsSounds
import org.valkyrienskies.horizons.content.client.HorizonsClient
import org.valkyrienskies.horizons.api.devices.PowerNetworks
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkEngine
import org.valkyrienskies.horizons.content.blocks.HorizonsBlocks
import org.valkyrienskies.horizons.content.blockentities.HorizonsBlockEntities
import org.valkyrienskies.horizons.content.items.HorizonsItems
import org.valkyrienskies.mod.util.logger
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod("horizons")
object Horizons {

    const val MOD_ID = "horizons"
    @JvmField
    val LOGGER = logger("Microplastics Factory").logger

    private var engine: PowerNetworkEngine? = null

    init {
        FORGE_BUS.addListener(::serverInit)
        FORGE_BUS.addListener(::levelLoaded)
        FORGE_BUS.addListener(::serverStop)
        FORGE_BUS.addListener { _: ServerStoppedEvent -> PowerNetworks.clear() }
        PowerNetworks.registerEvents(FORGE_BUS)
        org.valkyrienskies.horizons.impl.rigging.RigBodyManager.register(FORGE_BUS)
        HorizonsBlocks.register()
        HorizonsItems.register()
        HorizonsBlockEntities.register()
        HorizonsSounds.register()
        org.valkyrienskies.horizons.content.circuit.CircuitContent.register(MOD_BUS)

        MOD_BUS.addListener(::commonInit)
        if (FMLEnvironment.dist.isClient) {
            MOD_BUS.addListener(org.valkyrienskies.horizons.content.circuit.client.CircuitBoxScreen::registerKeys)
            MOD_BUS.addListener(HorizonsClient::clientInit)
            MOD_BUS.addListener(org.valkyrienskies.horizons.content.client.RigBodyRenderer::models)
        }
    }

    @JvmStatic
    fun commonInit (event: FMLCommonSetupEvent) {
        HorizonsNetworking.register()
    }

    @JvmStatic
    fun serverInit (event: ServerStartingEvent) {
        LOGGER.info("The sun is rising...")

        // TODO: make tps configurable
        engine = PowerNetworkEngine({ PowerNetworks.getNetworks() }, 120)

        LOGGER.info("...over the Horizon.")

        if (ModList.get().isLoaded("create")) {
            LOGGER.info("Good morning, Create!")
        }
    }

    @JvmStatic
    fun levelLoaded (event: LevelEvent.Load) {
        val level = event.level as Level
        if (!level.isClientSide) PowerNetworks.get(level)
    }

    @JvmStatic
    fun serverStop (event: ServerStoppingEvent) {
        engine?.close()
        engine = null
    }
}
