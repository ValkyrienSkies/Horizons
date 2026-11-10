package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;

public class ResistorNode extends PowerNode {
  private final double resistance;

  public ResistorNode(double resistance) {
    super(2);
    if (!Double.isFinite(resistance) || resistance <= 0) throw new IllegalArgumentException("Invalid resistance");
    this.resistance = resistance;
  }

  public double getResistance() { return resistance; }
  public double getDissipatedPower(IPowerNetwork<?> network) {
    double voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    return voltage * voltage / resistance;
  }
  @Override public void stamp(CircuitStampContext context) {
    context.stampResistance(0, 1, resistance);
  }
}
