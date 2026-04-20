package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;

public final class CircuitComponents {
  private CircuitComponents() {}

  public static final class FixedStepNetwork extends PowerNetworkServer {
    private final double timeStepSeconds;

    public FixedStepNetwork(IPBSolver solver, double timeStepSeconds) {
      super(null, null, solver);
      this.timeStepSeconds = timeStepSeconds;
    }

    @Override
    public double getTimeStepSeconds() {
      return timeStepSeconds;
    }
  }

  public static final class VariableVoltageNode extends PowerNode {
    private volatile double voltage;

    public VariableVoltageNode() {
      super(2);
    }

    public void setVoltage(double voltage) {
      this.voltage = voltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, 1, voltage);
    }
  }

  public static final class FixedVoltageNode extends PowerNode {
    private final double voltage;

    public FixedVoltageNode(double voltage) {
      super(1);
      this.voltage = voltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, voltage);
    }
  }

  public static final class BatteryNode extends PowerNode {
    private final double voltage;

    public BatteryNode(double voltage) {
      super(2);
      this.voltage = voltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, 1, voltage);
    }
  }

  public static final class ResistorNode extends PowerNode {
    private final double resistance;

    public ResistorNode(double resistance) {
      super(2);
      this.resistance = resistance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampResistance(0, 1, resistance);
    }
  }

  public static final class CapacitorNode extends PowerNode {
    private final double capacitance;
    private double previousVoltage;

    public CapacitorNode(double capacitance) {
      super(2);
      this.capacitance = capacitance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double conductance = capacitance / context.getTimeStep();
      context.stampConductance(0, 1, conductance);
      context.stampCurrentSource(1, 0, conductance * previousVoltage);
    }

    public void postStep(PowerNetworkServer network) {
      previousVoltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }
  }

  public static final class InductorNode extends PowerNode {
    private final double inductance;
    private double previousCurrent;

    public InductorNode(double inductance) {
      super(2);
      this.inductance = inductance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double conductance = context.getTimeStep() / inductance;
      context.stampConductance(0, 1, conductance);
      context.stampCurrentSource(0, 1, previousCurrent);
    }

    public void postStep(PowerNetworkServer network) {
      double voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
      previousCurrent += (network.getTimeStepSeconds() / inductance) * voltage;
    }
  }

  public static final class GroundNode extends PowerNode {
    public GroundNode() {
      super(1);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, 0.0);
    }
  }

  public static final class JunctionNode extends PowerNode {
    public JunctionNode(int ports) {
      super(ports);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      for (int port = 1; port < getPorts(); port++) {
        context.stampResistance(0, port, 1.0e-9);
      }
    }
  }
}
