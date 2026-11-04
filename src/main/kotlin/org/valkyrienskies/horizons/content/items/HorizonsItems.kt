package org.valkyrienskies.horizons.content.items

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.CreativeModeTabs
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.horizons.Horizons
import org.valkyrienskies.horizons.content.blocks.HorizonsBlocks
import org.valkyrienskies.horizons.content.items.pda.PDAItem
import thedarkcolour.kotlinforforge.forge.MOD_BUS

object HorizonsItems {
    private val items = DeferredRegister.create(ForgeRegistries.ITEMS, Horizons.MOD_ID)
    val BATTERY = items.register("battery") { BlockItem(HorizonsBlocks.BATTERY.get(), Item.Properties()) }
    val SWITCH = items.register("switch") { BlockItem(HorizonsBlocks.SWITCH.get(), Item.Properties()) }
    val RESISTOR = items.register("resistor") { BlockItem(HorizonsBlocks.RESISTOR.get(), Item.Properties()) }
    val LAMP = items.register("lamp") { BlockItem(HorizonsBlocks.LAMP.get(), Item.Properties()) }
    val AMMETER = items.register("ammeter") { BlockItem(HorizonsBlocks.AMMETER.get(), Item.Properties()) }
    val DEBUG_CABLE = items.register("debug_cable", ::DebugCableItem)
    val DEBUG_PDA = items.register("debug_pda", ::PDAItem)
    fun register() {
        items.register(MOD_BUS)
        MOD_BUS.addListener { event: BuildCreativeModeTabContentsEvent ->
            if (event.tabKey == CreativeModeTabs.REDSTONE_BLOCKS) {
                listOf(BATTERY, SWITCH, RESISTOR, LAMP, AMMETER, DEBUG_CABLE, DEBUG_PDA).forEach { event.accept(it.get()) }
            }
        }
    }
}
