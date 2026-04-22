package org.valkyrienskies.horizons

import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.content.HorizonsSounds
import org.valkyrienskies.horizons.content.client.HorizonsClient
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer
import org.valkyrienskies.horizons.potato_battery.impl.client.PowerNetworkClient
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.util.logger
import kotlin.concurrent.thread
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod("horizons")
object Horizons {

    const val MOD_ID = "horizons"
    @JvmField
    val LOGGER = logger("Microplastics Factory").logger

    //Deferred Registries
    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)

    var NETWORKS: HashMap<DimensionId, IPowerNetwork<*>> = hashMapOf()
    var ENGINE: PotatoBatteryTask? = null
    var engineThread: Thread? = null

    var networkRunning = !FMLEnvironment.dist.isClient

    // Put RegistryObjects here:

    // end of RegistryObjects

    init {
        HorizonsSounds.register()

        MOD_BUS.addListener(::commonInit)
        if (FMLEnvironment.dist.isClient) {
            MOD_BUS.addListener(HorizonsClient::clientInit)
        }
    }

    // Helper function, taken from VS2.
    private fun registerBlockAndItem(registryName: String, blockSupplier: () -> Block): RegistryObject<Block> {
        val blockRegistry = BLOCKS.register(registryName, blockSupplier)
        ITEMS.register(registryName) { BlockItem(blockRegistry.get(), Item.Properties()) }
        return blockRegistry
    }

    @JvmStatic
    fun commonInit (event: FMLCommonSetupEvent) {
        // Put anything initialized on forge-side here.
        HorizonsNetworking.register()
    }

    @JvmStatic
    @SubscribeEvent
    fun serverInit (event: ServerStartingEvent) {
        LOGGER.info("The sun is rising...")

        ENGINE = PotatoBatteryTask(NETWORKS, 120) //todo: tps config
        engineThread = thread(start = true, priority = 7, name = "Potato Battery Thread") {
            ENGINE!!
        }

        LOGGER.info("...over the Horizon.")

        if (ModList.get().isLoaded("create")) {
            LOGGER.info("Good morning, Create!")
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun levelLoaded (event: LevelEvent.Load) {
        val level = event.level as Level
        if (!NETWORKS.containsKey((event.level as Level).dimensionId)) {
            NETWORKS[(event.level as Level).dimensionId] = if (event.level.isClientSide) PowerNetworkClient() else PowerNetworkServer()
        }

        vsApi.physTickEvent.on { physTickEvent ->
            level.players().forEach { player ->
                (player as PlayerGrabbingMixinDuck).physTick(physTickEvent.world)
            }
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun serverStop (event: ServerStoppingEvent) {
        ENGINE?.killTask = true
    }
}
