package org.valkyrienskies.horizons.content.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.util.FastColor
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.common.MinecraftForge.EVENT_BUS
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.valkyrienskies.horizons.PotatoBatteryTask
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck
import org.valkyrienskies.horizons.api.foundation.networking.bi_directional.ObjectGrabPacket
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.impl.render.SocketRenderer
import org.valkyrienskies.horizons.potato_battery.impl.client.PowerNetworkClient
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.addGenericListener

import kotlin.concurrent.thread

@Mod.EventBusSubscriber
object HorizonsClient {
    @JvmField
    val SOCKET_RENDERER = SocketRenderer()

    @OnlyIn(Dist.CLIENT)
    @JvmField
    val CLIENT_NETWORK: PowerNetworkClient = PowerNetworkClient()



    @JvmStatic
    fun clientInit(event: FMLClientSetupEvent) {
        // Put anything initialized on forge-side client here.
        EVENT_BUS.register(HorizonsKeybindings)
        EVENT_BUS.register(HorizonsInputHandler)
        EVENT_BUS.addListener(::onClientTick)
        EVENT_BUS.addListener(::onGUIRender)
    }

    @JvmStatic
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) {
            while (HorizonsKeybindings.GRAB_MODE_BIND.get().consumeClick()) {
                println("AAAA")
                val player = Minecraft.getInstance().player
                if (player != null) {
                    val grabber = player as PlayerGrabbingMixinDuck
                    grabber.toggleGrabMode()
                    if (!grabber.inGrabMode()) {
                        HorizonsNetworking.sendToServer(ObjectGrabPacket(HorizonsClient.localPlayerGrabbed()))
                        (player as PlayerGrabbingMixinDuck).grabbedObjectId = -1L
                    }
                }
            }
        }
    }

    @JvmStatic
    fun onGUIRender(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay == VanillaGuiOverlay.FOOD_LEVEL.type() && localPlayerGrabMode()) {
            event.guiGraphics.drawCenteredString(Minecraft.getInstance().font, "Grab Mode: Enabled", event.window.guiScaledWidth/2, event.window.guiScaledHeight/4, FastColor.ARGB32.color(255,0,255,100))
        }
    }

    @JvmStatic
    fun localPlayerGrabMode(): Boolean {
        var toReturn = false
        val player = Minecraft.getInstance().player
        if (player != null) {
            val grabber = player as PlayerGrabbingMixinDuck
            toReturn = grabber.inGrabMode()
        }
        return toReturn
    }

    @JvmStatic
    fun localPlayerGrabbed(): Long {
        var toReturn = -1L
        val player = Minecraft.getInstance().player
        if (player != null) {
            val grabber = player as PlayerGrabbingMixinDuck
            toReturn = grabber.grabbedObjectId
        }
        return toReturn
    }
}
