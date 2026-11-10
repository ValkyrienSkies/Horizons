@file:Suppress("OVERRIDE_DEPRECATION")

package org.valkyrienskies.horizons.content.blocks

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.horizons.content.blockentities.ACSourceBlockEntity

class ACSourceBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = ACSourceBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)

    override fun appendHoverText(stack: ItemStack, level: BlockGetter?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, level, tooltip, flag)
        tooltip.add(Component.translatable("tooltip.horizons.debug_ac_source", ACSourceBlockEntity.PEAK_VOLTAGE, ACSourceBlockEntity.FREQUENCY_HZ))
    }

    companion object {
        private val SHAPE = Shapes.or(
            Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0),
            Block.box(3.0, 14.0, 6.0, 7.0, 16.0, 10.0),
            Block.box(9.0, 14.0, 6.0, 13.0, 16.0, 10.0)
        )
    }
}
