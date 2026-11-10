package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;

/** Ideal two-terminal voltage source. Port 0 is positive, port 1 is negative. */
public class VoltageSourceNode extends PowerNode {
  private final double voltage;

  public VoltageSourceNode(double voltage) {
    super(2);
    if (!Double.isFinite(voltage)) throw new IllegalArgumentException("Invalid voltage");
    this.voltage = voltage;
  }

  public double getVoltage() { return voltage; }
  @Override public int getVoltageSourceCount() { return 1; }
  @Override public void stamp(CircuitStampContext context) {
    context.stampVoltageSource(0, 0, 1, voltage);
  }
}
