package org.valkyrienskies.horizons.api.foundation.networking.client_to_server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.valkyrienskies.horizons.api.foundation.HorizonsUtils;
import org.valkyrienskies.horizons.content.client.HorizonsClientPacketHandler;
import org.valkyrienskies.horizons.content.ship_grabbing.PlayerGrabbingMixinDuck;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.networking.VSGamePackets;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class ObjectGrabAdjustPacket {
    public final double newDistance;
    @Nullable
    public final Vector3dc newPosition;
    @Nullable
    public final Quaterniondc newRotation;

    private final boolean hasNewDistance;
    private final boolean hasNewPosition;
    private final boolean hasNewRotation;

    public ObjectGrabAdjustPacket(double distance, @Nullable Vector3dc position, @Nullable Quaterniondc rotation) {
        newDistance = distance;
        hasNewDistance = distance >= 0.0;
        newPosition = position;
        hasNewPosition = position != null;
        newRotation = rotation;
        hasNewRotation = rotation != null;
    }

    public static void handle(ObjectGrabAdjustPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();

            if (sender != null) {
                PlayerGrabbingMixinDuck grabber = (PlayerGrabbingMixinDuck) sender;
                grabber.setGrabbedObjectTarget(packet.newDistance, packet.newPosition, packet.newRotation);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static void encode(ObjectGrabAdjustPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.hasNewDistance);
        if (packet.hasNewDistance) {
            buf.writeDouble(packet.newDistance);
        }
        buf.writeBoolean(packet.hasNewPosition);
        if (packet.hasNewPosition) {
            assert packet.newPosition != null;
            HorizonsUtils.writeVector3d(packet.newPosition, buf);
        }
        buf.writeBoolean(packet.hasNewRotation);
        if (packet.hasNewRotation) {
            assert packet.newRotation != null;
            HorizonsUtils.writeQuaterniond(packet.newRotation, buf);
        }
    }

    public static ObjectGrabAdjustPacket decode(FriendlyByteBuf buf) {
        double distance = -1.0;
        Vector3dc position = null;
        Quaterniondc rotation = null;
        boolean hasDistance = buf.readBoolean();
        if (hasDistance) {
            distance = buf.readDouble();
        }
        boolean hasPosition = buf.readBoolean();
        if (hasPosition) {
            position = HorizonsUtils.readVector3d(buf);
        }
        boolean hasRotation = buf.readBoolean();
        if (hasRotation) {
            rotation = HorizonsUtils.readQuaterniond(buf);
        }
        return new ObjectGrabAdjustPacket(distance, position, rotation);
    }
}
