@file:Suppress("OVERRIDE_DEPRECATION")

package org.valkyrienskies.horizons.content.circuit

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraftforge.network.NetworkHooks
import org.valkyrienskies.horizons.content.blocks.ElectricalBlock
import org.valkyrienskies.horizons.content.items.DebugCableItem
import org.valkyrienskies.horizons.content.items.pda.PDAItem

class CircuitBoxBlock : ElectricalBlock() {
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = CircuitBoxBlockEntity(pos, state)
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext) = rotate(SHAPE, state)
    override fun use(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        if (player.getItemInHand(hand).item is DebugCableItem || player.getItemInHand(hand).item is PDAItem) return InteractionResult.PASS
        val box = level.getBlockEntity(pos)
        if (player is ServerPlayer && box is CircuitBoxBlockEntity) {
            box.syncDevice()
            NetworkHooks.openScreen(player, box, pos)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        private val SHAPE = Shapes.or(Block.box(2.0, 0.0, 10.0, 14.0, 16.0, 16.0),
            Block.box(0.5, 4.0, 8.0, 2.5, 13.0, 15.25))
    }
}
