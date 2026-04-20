package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;

public final class CircuitComponents {
  private static final double DYNAMIC_LINEAR_MAX_STEP = 1.0 / 1000.0;
  private static final double DYNAMIC_NONLINEAR_MAX_STEP = 1.0 / 8000.0;
  private static final double EXP_LIMIT = 40.0;
  private static final double MAX_FORWARD_JUNCTION_VOLTAGE = 0.8;
  private static final double MIN_JUNCTION_CONDUCTANCE = 1.0e-12;
  private static final double MOS_OFF_CONDUCTANCE = 1.0e-8;
  private static final double GATE_LEAKAGE = 1.0e-12;

  private CircuitComponents() {}

  private record JunctionLinearization(double current, double conductance, double voltage) {}

  private static JunctionLinearization linearizeJunction(double voltage, double saturationCurrent, double thermalVoltage) {
    double limitedVoltage = Math.max(-EXP_LIMIT * thermalVoltage, Math.min(MAX_FORWARD_JUNCTION_VOLTAGE, voltage));
    double exp = Math.exp(limitedVoltage / thermalVoltage);
    double current = saturationCurrent * (exp - 1.0);
    double conductance = Math.max((saturationCurrent / thermalVoltage) * exp, MIN_JUNCTION_CONDUCTANCE);
    return new JunctionLinearization(current, conductance, limitedVoltage);
  }

  private static void stampLinearizedBranch(
      CircuitStampContext context,
      int fromPort,
      int toPort,
      double scale,
      JunctionLinearization linearization
  ) {
    if (scale == 0.0) {
      return;
    }
    double conductance = scale * linearization.conductance();
    double current = scale * (linearization.current() - linearization.conductance() * linearization.voltage());
    context.stampConductance(fromPort, toPort, conductance);
    context.stampCurrentSource(fromPort, toPort, current);
  }

