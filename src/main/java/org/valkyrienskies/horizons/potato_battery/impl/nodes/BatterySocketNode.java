package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import javax.annotation.Nullable;

/** Stable two-port identity for a removable cell. Empty or unverified sockets are open circuits. */
public class BatterySocketNode extends PowerNode {
  private @Nullable FiniteBatteryNode battery;
  private String batteryId = "";
  private boolean connected;
  public BatterySocketNode() { super(2); }
  public @Nullable FiniteBatteryNode getBattery() { return battery; }
  public String getBatteryId() { return batteryId; }
  public boolean isConnected() { return connected; }
  /** Restore charge without energizing the circuit before the physical attachment is verified. */
  public void retain(String id, FiniteBatteryNode cell) { batteryId = id; battery = cell; connected = false; }
  public void connect(String id, FiniteBatteryNode cell) { retain(id, cell); connected = true; }
  public void disconnect() { connected = false; battery = null; batteryId = ""; }
  @Override public PowerNodeSimulationMode getSimulationMode() {
    return connected ? PowerNodeSimulationMode.DYNAMIC_LINEAR : PowerNodeSimulationMode.STATIC_LINEAR;
  }
  @Override public long getWakeFingerprint() { return connected && battery != null ? battery.getWakeFingerprint() : 0; }
  @Override public double getSuggestedMaxTimeStepSeconds() {
    return connected && battery != null ? battery.getSuggestedMaxTimeStepSeconds() : Double.POSITIVE_INFINITY;
  }
  @Override public void stamp(CircuitStampContext context) { if (connected && battery != null) battery.stamp(context); }
  @Override public void onSubstepComplete(IPowerNetwork<?> network, double dt) {
    if (connected && battery != null) battery.discharge(network.getNodeEnergyData(this, 0).getCurrent(), dt);
  }
}
