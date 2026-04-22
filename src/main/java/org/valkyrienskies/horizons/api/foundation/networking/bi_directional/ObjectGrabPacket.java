package org.valkyrienskies.horizons.api.foundation.networking.bi_directional;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck;
import org.valkyrienskies.horizons.content.HorizonsNetworking;
import org.valkyrienskies.horizons.content.client.HorizonsClientPacketHandler;

import java.util.function.Supplier;

public class ObjectGrabPacket {
    public final long grabbedObject;

    public ObjectGrabPacket(long grabbedId) {
        grabbedObject = grabbedId;
    }

    public static void handleServer(ObjectGrabPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                PlayerGrabbingMixinDuck grabber = (PlayerGrabbingMixinDuck) sender;
                boolean success = grabber.tryGrab(packet.grabbedObject);
                if (success) {
                    HorizonsNetworking.sendToClient(new ObjectGrabPacket(grabber.getGrabbedObjectId()), sender);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static void handleClient(ObjectGrabPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> HorizonsClientPacketHandler.handleGrabPacket(packet, ctx));
        });
        ctx.get().setPacketHandled(true);
    }

    public static void encode(ObjectGrabPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.grabbedObject);
    }

    public static ObjectGrabPacket decode(FriendlyByteBuf buf) {
        return new ObjectGrabPacket(buf.readLong());
    }
}
