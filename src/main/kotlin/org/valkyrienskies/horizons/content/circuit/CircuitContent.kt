package org.valkyrienskies.horizons.content.circuit

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.common.extensions.IForgeMenuType
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.horizons.api.circuits.CircuitComponents

object CircuitContent {
    private val blocks = DeferredRegister.create(ForgeRegistries.BLOCKS, "horizons")
    private val items = DeferredRegister.create(ForgeRegistries.ITEMS, "horizons")
    private val entities = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "horizons")
    private val menus = DeferredRegister.create(ForgeRegistries.MENU_TYPES, "horizons")

    val BOX = blocks.register("circuit_box", ::CircuitBoxBlock)
    val BOX_ITEM = items.register("circuit_box") { BlockItem(BOX.get(), Item.Properties()) }
    val RESISTOR = items.register("circuit_resistor") { Item(Item.Properties()) }
    val CAPACITOR = items.register("circuit_capacitor") { Item(Item.Properties()) }
    val NMOS = items.register("circuit_nmos") { Item(Item.Properties()) }
    val AND = items.register("circuit_and_gate") { Item(Item.Properties()) }
    val OR = items.register("circuit_or_gate") { Item(Item.Properties()) }
    val SCHEMATIC = items.register("circuit_schematic") { Item(Item.Properties().stacksTo(1)) }
    val ENTITY = entities.register("circuit_box") {
        BlockEntityType.Builder.of(::CircuitBoxBlockEntity, BOX.get()).build(null)
    }
    val MENU = menus.register("circuit_box") {
        IForgeMenuType.create { id, inventory, buffer -> CircuitBoxMenu(id, inventory, buffer.readBlockPos()) }
    }

    fun register(bus: IEventBus) {
        blocks.register(bus)
        items.register(bus)
        entities.register(bus)
        menus.register(bus)
        BuiltinCircuitComponents.initialize()
        bus.addListener(::creativeItems)
        bus.addListener { event: FMLLoadCompleteEvent -> event.enqueueWork { CircuitComponents.freeze() } }
    }

    private fun creativeItems(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(BOX_ITEM.get())
            CircuitComponents.values().filter { it.id().namespace == "horizons" }.forEach { event.accept(it.item().get()) }
        }
    }
}
