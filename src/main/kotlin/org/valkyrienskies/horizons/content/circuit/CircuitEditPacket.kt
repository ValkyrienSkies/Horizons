package org.valkyrienskies.horizons.content.circuit

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

data class CircuitEditPacket(val containerId: Int, val action: Int, val data: CompoundTag?) {
    companion object {
        fun encode(packet: CircuitEditPacket, buffer: FriendlyByteBuf) {
            buffer.writeVarInt(packet.containerId)
            buffer.writeVarInt(packet.action)
            buffer.writeNbt(packet.data)
        }
        fun decode(buffer: FriendlyByteBuf) = CircuitEditPacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readNbt())
        fun handle(packet: CircuitEditPacket, supplier: Supplier<NetworkEvent.Context>) {
            val context = supplier.get()
            context.enqueueWork {
                val player = context.sender
                val menu = player?.containerMenu as? CircuitBoxMenu
                if (menu != null && packet.data != null && menu.containerId == packet.containerId && menu.stillValid(player))
                    menu.edit(packet.action, packet.data)
            }
            context.packetHandled = true
        }
    }
}
