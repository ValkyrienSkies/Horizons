package org.valkyrienskies.horizons.content.circuit.simulation;

import java.util.List;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

public interface CompositePowerNode extends IPowerNode {
  List<IPowerNode> getChildren();
  void onNetworkAttached(PowerNetworkServer network);
  void onNetworkDetached();
}
