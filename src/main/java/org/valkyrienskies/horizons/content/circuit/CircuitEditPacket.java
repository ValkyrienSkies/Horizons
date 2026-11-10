package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record CircuitEditPacket(int containerId, int action, CompoundTag data) {
  public static void encode(CircuitEditPacket packet, FriendlyByteBuf buffer) {
    buffer.writeVarInt(packet.containerId);
    buffer.writeVarInt(packet.action);
    buffer.writeNbt(packet.data);
  }

  public static CircuitEditPacket decode(FriendlyByteBuf buffer) {
    return new CircuitEditPacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readNbt());
  }

  public static void handle(CircuitEditPacket packet, Supplier<NetworkEvent.Context> supplier) {
    NetworkEvent.Context context = supplier.get();
    context.enqueueWork(() -> {
      var player = context.getSender();
      if (player != null && packet.data != null && player.containerMenu instanceof CircuitBoxMenu menu
          && menu.containerId == packet.containerId && menu.stillValid(player)) menu.edit(packet.action, packet.data);
    });
    context.setPacketHandled(true);
  }
}
