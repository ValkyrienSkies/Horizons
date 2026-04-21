package org.valkyrienskies.horizons.content.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.valkyrienskies.horizons.PotatoBatteryTask
import org.valkyrienskies.horizons.impl.render.SocketRenderer
import org.valkyrienskies.horizons.potato_battery.impl.client.PowerNetworkClient

import kotlin.concurrent.thread

class HorizonsClient {
    companion object {
        @JvmField
        val SOCKET_RENDERER = SocketRenderer()

        @OnlyIn(Dist.CLIENT)
        @JvmField
        val CLIENT_NETWORK: PowerNetworkClient = PowerNetworkClient()

        @JvmStatic
        fun clientInit(event: FMLClientSetupEvent) {
            // Put anything initialized on forge-side client here.
        }
    }
}
