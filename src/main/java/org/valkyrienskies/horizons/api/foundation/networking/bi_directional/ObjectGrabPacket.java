package org.valkyrienskies.horizons.api.foundation.networking.bi_directional;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck;
import org.valkyrienskies.horizons.content.HorizonsNetworking;
import org.valkyrienskies.horizons.api.foundation.networking.server_to_client.ClientboundObjectGrabPacket;

import java.util.function.Supplier;

public class ObjectGrabPacket {
    public final long grabbedObject;
    public final boolean yeet;

    public ObjectGrabPacket(long grabbedId) {
        grabbedObject = grabbedId;
        yeet = false;
    }

    public ObjectGrabPacket(long grabbedId, boolean throwIt) {
        grabbedObject = grabbedId;
        yeet = throwIt;
    }

    public static void handleServer(ObjectGrabPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                PlayerGrabbingMixinDuck grabber = (PlayerGrabbingMixinDuck) sender;
                if (packet.yeet) {
                    grabber.tryThrow(packet.grabbedObject);
                } else {
                    boolean success = grabber.tryGrab(packet.grabbedObject);
                    if (success) {
                        HorizonsNetworking.sendToClient(new ClientboundObjectGrabPacket(grabber.getGrabbedObjectId()), sender);
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static void encode(ObjectGrabPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.grabbedObject);
        buf.writeBoolean(packet.yeet);
    }

    public static ObjectGrabPacket decode(FriendlyByteBuf buf) {
        return new ObjectGrabPacket(buf.readLong(), buf.readBoolean());
    }
}
