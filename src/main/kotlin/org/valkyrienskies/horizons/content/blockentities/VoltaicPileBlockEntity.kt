package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.horizons.potato_battery.impl.nodes.FiniteBatteryNode

class VoltaicPileBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.VOLTAIC_PILE.get(), pos, state) {
    override fun createPowerNode() = FiniteBatteryNode(MAX_VOLTAGE, MAX_CURRENT, MAX_ENERGY_JOULES)

    companion object {
        const val MAX_VOLTAGE = 12.0
        const val MAX_CURRENT = 1.0
        const val MAX_ENERGY_JOULES = 18000.0
    }
}
