package org.valkyrienskies.horizons.potato_battery.api.network;

public class NodeEnergyData {

  private double voltage;
  private double current;
  private boolean source;

  public NodeEnergyData(double voltage, double current, boolean source) {
    this.voltage = voltage;
    this.current = current;
    this.source = source;
  }

  public NodeEnergyData() {
    this.voltage = 0;
    this.current = 0;
    this.source = false;
  }

  public double getPower() {
    return voltage * current;
  }

  public double getVoltage() {
    return voltage;
  }

  public void setVoltage(double voltage) {
    this.voltage = voltage;
  }

  public double getCurrent() {
    return current;
  }

  public void setCurrent(double current) {
    this.current = current;
  }

  public boolean isSource() {
    return source;
  }

  public void setSource(boolean source) {
    this.source = source;
  }
}
