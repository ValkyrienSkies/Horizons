package org.valkyrienskies.horizons.api.devices;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.impl.devices.components.StandardSocket;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;

/** Thin block-entity adapter for IDevice. Construct/register the node once a server level is available. */
public abstract class DeviceBlockEntity extends BlockEntity implements IDevice {
  private static final Set<DeviceBlockEntity> CLIENT_DEVICES = Collections.newSetFromMap(new WeakHashMap<>());
  private final DeviceState deviceState = new DeviceState();

  protected DeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
  @Override public final BlockEntity asBlockEntity() { return this; }
  @Override public final DeviceState getDeviceState() { return deviceState; }
  public static Set<DeviceBlockEntity> getClientDevices() { return Set.copyOf(CLIENT_DEVICES); }

  @Override public void onLoad() {
    if (isRemoved()) return;
    super.onLoad();
    loadDevice();
    if (level != null && level.isClientSide) CLIENT_DEVICES.add(this);
  }

  @Override public void setRemoved() {
    // Chunk unload and permanent block replacement both call setRemoved. Only onRemove deletes the node.
    unloadDevice();
    if (level != null && level.isClientSide) CLIENT_DEVICES.remove(this);
    super.setRemoved();
  }

  @Override public void onChunkUnloaded() {
    unloadDevice();
    if (level != null && level.isClientSide) CLIENT_DEVICES.remove(this);
    super.onChunkUnloaded();
  }

  @Override protected void saveAdditional(CompoundTag tag) {
    super.saveAdditional(tag);
    tag.putLong("PowerNodeId", getNodeId());
  }

  @Override public void load(CompoundTag tag) {
    super.load(tag);
    deviceState.nodeId = tag.contains("PowerNodeId") ? tag.getLong("PowerNodeId") : -1;
    if (tag.contains("SocketConnections")) {
      for (ISocket socket : getSockets()) {
        if (socket instanceof StandardSocket standard) standard.readClientConnections(tag.getList("SocketConnections", Tag.TAG_COMPOUND));
      }
    }
  }

  @Override public CompoundTag getUpdateTag() {
    CompoundTag tag = super.getUpdateTag();
    tag.putLong("PowerNodeId", getNodeId());
    ListTag connections = new ListTag();
    for (ISocket socket : getSockets()) {
      for (ISocket other : socket.getConnectedSockets()) {
        CompoundTag connection = new CompoundTag();
        connection.putInt("Port", socket.getPort());
        connection.putLong("OtherPos", other.getBlockPos().asLong());
        connection.putInt("OtherPort", other.getPort());
        connections.add(connection);
      }
    }
    // BE packets contain only a render view of active connections. The archive owns durable connections.
    tag.put("SocketConnections", connections);
    return tag;
  }

  @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
