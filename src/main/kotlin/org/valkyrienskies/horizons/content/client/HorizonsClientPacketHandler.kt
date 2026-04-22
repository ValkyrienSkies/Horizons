package org.valkyrienskies.horizons.content.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck
import org.valkyrienskies.horizons.api.foundation.networking.server_to_client.ClientboundObjectGrabPacket

object HorizonsClientPacketHandler {
    @JvmStatic
    fun handleGrabPacket(packet: ClientboundObjectGrabPacket) {
        val player = Minecraft.getInstance().player
        if (player != null) {
            val grabber = player as PlayerGrabbingMixinDuck
            grabber.grabbedObjectId = packet.grabbedObject
        }
    }
}
