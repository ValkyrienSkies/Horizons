package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;

/**
 * Non-rechargeable two-terminal cell. Port 0 is positive, port 1 is negative.
 * Open-circuit voltage is proportional to remaining charge. Internal resistance limits
 * the full-charge short-circuit current; forcing a reverse terminal voltage can exceed it.
 * Stored energy includes the energy subsequently lost to that internal resistance.
 */
public class FiniteBatteryNode extends PowerNode {
  private final double maxVoltage;
  private final double maxCurrent;
  private final double maxEnergyJoules;
  private final double internalResistance;
  private final double chargeCapacityCoulombs;
  private final double capacitance;
  private double stateOfCharge;

  public FiniteBatteryNode(double maxVoltage, double maxCurrent, double maxEnergyJoules) {
    this(maxVoltage, maxCurrent, maxEnergyJoules, 1.0);
  }

  public FiniteBatteryNode(double maxVoltage, double maxCurrent, double maxEnergyJoules, double stateOfCharge) {
    super(2);
    if (!Double.isFinite(maxVoltage) || maxVoltage <= 0
        || !Double.isFinite(maxCurrent) || maxCurrent <= 0
        || !Double.isFinite(maxEnergyJoules) || maxEnergyJoules <= 0
        || !Double.isFinite(stateOfCharge) || stateOfCharge < 0 || stateOfCharge > 1) {
      throw new IllegalArgumentException("Invalid battery parameters");
    }
    this.maxVoltage = maxVoltage;
    this.maxCurrent = maxCurrent;
    this.maxEnergyJoules = maxEnergyJoules;
    this.internalResistance = maxVoltage / maxCurrent;
    // Integral of V(q) dq, where V(q) = Vmax * q / Qmax, is Vmax * Qmax / 2.
    this.chargeCapacityCoulombs = 2 * (maxEnergyJoules / maxVoltage);
    this.capacitance = chargeCapacityCoulombs / maxVoltage;
    if (!Double.isFinite(internalResistance) || internalResistance <= 0
        || !Double.isFinite(chargeCapacityCoulombs) || chargeCapacityCoulombs <= 0
        || !Double.isFinite(capacitance) || capacitance <= 0) {
      throw new IllegalArgumentException("Battery parameters exceed simulation range");
    }
    this.stateOfCharge = stateOfCharge;
  }

  public double getMaxVoltage() { return maxVoltage; }
  public double getMaxCurrent() { return maxCurrent; }
  public double getMaxEnergyJoules() { return maxEnergyJoules; }
  public double getInternalResistance() { return internalResistance; }
  public double getStateOfCharge() { return stateOfCharge; }
  public double getOpenCircuitVoltage() { return maxVoltage * stateOfCharge; }
  public double getStoredEnergyJoules() { return maxEnergyJoules * stateOfCharge * stateOfCharge; }

  @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
  @Override public long getWakeFingerprint() { return Double.doubleToLongBits(stateOfCharge); }
  @Override public double getSuggestedMaxTimeStepSeconds() { return internalResistance * capacitance * 0.01; }

  @Override public void stamp(CircuitStampContext context) {
    // Backward-Euler charge integration: Vterminal = Vprevious - I * (R + dt / C).
    // The companion resistance keeps even large simulation steps from creating energy.
    double conductance = 1 / (internalResistance + context.getTimeStep() / capacitance);
    context.stampConductance(0, 1, conductance);
    context.stampCurrentSource(1, 0, getOpenCircuitVoltage() * conductance);
  }

  @Override public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
    double current = network.getNodeEnergyData(this, 0).getCurrent();
    // External power is absorbed without restoring the reactants of this primary cell.
    if (Double.isFinite(current) && current > 0) {
      stateOfCharge = Math.max(0, stateOfCharge - current * timeStepSeconds / chargeCapacityCoulombs);
    }
  }
}
