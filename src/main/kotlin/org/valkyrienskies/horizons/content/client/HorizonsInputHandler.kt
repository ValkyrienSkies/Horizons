package org.valkyrienskies.horizons.content.client

import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck
import org.valkyrienskies.horizons.api.foundation.networking.bi_directional.ObjectGrabPacket
import org.valkyrienskies.horizons.api.foundation.networking.client_to_server.ObjectGrabAdjustPacket
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.mod.api.getShipManagingBlock
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.toWorldCoordinates

@Mod.EventBusSubscriber
object HorizonsInputHandler {
    @SubscribeEvent
    fun handleMouseScroll(event: InputEvent.MouseScrollingEvent) {
        if (HorizonsClient.localPlayerGrabMode() && HorizonsClient.localPlayerGrabbed() != -1L) {
            if (Minecraft.getInstance().options.keySprint.isDown) {
                val player = Minecraft.getInstance().player
                if (player != null) {
                    val grabber = player as PlayerGrabbingMixinDuck
                    val newDistance = Mth.clamp(grabber.grabbedObjectTarget.distance + event.scrollDelta, 2.0, 40.0)
                    HorizonsNetworking.sendToServer(ObjectGrabAdjustPacket(newDistance, null, null))
                    event.isCanceled = true
                }
            }
        }
    }

    @SubscribeEvent
    fun handleMouseClicked(event: InputEvent.MouseButton.Pre) {
        val player = Minecraft.getInstance().player
        if (HorizonsClient.localPlayerGrabMode() && Minecraft.getInstance().screen == null) {
            if (HorizonsClient.localPlayerGrabbed() != -1L && event.action == 0) {
                if (event.button == 0) {
                    //throw it
                    HorizonsNetworking.sendToServer(ObjectGrabPacket(HorizonsClient.localPlayerGrabbed(), true))
                } else if (event.button == 1) {
                    //release it
                    HorizonsNetworking.sendToServer(ObjectGrabPacket(HorizonsClient.localPlayerGrabbed()))
                }
                (player as PlayerGrabbingMixinDuck).grabbedObjectId = -1L
                //event.isCanceled = true
            } else if (event.action == 0 && event.button == 1) {
                val level = Minecraft.getInstance().level
                val lastHit = Minecraft.getInstance().hitResult
                var shipHit: Ship? = null
                if (lastHit != null && lastHit is BlockHitResult) {
                    shipHit = level.getShipManagingPos(lastHit.blockPos)
                }

                if (player != null && lastHit != null && shipHit != null && level.toWorldCoordinates(lastHit.location)
                        .distanceTo(player.position()) <= 10.0
                ) {
                    (player as PlayerGrabbingMixinDuck).grabbedObjectId = shipHit.id
                    HorizonsNetworking.sendToServer(ObjectGrabPacket(HorizonsClient.localPlayerGrabbed()))
                }
                //event.isCanceled = true
            }
        }
    }
}
