package org.valkyrienskies.horizons.content.blocks

import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.horizons.Horizons
import thedarkcolour.kotlinforforge.forge.MOD_BUS

object HorizonsBlocks {
    private val blocks = DeferredRegister.create(ForgeRegistries.BLOCKS, Horizons.MOD_ID)
    val BATTERY = blocks.register("battery", ::BatteryBlock)
    val SWITCH = blocks.register("switch", ::SwitchBlock)
    val RESISTOR = blocks.register("resistor", ::ResistorBlock)
    val LAMP = blocks.register("lamp", ::LampBlock)
    val AMMETER = blocks.register("ammeter", ::AmmeterBlock)
    fun register() = blocks.register(MOD_BUS)
}
