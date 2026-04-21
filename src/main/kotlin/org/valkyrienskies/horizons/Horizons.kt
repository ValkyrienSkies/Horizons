package org.valkyrienskies.horizons

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.horizons.content.HorizonsSounds
import org.valkyrienskies.horizons.content.client.HorizonsClient
import java.util.logging.Logger

@Mod("horizons")
class Horizons {

    //Deferred Registries
    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)

    // Put RegistryObjects here:

    // end of RegistryObjects

    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus

        HorizonsSounds.register(FMLJavaModLoadingContext.get())

        modEventBus.addListener(::init)
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(HorizonsClient.Companion::clientInit)
        }
    }

    // Helper function, taken from VS2.
    private fun registerBlockAndItem(registryName: String, blockSupplier: () -> Block): RegistryObject<Block> {
        val blockRegistry = BLOCKS.register(registryName, blockSupplier)
        ITEMS.register(registryName) { BlockItem(blockRegistry.get(), Item.Properties()) }
        return blockRegistry
    }

    companion object {
        const val MOD_ID = "horizons"
        @JvmStatic
        val LOGGER = Logger.getLogger("Microplastics Factory")
        @JvmStatic
        fun init (event: FMLCommonSetupEvent) {
            // Put anything initialized on forge-side here.
            //vsApi.physTickEvent.on {
            //}
        }
    }
}
