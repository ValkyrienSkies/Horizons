@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
package org.valkyrienskies.horizons.content.blocks

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.*
import org.valkyrienskies.horizons.content.blockentities.*

class AccumulatorSocketBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = AccumulatorSocketBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(COLLISION_SHAPE, state)
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moving: Boolean) {
        if (!state.`is`(newState.block)) (level.getBlockEntity(pos) as? AccumulatorSocketBlockEntity)?.disconnect()
        super.onRemove(state, level, pos, newState, moving)
    }
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null else createTickerHelper(type, HorizonsBlockEntities.ACCUMULATOR_SOCKET.get(), BlockEntityTicker { _, _, _, dock -> dock.tick() })
    companion object {
        private val COLLISION_SHAPE = Shapes.or(Block.box(0.0,0.0,0.0,16.0,1.0,16.0),
            Block.box(0.0,1.0,0.0,16.0,6.0,1.0), Block.box(0.0,1.0,15.0,16.0,6.0,16.0),
            Block.box(0.0,1.0,1.0,1.0,6.0,15.0), Block.box(15.0,1.0,1.0,16.0,6.0,15.0))
        private val SHAPE = Shapes.or(COLLISION_SHAPE,
            Block.box(3.0,1.0,6.0,7.0,2.0,10.0), Block.box(9.0,1.0,6.0,13.0,2.0,10.0))
    }
}
