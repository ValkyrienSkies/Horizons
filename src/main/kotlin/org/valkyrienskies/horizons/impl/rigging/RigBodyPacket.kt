package org.valkyrienskies.horizons.impl.rigging

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import org.valkyrienskies.horizons.content.client.RigBodyRenderer
import java.util.function.Supplier

class RigBodyPacket(val dimension: ResourceLocation, val data: CompoundTag) {
    companion object {
        fun encode(packet: RigBodyPacket, buf: FriendlyByteBuf) { buf.writeResourceLocation(packet.dimension); buf.writeNbt(packet.data) }
        fun decode(buf: FriendlyByteBuf) = RigBodyPacket(buf.readResourceLocation(), buf.readNbt() ?: CompoundTag())
        fun handle(packet: RigBodyPacket, context: Supplier<NetworkEvent.Context>) {
            context.get().enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { RigBodyRenderer.receive(packet) } } }
            context.get().packetHandled = true
        }
    }
}
