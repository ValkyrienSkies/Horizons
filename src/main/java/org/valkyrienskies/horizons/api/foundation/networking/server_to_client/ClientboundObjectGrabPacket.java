package org.valkyrienskies.horizons.api.foundation.networking.server_to_client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.valkyrienskies.horizons.content.client.HorizonsClientPacketHandler;

import java.util.function.Supplier;

public class ClientboundObjectGrabPacket {
    public final long grabbedObject;

    public ClientboundObjectGrabPacket(long grabbedObject) {
        this.grabbedObject = grabbedObject;
    }

    public static void handle(ClientboundObjectGrabPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> HorizonsClientPacketHandler.handleGrabPacket(packet))
        );
        ctx.get().setPacketHandled(true);
    }

    public static void encode(ClientboundObjectGrabPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.grabbedObject);
    }

    public static ClientboundObjectGrabPacket decode(FriendlyByteBuf buf) {
        return new ClientboundObjectGrabPacket(buf.readLong());
    }
}
