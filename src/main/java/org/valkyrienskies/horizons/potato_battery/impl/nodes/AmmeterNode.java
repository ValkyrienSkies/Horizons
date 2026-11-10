package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;

/** Ideal series ammeter: a zero-volt source, with positive current flowing from port 0 to port 1. */
public class AmmeterNode extends PowerNode {
  public AmmeterNode() { super(2); }
  @Override public int getVoltageSourceCount() { return 1; }
  @Override public void stamp(CircuitStampContext context) {
    context.stampVoltageSource(0, 0, 1, 0);
  }
  public double getCurrent(IPowerNetwork<?> network) {
    // Port readings describe external current leaving the node; internal current has the opposite sign.
    return -network.getNodeEnergyData(this, 0).getCurrent();
  }
}
