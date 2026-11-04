package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.horizons.api.devices.DeviceBlockEntity
import org.valkyrienskies.horizons.api.devices.components.ISocket
import org.valkyrienskies.horizons.impl.devices.components.StandardSocket
import org.valkyrienskies.horizons.content.blocks.LampBlock
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode
import org.valkyrienskies.horizons.potato_battery.impl.nodes.SwitchNode
import org.valkyrienskies.horizons.potato_battery.impl.nodes.VoltageSourceNode
import kotlin.math.roundToInt

/* Debug classes. Remove later */
abstract class ElectricalBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : DeviceBlockEntity(type, pos, state) {
    override fun createSockets(): List<ISocket> {
        val bounds = if (this is SwitchBlockEntity) listOf(AABBd(3.0/16, 0.5/16, 7.0/16, 4.0/16, 2.5/16, 9.0/16), AABBd(12.0/16, 0.5/16, 7.0/16, 13.0/16, 2.5/16, 9.0/16))
        else listOf(AABBd(3.0/16, 14.0/16, 6.0/16, 7.0/16, 1.0, 10.0/16), AABBd(9.0/16, 14.0/16, 6.0/16, 13.0/16, 1.0, 10.0/16))
        val turns = when (blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)) { Direction.EAST -> 1; Direction.SOUTH -> 2; Direction.WEST -> 3; else -> 0 }
        return bounds.mapIndexed { port, original ->
            var box = original
            repeat(turns) { box = AABBd(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX) }
            val center = Vector3d((box.minX + box.maxX)/2, box.maxY, (box.minZ + box.maxZ)/2)
            StandardSocket(port, Int.MAX_VALUE, this, center, Vector3d(0.0, 1.0, 0.0), box)
        }
    }
}

class BatteryBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.BATTERY.get(), pos, state) {
    override fun createPowerNode() = VoltageSourceNode(12.0)
}
class ResistorBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.RESISTOR.get(), pos, state) {
    override fun createPowerNode() = ResistorNode(10.0)
}
class SwitchBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.SWITCH.get(), pos, state) {
    override fun createPowerNode() = SwitchNode(blockState.getValue(BlockStateProperties.POWERED))
    fun setClosed(closed: Boolean) {
        val node = powerNode as? SwitchNode ?: return
        powerNetwork?.updateTopology { node.setClosed(closed) }
    }
}
class LampBlockEntity(pos: BlockPos, state: BlockState) : ElectricalBlockEntity(HorizonsBlockEntities.LAMP.get(), pos, state) {
    override fun createPowerNode() = ResistorNode(1.44)
    fun updateLight(level: Level, pos: BlockPos, state: BlockState) {
        val node = powerNode as? ResistorNode ?: return
        val network = powerNetwork ?: return
        val watts = node.getDissipatedPower(network)
        val light = if (watts.isFinite()) (watts / 100.0 * 15).roundToInt().coerceIn(0, 15) else 0
        if (state.getValue(LampBlock.LIGHT) != light) level.setBlock(pos, state.setValue(LampBlock.LIGHT, light), 3)
    }
}
