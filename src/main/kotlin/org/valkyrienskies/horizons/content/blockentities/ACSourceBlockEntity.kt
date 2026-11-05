package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ACVoltageSourceNode

class ACSourceBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.DEBUG_AC_SOURCE.get(), pos, state) {
    override fun createPowerNode() = ACVoltageSourceNode(PEAK_VOLTAGE, FREQUENCY_HZ)

    companion object {
        const val PEAK_VOLTAGE = 12.0
        const val FREQUENCY_HZ = 1.0
    }
}
