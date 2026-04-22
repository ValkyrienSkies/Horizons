package org.valkyrienskies.horizons.content.client

import net.minecraft.client.Minecraft
import net.minecraftforge.network.NetworkEvent
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck
import org.valkyrienskies.horizons.api.foundation.networking.bi_directional.ObjectGrabPacket
import java.util.function.Supplier

object HorizonsClientPacketHandler {
    @JvmStatic
    fun handleGrabPacket(packet: ObjectGrabPacket, ctx: Supplier<NetworkEvent.Context>) {
        val player = Minecraft.getInstance().player
        if (player != null) {
            val grabber = player as PlayerGrabbingMixinDuck
            grabber.grabbedObjectId = packet.grabbedObject
        }
    }
}
