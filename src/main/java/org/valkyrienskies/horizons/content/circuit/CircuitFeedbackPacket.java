package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.valkyrienskies.horizons.content.circuit.client.CircuitBoxScreen;
import java.util.function.Supplier;

public record CircuitFeedbackPacket(int containerId, Component message) {
  public static void encode(CircuitFeedbackPacket packet, FriendlyByteBuf buffer) {
    buffer.writeVarInt(packet.containerId);
    buffer.writeComponent(packet.message);
  }

  public static CircuitFeedbackPacket decode(FriendlyByteBuf buffer) {
    return new CircuitFeedbackPacket(buffer.readVarInt(), buffer.readComponent());
  }

  public static void handle(CircuitFeedbackPacket packet, Supplier<NetworkEvent.Context> supplier) {
    NetworkEvent.Context context = supplier.get();
    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CircuitBoxScreen.feedback(packet)));
    context.setPacketHandled(true);
  }
}
