@file:Suppress("OVERRIDE_DEPRECATION")

package org.valkyrienskies.horizons.content.blocks

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.horizons.content.blockentities.VoltaicPileBlockEntity

class VoltaicPileBlock : ElectricalBlock(BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()) {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = VoltaicPileBlockEntity(pos, state)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)

    companion object {
        private val SHAPE = Shapes.or(
            Block.box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0),
            Block.box(2.0, 2.0, 2.0, 14.0, 13.0, 14.0),
            Block.box(1.0, 13.0, 1.0, 15.0, 14.0, 15.0),
            Block.box(3.0, 14.0, 6.0, 7.0, 16.0, 10.0),
            Block.box(9.0, 14.0, 6.0, 13.0, 16.0, 10.0)
        )
    }
}
