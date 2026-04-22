package org.valkyrienskies.horizons.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import org.valkyrienskies.horizons.Horizons
import org.valkyrienskies.horizons.api.foundation.networking.bi_directional.ObjectGrabPacket
import org.valkyrienskies.horizons.api.foundation.networking.client_to_server.ObjectGrabAdjustPacket
import org.valkyrienskies.horizons.api.foundation.networking.server_to_client.ClientboundObjectGrabPacket
import java.util.Optional
import java.util.function.Supplier

@Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")
object HorizonsNetworking {

    @JvmStatic
    val NetworkingChannel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(Horizons.MOD_ID, "main"),
        Supplier { "1" },
        "1"::equals,
        "1"::equals
    )

    init {
        var id = 0
        //Client to Server
        NetworkingChannel.registerMessage(id++, ObjectGrabAdjustPacket::class.java,
            ObjectGrabAdjustPacket::encode, ObjectGrabAdjustPacket::decode, ObjectGrabAdjustPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER))

        NetworkingChannel.registerMessage(id++, ObjectGrabPacket::class.java, ObjectGrabPacket::encode, ObjectGrabPacket::decode, ObjectGrabPacket::handleServer, Optional.of(NetworkDirection.PLAY_TO_SERVER))
        //Server to Client
        NetworkingChannel.registerMessage(id++, ClientboundObjectGrabPacket::class.java, ClientboundObjectGrabPacket::encode, ClientboundObjectGrabPacket::decode, ClientboundObjectGrabPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT))


    }

    @JvmStatic
    fun sendToServer(msg: Any) {
        NetworkingChannel.send(PacketDistributor.SERVER.noArg(), msg)
    }

    @JvmStatic
    fun sendToClient(msg: Any, player: ServerPlayer) {
        NetworkingChannel.send(PacketDistributor.PLAYER.with({ player }), msg)
    }

    @JvmStatic
    fun sendToAllClients(msg: Any) {
        NetworkingChannel.send(PacketDistributor.ALL.noArg(), msg)
    }

    @JvmStatic
    fun register() {

    }
}