  private static void stampLinearizedTwoControlCurrent(
      CircuitStampContext context,
      int outPositive,
      int outNegative,
      int firstControlPositive,
      int firstControlNegative,
      double firstTransconductance,
      int secondControlPositive,
      int secondControlNegative,
      double secondTransconductance,
      double constantCurrent
  ) {
    if (firstTransconductance != 0.0) {
      context.stampVCCS(outPositive, outNegative, firstControlPositive, firstControlNegative, firstTransconductance);
    }
    if (secondTransconductance != 0.0) {
      context.stampVCCS(outPositive, outNegative, secondControlPositive, secondControlNegative, secondTransconductance);
    }
    context.stampCurrentSource(outPositive, outNegative, constantCurrent);
  }

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
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(voltage);
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
    private static final double LEAKAGE_RESISTANCE = 1.0e8;
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
      context.stampResistance(0, 1, LEAKAGE_RESISTANCE);
    }

    public void postStep(PowerNetworkServer network) {
      previousVoltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_LINEAR_MAX_STEP;
    }

    @Override
    public void onSubstepComplete(org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork<?> network, double timeStepSeconds) {
      previousVoltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(previousVoltage);
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

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_LINEAR_MAX_STEP;
    }

    @Override
    public void onSubstepComplete(org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork<?> network, double timeStepSeconds) {
      double voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
      previousCurrent += (timeStepSeconds / inductance) * voltage;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(previousCurrent);
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
  // Nonlinear devices below use companion-model linearization around the solver's current
  // Newton guess, exposed through context.getPreviousVoltage(...). The solver iterates those
  // guesses within each physTick, so these stamps behave as an actual nonlinear solve rather
  // than a once-per-tick frozen linearization.

  public static final class DiodeNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-12;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;

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
      stampLinearizedBranch(context, 0, 1, 1.0, linearizeJunction(vd, saturationCurrent, thermalVoltage));
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_NONLINEAR_MAX_STEP;
    }
  }

  public static final class NPNTransistorNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_FORWARD_BETA = 100.0;
    private static final double DEFAULT_REVERSE_BETA = 0.1;

    private final double saturationCurrent;
    private final double thermalVoltage;
    private final double alphaF;
    private final double alphaR;

    public NPNTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_FORWARD_BETA, DEFAULT_REVERSE_BETA);
    }

    public NPNTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      this(saturationCurrent, thermalVoltage, beta, DEFAULT_REVERSE_BETA);
    }

    public NPNTransistorNode(double saturationCurrent, double thermalVoltage, double forwardBeta, double reverseBeta) {
      super(3);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
      this.alphaF = forwardBeta / (forwardBeta + 1.0);
      this.alphaR = reverseBeta / (reverseBeta + 1.0);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double vbe = context.getPreviousVoltage(0) - context.getPreviousVoltage(2);
      double vbc = context.getPreviousVoltage(0) - context.getPreviousVoltage(1);
      JunctionLinearization be = linearizeJunction(vbe, saturationCurrent, thermalVoltage);
      JunctionLinearization bc = linearizeJunction(vbc, saturationCurrent, thermalVoltage);
      double ifwd = be.current();
      double gfwd = be.conductance();
      double irev = bc.current();
      double grev = bc.conductance();

      double collectorCurrent = alphaF * ifwd - irev;
      double collectorGmBe = alphaF * gfwd;
      double collectorGmBc = -grev;
      double collectorConstant = collectorCurrent - collectorGmBe * vbe - collectorGmBc * vbc;
      stampLinearizedTwoControlCurrent(
          context,
          1,
          CircuitStampContext.GROUND,
          0,
          2,
          collectorGmBe,
          0,
          1,
          collectorGmBc,
          collectorConstant
      );

      double baseCurrent = (1.0 - alphaF) * ifwd + (1.0 - alphaR) * irev;
      double baseGmBe = (1.0 - alphaF) * gfwd;
      double baseGmBc = (1.0 - alphaR) * grev;
      double baseConstant = baseCurrent - baseGmBe * vbe - baseGmBc * vbc;
      stampLinearizedTwoControlCurrent(
          context,
          0,
          CircuitStampContext.GROUND,
          0,
          2,
          baseGmBe,
          0,
          1,
          baseGmBc,
          baseConstant
      );

      double emitterCurrent = -ifwd + alphaR * irev;
      double emitterGmBe = -gfwd;
      double emitterGmBc = alphaR * grev;
      double emitterConstant = emitterCurrent - emitterGmBe * vbe - emitterGmBc * vbc;
      stampLinearizedTwoControlCurrent(
          context,
          2,
          CircuitStampContext.GROUND,
          0,
          2,
          emitterGmBe,
          0,
          1,
          emitterGmBc,
          emitterConstant
      );
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_NONLINEAR_MAX_STEP;
    }
  }

  public static final class PNPTransistorNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_FORWARD_BETA = 100.0;
    private static final double DEFAULT_REVERSE_BETA = 0.1;

    private final double saturationCurrent;
    private final double thermalVoltage;
    private final double alphaF;
    private final double alphaR;

    public PNPTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_FORWARD_BETA, DEFAULT_REVERSE_BETA);
    }

    public PNPTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      this(saturationCurrent, thermalVoltage, beta, DEFAULT_REVERSE_BETA);
    }

    public PNPTransistorNode(double saturationCurrent, double thermalVoltage, double forwardBeta, double reverseBeta) {
      super(3);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
      this.alphaF = forwardBeta / (forwardBeta + 1.0);
      this.alphaR = reverseBeta / (reverseBeta + 1.0);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double veb = context.getPreviousVoltage(2) - context.getPreviousVoltage(0);
      double vcb = context.getPreviousVoltage(1) - context.getPreviousVoltage(0);
      JunctionLinearization eb = linearizeJunction(veb, saturationCurrent, thermalVoltage);
      JunctionLinearization cb = linearizeJunction(vcb, saturationCurrent, thermalVoltage);
      double ifwd = eb.current();
      double gfwd = eb.conductance();
      double irev = cb.current();
      double grev = cb.conductance();

      double collectorCurrent = -alphaF * ifwd + irev;
      double collectorGmEb = -alphaF * gfwd;
      double collectorGmCb = grev;
      double collectorConstant = collectorCurrent - collectorGmEb * veb - collectorGmCb * vcb;
      stampLinearizedTwoControlCurrent(
          context,
          1,
          CircuitStampContext.GROUND,
          2,
          0,
          collectorGmEb,
          1,
          0,
          collectorGmCb,
          collectorConstant
      );

      double baseCurrent = -(1.0 - alphaF) * ifwd + (1.0 - alphaR) * irev;
      double baseGmEb = -(1.0 - alphaF) * gfwd;
      double baseGmCb = (1.0 - alphaR) * grev;
      double baseConstant = baseCurrent - baseGmEb * veb - baseGmCb * vcb;
      stampLinearizedTwoControlCurrent(
          context,
          0,
          CircuitStampContext.GROUND,
          2,
          0,
          baseGmEb,
          1,
          0,
          baseGmCb,
          baseConstant
      );

      double emitterCurrent = ifwd - alphaR * irev;
      double emitterGmEb = gfwd;
      double emitterGmCb = -alphaR * grev;
      double emitterConstant = emitterCurrent - emitterGmEb * veb - emitterGmCb * vcb;
      stampLinearizedTwoControlCurrent(
          context,
          2,
          CircuitStampContext.GROUND,
          2,
          0,
          emitterGmEb,
          1,
          0,
          emitterGmCb,
          emitterConstant
      );
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_NONLINEAR_MAX_STEP;
    }
  }

  public static final class NMOSTransistorNode extends PowerNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double BODY_DIODE_SATURATION_CURRENT = 1.0e-12;
    private static final double BODY_DIODE_THERMAL_VOLTAGE = 0.02585;

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
      if (vov <= 0.0) {
        id = MOS_OFF_CONDUCTANCE * vds;
        gds = MOS_OFF_CONDUCTANCE;
        gm = 0.0;
      } else if (vds < vov) {
        id = transconductance * (2.0 * vov * vds - vds * vds);
        gds = Math.max(2.0 * transconductance * (vov - vds), MOS_OFF_CONDUCTANCE);
        gm = 2.0 * transconductance * vds;
      } else {
        id = transconductance * vov * vov;
        gds = MOS_OFF_CONDUCTANCE;
        gm = 2.0 * transconductance * vov;
      }

      context.stampConductance(0, 2, gds);
      context.stampVCCS(0, 2, 1, 2, gm);
      context.stampCurrentSource(0, 2, id - gds * vds - gm * vgs);
      context.stampConductance(1, 2, GATE_LEAKAGE);
      stampLinearizedBranch(
          context,
          2,
          0,
          1.0,
          linearizeJunction(vs - vd, BODY_DIODE_SATURATION_CURRENT, BODY_DIODE_THERMAL_VOLTAGE)
      );
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_NONLINEAR_MAX_STEP;
    }
  }

  public static final class PMOSTransistorNode extends PowerNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double BODY_DIODE_SATURATION_CURRENT = 1.0e-12;
    private static final double BODY_DIODE_THERMAL_VOLTAGE = 0.02585;

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
      if (vov <= 0.0) {
        isd = MOS_OFF_CONDUCTANCE * vsd;
        gsd = MOS_OFF_CONDUCTANCE;
        gm = 0.0;
      } else if (vsd < vov) {
        isd = transconductance * (2.0 * vov * vsd - vsd * vsd);
        gsd = Math.max(2.0 * transconductance * (vov - vsd), MOS_OFF_CONDUCTANCE);
        gm = 2.0 * transconductance * vsd;
      } else {
        isd = transconductance * vov * vov;
        gsd = MOS_OFF_CONDUCTANCE;
        gm = 2.0 * transconductance * vov;
      }

      context.stampConductance(0, 2, gsd);
      context.stampVCCS(0, 2, 1, 2, gm);
      context.stampCurrentSource(0, 2, -isd + gsd * vsd + gm * vsg);
      context.stampConductance(1, 2, GATE_LEAKAGE);
      stampLinearizedBranch(
          context,
          0,
          2,
          1.0,
          linearizeJunction(vd - vs, BODY_DIODE_SATURATION_CURRENT, BODY_DIODE_THERMAL_VOLTAGE)
      );
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DYNAMIC_NONLINEAR_MAX_STEP;
    }
  }
}
