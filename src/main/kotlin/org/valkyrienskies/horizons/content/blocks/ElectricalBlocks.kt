@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")

package org.valkyrienskies.horizons.content.blocks

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.horizons.api.devices.IDevice
import org.valkyrienskies.horizons.content.blockentities.*
import org.valkyrienskies.horizons.content.items.DebugCableItem
import org.valkyrienskies.horizons.content.items.pda.PDAItem

/** Shared block lifecycle, facing, and shape rotation for electrical devices. */
abstract class ElectricalBlock(properties: BlockBehaviour.Properties = BlockBehaviour.Properties.of().strength(2f).noOcclusion()) : BaseEntityBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH)) }
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(BlockStateProperties.HORIZONTAL_FACING) }
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.horizontalDirection.opposite)
    override fun getRenderShape(state: BlockState) = RenderShape.MODEL
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moving: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) (level.getBlockEntity(pos) as? IDevice)?.removeDevice()
        super.onRemove(state, level, pos, newState, moving)
    }

    protected fun rotate(shape: VoxelShape, state: BlockState): VoxelShape {
        var result = shape
        val turns = when (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.core.Direction.EAST -> 1
            net.minecraft.core.Direction.SOUTH -> 2
            net.minecraft.core.Direction.WEST -> 3
            else -> 0
        }
        repeat(turns) {
            var rotated = Shapes.empty()
            result.forAllBoxes { x1, y1, z1, x2, y2, z2 -> rotated = Shapes.or(rotated, Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)) }
            result = rotated
        }
        return result
    }
}

/* Debug blocks. Remove later. */
class BatteryBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = BatteryBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)
    companion object {
        private val SHAPE = Shapes.or(Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0), Block.box(3.0, 14.0, 6.0, 7.0, 16.0, 10.0), Block.box(9.0, 14.0, 6.0, 13.0, 16.0, 10.0))
    }
}

class SwitchBlock : ElectricalBlock() {
    init { registerDefaultState(defaultBlockState().setValue(BlockStateProperties.POWERED, false)) }
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { super.createBlockStateDefinition(builder); builder.add(BlockStateProperties.POWERED) }
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = SwitchBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)
    override fun use(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        if (player.getItemInHand(hand).item is DebugCableItem || player.getItemInHand(hand).item is PDAItem) return InteractionResult.PASS
        if (!level.isClientSide) {
            val closed = !state.getValue(BlockStateProperties.POWERED)
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, closed), 3)
            (level.getBlockEntity(pos) as? SwitchBlockEntity)?.setClosed(closed)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    companion object {
        private val SHAPE = Shapes.or(Block.box(4.0, 0.0, 3.0, 12.0, 3.0, 13.0), Block.box(4.0, 3.0, 3.0, 12.0, 9.0, 13.0), Block.box(3.0, 0.5, 7.0, 4.0, 2.5, 9.0), Block.box(12.0, 0.5, 7.0, 13.0, 2.5, 9.0))
    }
}

class ResistorBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = ResistorBlockEntity(pos, state)
}

class AmmeterBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = AmmeterBlockEntity(pos, state)
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return createTickerHelper(type, HorizonsBlockEntities.AMMETER.get(), BlockEntityTicker { tickLevel, _, _, meter -> meter.updateReading(tickLevel) })
    }
}

class LampBlock : ElectricalBlock(BlockBehaviour.Properties.of().strength(2f).noOcclusion().lightLevel { it.getValue(LIGHT) }) {
    init { registerDefaultState(defaultBlockState().setValue(LIGHT, 0)) }
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { super.createBlockStateDefinition(builder); builder.add(LIGHT) }
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = LampBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = rotate(SHAPE, state)
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return createTickerHelper(type, HorizonsBlockEntities.LAMP.get(), BlockEntityTicker { tickLevel, pos, tickState, lamp -> lamp.updateLight(tickLevel, pos, tickState) })
    }
    companion object {
        @JvmField val LIGHT: IntegerProperty = IntegerProperty.create("light", 0, 15)
        private val SHAPE = Shapes.or(
            Block.box(6.0, 0.0, 6.0, 10.0, 1.0, 10.0),
            Block.box(5.0, 1.0, 5.0, 11.0, 4.0, 11.0),
            Block.box(7.0, 4.0, 7.0, 9.0, 5.0, 9.0),
            Block.box(3.0, 5.0, 3.0, 13.0, 11.0, 13.0),
            Block.box(6.0, 2.0, 11.0, 7.0, 3.0, 12.0),
            Block.box(9.0, 2.0, 11.0, 10.0, 3.0, 12.0)
        )
    }
}
