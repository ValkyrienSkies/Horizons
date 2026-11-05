package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.horizons.Horizons
import org.valkyrienskies.horizons.content.blocks.HorizonsBlocks
import thedarkcolour.kotlinforforge.forge.MOD_BUS

object HorizonsBlockEntities {
    private val types = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Horizons.MOD_ID)
    val BATTERY = types.register("battery") { BlockEntityType.Builder.of(::BatteryBlockEntity, HorizonsBlocks.BATTERY.get()).build(null) }
    val DEBUG_AC_SOURCE = types.register("debug_ac_source") { BlockEntityType.Builder.of(::ACSourceBlockEntity, HorizonsBlocks.DEBUG_AC_SOURCE.get()).build(null) }
    val SWITCH = types.register("switch") { BlockEntityType.Builder.of(::SwitchBlockEntity, HorizonsBlocks.SWITCH.get()).build(null) }
    val RESISTOR = types.register("resistor") { BlockEntityType.Builder.of(::ResistorBlockEntity, HorizonsBlocks.RESISTOR.get()).build(null) }
    val LAMP = types.register("lamp") { BlockEntityType.Builder.of(::LampBlockEntity, HorizonsBlocks.LAMP.get()).build(null) }
    val AMMETER = types.register("ammeter") { BlockEntityType.Builder.of(::AmmeterBlockEntity, HorizonsBlocks.AMMETER.get()).build(null) }
    val VOLTAIC_PILE = types.register("voltaic_pile") { BlockEntityType.Builder.of(::VoltaicPileBlockEntity, HorizonsBlocks.VOLTAIC_PILE.get()).build(null) }
    fun register() = types.register(MOD_BUS)
}
