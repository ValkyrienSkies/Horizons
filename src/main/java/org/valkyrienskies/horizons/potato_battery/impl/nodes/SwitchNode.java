package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;

public class SwitchNode extends PowerNode {
  private boolean closed;

  public SwitchNode(boolean closed) {
    super(2);
    this.closed = closed;
  }

  /** Mutate through IPowerNetwork.updateTopology when registered. */
  public void setClosed(boolean closed) { this.closed = closed; }
  public boolean isClosed() { return closed; }
  @Override public long getWakeFingerprint() { return closed ? 1 : 0; }
  @Override public void stamp(CircuitStampContext context) {
    if (closed) context.stampResistance(0, 1, 1.0e-6);
  }
}
