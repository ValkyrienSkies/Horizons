package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.horizons.api.devices.components.ISocket
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity

class DeviceBlockEntity(type: BlockEntityType<*>, pos: BlockPos, blockState: BlockState) : BlockEntity(type, pos, blockState), SocketedBlockEntity {
    override fun getSocket(port: Int): ISocket? {
        TODO("Not yet implemented")
    }

    override fun getPorts(): Int {
        TODO("Not yet implemented")
    }
}
