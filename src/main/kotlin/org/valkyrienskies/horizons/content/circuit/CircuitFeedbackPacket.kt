package org.valkyrienskies.horizons.content.circuit

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import org.valkyrienskies.horizons.content.circuit.client.CircuitBoxScreen
import java.util.function.Supplier

data class CircuitFeedbackPacket(val containerId: Int, val message: Component) {
    companion object {
        fun encode(packet: CircuitFeedbackPacket, buffer: FriendlyByteBuf) {
            buffer.writeVarInt(packet.containerId)
            buffer.writeComponent(packet.message)
        }
        fun decode(buffer: FriendlyByteBuf) = CircuitFeedbackPacket(buffer.readVarInt(), buffer.readComponent())
        fun handle(packet: CircuitFeedbackPacket, supplier: Supplier<NetworkEvent.Context>) {
            val context = supplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { CircuitBoxScreen.feedback(packet) } }
            }
            context.packetHandled = true
        }
    }
}
