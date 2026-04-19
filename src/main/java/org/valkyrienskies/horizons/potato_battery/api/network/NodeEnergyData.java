package org.valkyrienskies.horizons.potato_battery.api.network;

public class NodeEnergyData {

  double voltage;
  double current;
  boolean isSource;

  public NodeEnergyData(double voltage, double current, boolean isSource) {
    this.voltage = voltage;
    this.current = current;
    this.isSource = isSource;
  }

  public NodeEnergyData() {
    this.voltage = 0;
    this.current = 0;
    this.isSource = false;
  }

  public double getPower() {
    return voltage * current;
  }
}
