package org.valkyrienskies.horizons.api.devices;

import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import java.util.List;

/** Per-instance state for IDevice's default behavior, including non-block-entity implementations. */
public final class DeviceState {
  public long nodeId = -1;
  public IPowerNetwork<?> network;
  public IPowerNode node;
  public List<ISocket> sockets = List.of();
}
