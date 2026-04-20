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
  // Nonlinear devices below use companion-model linearization around the previous timestep's
  // voltages (read via context.getPreviousVoltage). Because the solver performs a single
  // linear solve per timestep, this is effectively one Newton iteration per physTick; with the
  // full Jacobian expressed via stampConductance + stampVCCS, convergence is quadratic near
  // the operating point and steady state is reached in a few ticks.

  public static final class DiodeNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-12;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double FORWARD_CLAMP = 0.8;

    private final double saturationCurrent;
    private final double thermalVoltage;

    public DiodeNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE);
    }

    public DiodeNode(double saturationCurrent, double thermalVoltage) {
      super(2);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double vd = context.getPreviousVoltage(0) - context.getPreviousVoltage(1);
      if (vd > FORWARD_CLAMP) {
        vd = FORWARD_CLAMP;
      }
      double exp = Math.exp(vd / thermalVoltage);
      double id = saturationCurrent * (exp - 1.0);
      double gd = (saturationCurrent / thermalVoltage) * exp;
      context.stampConductance(0, 1, gd);
      context.stampCurrentSource(0, 1, id - gd * vd);
    }
  }

  public static final class NPNTransistorNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_BETA = 100.0;
    private static final double FORWARD_CLAMP = 0.8;

    private final double saturationCurrent;
    private final double thermalVoltage;
    private final double alphaF;

    public NPNTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_BETA);
    }

    public NPNTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      super(3);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
      this.alphaF = beta / (beta + 1.0);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double vbe = context.getPreviousVoltage(0) - context.getPreviousVoltage(2);
      if (vbe > FORWARD_CLAMP) {
        vbe = FORWARD_CLAMP;
      }
      double exp = Math.exp(vbe / thermalVoltage);
      double iForward = saturationCurrent * (exp - 1.0);
      double gbe = (saturationCurrent / thermalVoltage) * exp;

      double baseConductance = (1.0 - alphaF) * gbe;
      context.stampConductance(0, 2, baseConductance);
      context.stampCurrentSource(0, 2, (1.0 - alphaF) * iForward - baseConductance * vbe);

      double collectorTransconductance = alphaF * gbe;
      context.stampVCCS(1, 2, 0, 2, collectorTransconductance);
      context.stampCurrentSource(1, 2, alphaF * iForward - collectorTransconductance * vbe);
    }
  }

  public static final class PNPTransistorNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_BETA = 100.0;
    private static final double FORWARD_CLAMP = 0.8;

    private final double saturationCurrent;
    private final double thermalVoltage;
    private final double alphaF;

    public PNPTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_BETA);
    }

    public PNPTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      super(3);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
      this.alphaF = beta / (beta + 1.0);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double veb = context.getPreviousVoltage(2) - context.getPreviousVoltage(0);
      if (veb > FORWARD_CLAMP) {
        veb = FORWARD_CLAMP;
      }
      double exp = Math.exp(veb / thermalVoltage);
      double iForward = saturationCurrent * (exp - 1.0);
      double geb = (saturationCurrent / thermalVoltage) * exp;

      double baseConductance = (1.0 - alphaF) * geb;
      context.stampConductance(0, 2, baseConductance);
      context.stampCurrentSource(2, 0, (1.0 - alphaF) * iForward - baseConductance * veb);

      double collectorTransconductance = alphaF * geb;
      context.stampVCCS(2, 1, 2, 0, collectorTransconductance);
      context.stampCurrentSource(2, 1, alphaF * iForward - collectorTransconductance * veb);
    }
  }

  public static final class NMOSTransistorNode extends PowerNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double GATE_LEAKAGE = 1.0e-12;

    private final double thresholdVoltage;
    private final double transconductance;

    public NMOSTransistorNode() {
      this(DEFAULT_THRESHOLD, DEFAULT_TRANSCONDUCTANCE);
    }

    public NMOSTransistorNode(double thresholdVoltage, double transconductance) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.transconductance = transconductance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double vd = context.getPreviousVoltage(0);
      double vg = context.getPreviousVoltage(1);
      double vs = context.getPreviousVoltage(2);
      double vgs = vg - vs;
      double vds = vd - vs;
      double vov = vgs - thresholdVoltage;

      double id;
      double gds;
      double gm;
      if (vov <= 0.0 || vds < 0.0) {
        id = 0.0;
        gds = 0.0;
        gm = 0.0;
      } else if (vds < vov) {
        id = transconductance * (2.0 * vov * vds - vds * vds);
        gds = 2.0 * transconductance * (vov - vds);
        gm = 2.0 * transconductance * vds;
      } else {
        id = transconductance * vov * vov;
        gds = 0.0;
        gm = 2.0 * transconductance * vov;
      }

      context.stampConductance(0, 2, gds);
      context.stampVCCS(0, 2, 1, 2, gm);
      context.stampCurrentSource(0, 2, id - gds * vds - gm * vgs);
      context.stampConductance(1, 2, GATE_LEAKAGE);
    }
  }

  public static final class PMOSTransistorNode extends PowerNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double GATE_LEAKAGE = 1.0e-12;

    private final double thresholdVoltage;
    private final double transconductance;

    public PMOSTransistorNode() {
      this(DEFAULT_THRESHOLD, DEFAULT_TRANSCONDUCTANCE);
    }

    public PMOSTransistorNode(double thresholdVoltage, double transconductance) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.transconductance = transconductance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double vd = context.getPreviousVoltage(0);
      double vg = context.getPreviousVoltage(1);
      double vs = context.getPreviousVoltage(2);
      double vsg = vs - vg;
      double vsd = vs - vd;
      double vov = vsg - thresholdVoltage;

      double isd;
      double gsd;
      double gm;
      if (vov <= 0.0 || vsd < 0.0) {
        isd = 0.0;
        gsd = 0.0;
        gm = 0.0;
      } else if (vsd < vov) {
        isd = transconductance * (2.0 * vov * vsd - vsd * vsd);
        gsd = 2.0 * transconductance * (vov - vsd);
        gm = 2.0 * transconductance * vsd;
      } else {
        isd = transconductance * vov * vov;
        gsd = 0.0;
        gm = 2.0 * transconductance * vov;
      }

      context.stampConductance(0, 2, gsd);
      context.stampVCCS(2, 0, 2, 1, gm);
      context.stampCurrentSource(2, 0, isd - gsd * vsd - gm * vsg);
      context.stampConductance(1, 2, GATE_LEAKAGE);
    }
  }
}
