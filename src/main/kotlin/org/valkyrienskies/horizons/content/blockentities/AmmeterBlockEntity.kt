package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.horizons.potato_battery.impl.nodes.AmmeterNode
import kotlin.math.abs

class AmmeterBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.AMMETER.get(), pos, state) {
    var measuredCurrent = 0.0
        private set

    override fun createPowerNode() = AmmeterNode()

    fun updateReading(level: Level) {
        if (level.gameTime % 4L != 0L) return
        val node = powerNode as? AmmeterNode ?: return
        val network = powerNetwork ?: return
        val current = node.getCurrent(network).takeIf { it.isFinite() } ?: 0.0
        if (abs(current - measuredCurrent) > 1e-7) {
            measuredCurrent = current
            syncDevice()
        }
    }

    override fun getUpdateTag(): CompoundTag = super.getUpdateTag().also { it.putDouble("MeasuredCurrent", measuredCurrent) }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        measuredCurrent = tag.getDouble("MeasuredCurrent").takeIf { it.isFinite() } ?: 0.0
    }
}
