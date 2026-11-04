package org.valkyrienskies.horizons.content.items

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import org.valkyrienskies.horizons.api.devices.IDevice
import org.valkyrienskies.horizons.api.devices.components.SocketConnectionException
import org.valkyrienskies.horizons.api.devices.components.SocketTargeting

class DebugCableItem : Item(Properties().stacksTo(1)) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(context.clickedPos) ?: return InteractionResult.PASS
        if (be !is IDevice) return InteractionResult.PASS
        val socket = SocketTargeting.find(be, player.eyePosition, player.eyePosition.add(player.lookAngle.scale(player.getAttributeValue(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get()) + 0.5)))
        if (socket == null) {
            player.displayClientMessage(Component.translatable("message.horizons.aim_at_socket"), true)
            return InteractionResult.CONSUME
        }
        val tag = context.itemInHand.orCreateTag
        val dimension = level.dimension().location().toString()
        if (!tag.contains("CableStart")) {
            tag.putLong("CableStart", socket.blockPos.asLong())
            tag.putInt("CablePort", socket.port)
            tag.putLong("CableNode", be.nodeId)
            tag.putString("CableDimension", dimension)
            player.displayClientMessage(Component.translatable("message.horizons.socket_selected", socket.port), true)
            return InteractionResult.CONSUME
        }
        val startPos = BlockPos.of(tag.getLong("CableStart"))
        val first = if (tag.getString("CableDimension") == dimension && level.hasChunkAt(startPos)) level.getBlockEntity(startPos) as? IDevice else null
        val start = if (first?.nodeId == tag.getLong("CableNode")) first.getSocket(tag.getInt("CablePort")) else null
        if (start == null) {
            tag.remove("CableStart")
            player.displayClientMessage(Component.translatable("message.horizons.selection_expired"), true)
            return InteractionResult.CONSUME
        }
        if (start === socket) {
            tag.remove("CableStart")
            player.displayClientMessage(Component.translatable("message.horizons.selection_cleared"), true)
            return InteractionResult.CONSUME
        }
        try {
            val disconnected = start.isConnectedTo(socket)
            if (disconnected) start.disconnect(socket) else start.connect(socket)
            tag.remove("CableStart")
            player.displayClientMessage(Component.translatable(if (disconnected) "message.horizons.cable_removed" else "message.horizons.cable_connected"), true)
        } catch (error: SocketConnectionException) {
            player.displayClientMessage(Component.translatable(if (!error.inRange) "message.horizons.out_of_range" else "message.horizons.connection_failed"), true)
        }
        return InteractionResult.CONSUME
    }
}
