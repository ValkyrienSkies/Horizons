package org.valkyrienskies.horizons.content.circuit.simulation;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;

final class CircuitComponentNodes {
  private CircuitComponentNodes() {}

  static final class Capacitor extends PowerNode {
    private double voltage;
    Capacitor() { super(2); }
    double voltage() { return voltage; }
    void restore(double value) {
      if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid capacitor state");
      voltage = value;
    }
    @Override public void stamp(CircuitStampContext context) {
      double conductance = 0.001 / context.getTimeStep();
      context.stampConductance(0, 1, conductance);
      context.stampCurrentSource(1, 0, conductance * voltage);
    }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public double getSuggestedMaxTimeStepSeconds() { return 0.005; }
    @Override public long getWakeFingerprint() { return Double.doubleToLongBits(voltage); }
    @Override public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }
  }

  static final class Nmos extends PowerNode {
    Nmos() { super(3); }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_NONLINEAR; }
    @Override public void stamp(CircuitStampContext context) {
      int drain = 1;
      int source = 2;
      if (context.getPreviousVoltage(drain) < context.getPreviousVoltage(source)) {
        drain = 2;
        source = 1;
      }
      double vds = context.getPreviousVoltage(drain) - context.getPreviousVoltage(source);
      double vgs = context.getPreviousVoltage(0) - context.getPreviousVoltage(source);
      double overdrive = vgs - 1.0;
      double current;
      double gm;
      double gds;
      if (overdrive <= 0) {
        current = 1e-8 * vds;
        gm = 0;
        gds = 1e-8;
      } else {
        double base = vds < overdrive ? 0.001 * (2 * overdrive * vds - vds * vds) : 0.001 * overdrive * overdrive;
        current = base * (1 + 0.02 * vds);
        gm = 0.002 * Math.min(vds, overdrive) * (1 + 0.02 * vds);
        gds = Math.max(1e-8, (vds < overdrive ? 0.002 * (overdrive - vds) * (1 + 0.02 * vds) : 0) + 0.02 * base);
      }
      context.stampConductance(0, 1, 1e-12);
      context.stampConductance(0, 2, 1e-12);
      context.stampConductance(drain, source, gds);
      context.stampVCCS(drain, source, 0, source, gm);
      context.stampCurrentSource(drain, source, current - gds * vds - gm * vgs);
    }
  }

  static final class Gate extends PowerNode {
    private final boolean and;
    Gate(boolean and) { super(3); this.and = and; }
    @Override public boolean usesImplicitVoltageReference() { return true; }
    @Override public int[] getReferenceTraversalPorts(int enteredPort) { return new int[] {0, 1}; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_NONLINEAR; }
    @Override public void stamp(CircuitStampContext context) {
      boolean first = context.getPreviousVoltage(0) >= 2.5;
      boolean second = context.getPreviousVoltage(1) >= 2.5;
      double output = (and ? first && second : first || second) ? 5 : 0;
      context.stampConductance(0, CircuitStampContext.GROUND, 1e-6);
      context.stampConductance(1, CircuitStampContext.GROUND, 1e-6);
      context.stampConductance(2, CircuitStampContext.GROUND, 100);
      context.stampCurrentSource(CircuitStampContext.GROUND, 2, output * 100);
    }
  }
}
