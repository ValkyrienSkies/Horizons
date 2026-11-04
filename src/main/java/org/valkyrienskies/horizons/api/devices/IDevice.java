package org.valkyrienskies.horizons.api.devices;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import java.util.List;

/** Interface for all electrical blocks */
public interface IDevice extends SocketedBlockEntity {
  BlockEntity asBlockEntity();
  DeviceState getDeviceState();
  IPowerNode createPowerNode();
  List<ISocket> createSockets();

  default IPowerNode getPowerNode() { return getDeviceState().node; }
  default IPowerNetwork<?> getPowerNetwork() { return getDeviceState().network; }
  default long getNodeId() { return getDeviceState().nodeId; }
  @Override default List<ISocket> getSockets() {
    DeviceState state = getDeviceState();
    if (state.sockets.isEmpty()) state.sockets = List.copyOf(createSockets());
    return state.sockets;
  }
  @Override default ISocket getSocket(int port) {
    return getSockets().stream().filter(socket -> socket.getPort() == port).findFirst().orElse(null);
  }
  @Override default int getPorts() { return getSockets().size(); }

  default void loadDevice() {
    BlockEntity be = asBlockEntity();
    getSockets();
    if (be.isRemoved() || be.getLevel() == null || be.getLevel().isClientSide || getDeviceState().node != null) return;
    PowerNetworks.getData(be.getLevel()).attach(this);
  }

  /** Permanently delete a device. Call from the block's onRemove when its block type changes. */
  default void removeDevice() {
    var be = asBlockEntity();
    if (be.getLevel() != null && !be.getLevel().isClientSide) PowerNetworks.getData(be.getLevel()).remove(this);
  }

  /** Release the BE reference, preserving the node identity, frozen state, and connections. */
  default void unloadDevice() {
    var be = asBlockEntity();
    if (be.getLevel() != null && !be.getLevel().isClientSide && getDeviceState().network != null) {
      PowerNetworks.getData(be.getLevel()).detach(this);
    }
  }

  default void syncDevice() {
    BlockEntity be = asBlockEntity();
    if (be.getLevel() != null && !be.getLevel().isClientSide) {
      be.setChanged();
      var state = be.getBlockState();
      be.getLevel().sendBlockUpdated(be.getBlockPos(), state, state, 2);
    }
  }
}
