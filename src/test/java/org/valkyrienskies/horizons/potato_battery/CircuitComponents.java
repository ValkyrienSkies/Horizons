package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;

public final class CircuitComponents {
  private static final double DYNAMIC_LINEAR_MAX_STEP = 1.0 / 1000.0;
  private static final double DIODE_MAX_STEP = 1.0 / 4000.0;
  private static final double BJT_MAX_STEP = 1.0 / 8000.0;
  private static final double MOS_MAX_STEP = 1.0 / 4000.0;

  private static final double EXP_LIMIT = 40.0;
  private static final double MIN_CONDUCTANCE = 1.0e-12;
  private static final double JUNCTION_SHUNT_CONDUCTANCE = 1.0e-9;
  private static final double LEAKAGE_RESISTANCE = 1.0e8;
  private static final double MOS_OFF_CONDUCTANCE = 1.0e-8;
  private static final double MOS_GATE_LEAKAGE = 1.0e-12;
  private static final double MOS_CHANNEL_LENGTH_MODULATION = 0.02;
  private static final double BJT_OUTPUT_CONDUCTANCE = 1.0e-8;

  private CircuitComponents() {
  }

  private record JunctionLinearization(double voltage, double current, double conductance) {
  }

  private static double limitedExponential(double arg) {
    return Math.exp(Math.max(-EXP_LIMIT, Math.min(EXP_LIMIT, arg)));
  }

  private static double criticalVoltage(double saturationCurrent, double thermalVoltage) {
    return thermalVoltage * Math.log(thermalVoltage / (Math.sqrt(2.0) * Math.max(saturationCurrent, 1.0e-30)));
  }

  private static double limitForwardJunctionVoltage(
      double proposedVoltage,
      double previousVoltage,
      double saturationCurrent,
      double thermalVoltage
  ) {
    double criticalVoltage = criticalVoltage(saturationCurrent, thermalVoltage);
    if (proposedVoltage <= criticalVoltage || Math.abs(proposedVoltage - previousVoltage) <= 2.0 * thermalVoltage) {
      return proposedVoltage;
    }
    if (previousVoltage > 0.0) {
      double arg = 1.0 + (proposedVoltage - previousVoltage) / thermalVoltage;
      if (arg > 0.0) {
        return previousVoltage + thermalVoltage * Math.log(arg);
      }
    }
    return criticalVoltage;
  }

  private static JunctionLinearization linearizeJunction(
      double proposedVoltage,
      double previousVoltage,
      double saturationCurrent,
      double thermalVoltage,
      double shuntConductance
  ) {
    double limitedVoltage = limitForwardJunctionVoltage(proposedVoltage, previousVoltage, saturationCurrent, thermalVoltage);
    double exp = limitedExponential(limitedVoltage / thermalVoltage);
    double diodeCurrent = saturationCurrent * (exp - 1.0);
    double diodeConductance = Math.max((saturationCurrent / thermalVoltage) * exp, MIN_CONDUCTANCE);
    double totalConductance = diodeConductance + shuntConductance;
    double totalCurrent = diodeCurrent + shuntConductance * limitedVoltage;
    return new JunctionLinearization(limitedVoltage, totalCurrent, totalConductance);
  }

  private static void stampLinearizedBranch(
      CircuitStampContext context,
      int positivePort,
      int negativePort,
      JunctionLinearization linearization
  ) {
    context.stampConductance(positivePort, negativePort, linearization.conductance());
    context.stampCurrentSource(
        positivePort,
        negativePort,
        linearization.current() - linearization.conductance() * linearization.voltage()
    );
  }

  private static void stampTerminalCurrent(
      CircuitStampContext context,
      int positivePort,
      int negativePort,
      int firstControlPositive,
      int firstControlNegative,
      double firstTransconductance,
      int secondControlPositive,
      int secondControlNegative,
      double secondTransconductance,
      double constantCurrent
  ) {
    if (firstTransconductance != 0.0) {
      context.stampVCCS(positivePort, negativePort, firstControlPositive, firstControlNegative, firstTransconductance);
    }
    if (secondTransconductance != 0.0) {
      context.stampVCCS(positivePort, negativePort, secondControlPositive, secondControlNegative, secondTransconductance);
    }
    if (constantCurrent != 0.0) {
      context.stampCurrentSource(positivePort, negativePort, constantCurrent);
    }
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

  public static final class VariableCurrentSourceNode extends PowerNode {
    private volatile double current;

    public VariableCurrentSourceNode() {
      super(2);
    }

    public void setCurrent(double current) {
      this.current = current;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(current);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampCurrentSource(0, 1, current);
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
      double companionConductance = Math.max(capacitance / context.getTimeStep(), MIN_CONDUCTANCE);
      context.stampConductance(0, 1, companionConductance);
      context.stampCurrentSource(1, 0, companionConductance * previousVoltage);
      context.stampResistance(0, 1, LEAKAGE_RESISTANCE);
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
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
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
      double companionConductance = Math.max(context.getTimeStep() / Math.max(inductance, 1.0e-18), MIN_CONDUCTANCE);
      context.stampConductance(0, 1, companionConductance);
      context.stampCurrentSource(0, 1, previousCurrent);
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
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
      previousCurrent += (timeStepSeconds / Math.max(inductance, 1.0e-18)) * voltage;
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

  public static final class DiodeNode extends PowerNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-12;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;

    private final double saturationCurrent;
    private final double thermalVoltage;
    private double previousJunctionVoltage;

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
      double voltage = context.getPreviousVoltage(0) - context.getPreviousVoltage(1);
      JunctionLinearization junction = linearizeJunction(
          voltage,
          previousJunctionVoltage,
          saturationCurrent,
          thermalVoltage,
          JUNCTION_SHUNT_CONDUCTANCE
      );
      stampLinearizedBranch(context, 0, 1, junction);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      previousJunctionVoltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return DIODE_MAX_STEP;
    }
  }

  private abstract static class AbstractBjtNode extends PowerNode {
    private final double saturationCurrent;
    private final double thermalVoltage;
    private final double alphaForward;
    private final double alphaReverse;
    private double previousForwardJunction;
    private double previousReverseJunction;

    protected AbstractBjtNode(double saturationCurrent, double thermalVoltage, double forwardBeta, double reverseBeta) {
      super(3);
      this.saturationCurrent = saturationCurrent;
      this.thermalVoltage = thermalVoltage;
      this.alphaForward = forwardBeta / (forwardBeta + 1.0);
      this.alphaReverse = reverseBeta / (reverseBeta + 1.0);
    }

    protected abstract int polarity();

    @Override
    public void stamp(CircuitStampContext context) {
      int polarity = polarity();
      double baseVoltage = context.getPreviousVoltage(0);
      double collectorVoltage = context.getPreviousVoltage(1);
      double emitterVoltage = context.getPreviousVoltage(2);

      double forwardVoltage = polarity * (baseVoltage - emitterVoltage);
      double reverseVoltage = polarity * (baseVoltage - collectorVoltage);

      JunctionLinearization forward = linearizeJunction(
          forwardVoltage,
          previousForwardJunction,
          saturationCurrent,
          thermalVoltage,
          JUNCTION_SHUNT_CONDUCTANCE
      );
      JunctionLinearization reverse = linearizeJunction(
          reverseVoltage,
          previousReverseJunction,
          saturationCurrent,
          thermalVoltage,
          JUNCTION_SHUNT_CONDUCTANCE
      );

      double ifwd = forward.current();
      double gfwd = forward.conductance();
      double irev = reverse.current();
      double grev = reverse.conductance();

      double collectorCurrent;
      double collectorGmForward;
      double collectorGmReverse;
      double baseCurrent;
      double baseGmForward;
      double baseGmReverse;
      double emitterCurrent;
      double emitterGmForward;
      double emitterGmReverse;

      if (polarity > 0) {
        collectorCurrent = alphaForward * ifwd - irev;
        collectorGmForward = alphaForward * gfwd;
        collectorGmReverse = -grev;

        baseCurrent = (1.0 - alphaForward) * ifwd + (1.0 - alphaReverse) * irev;
        baseGmForward = (1.0 - alphaForward) * gfwd;
        baseGmReverse = (1.0 - alphaReverse) * grev;

        emitterCurrent = -ifwd + alphaReverse * irev;
        emitterGmForward = -gfwd;
        emitterGmReverse = alphaReverse * grev;
      } else {
        collectorCurrent = -alphaForward * ifwd + irev;
        collectorGmForward = -alphaForward * gfwd;
        collectorGmReverse = grev;

        baseCurrent = -(1.0 - alphaForward) * ifwd + (1.0 - alphaReverse) * irev;
        baseGmForward = -(1.0 - alphaForward) * gfwd;
        baseGmReverse = (1.0 - alphaReverse) * grev;

        emitterCurrent = ifwd - alphaReverse * irev;
        emitterGmForward = gfwd;
        emitterGmReverse = -alphaReverse * grev;
      }

      double collectorConstant = collectorCurrent
          - collectorGmForward * forward.voltage()
          - collectorGmReverse * reverse.voltage();
      double baseConstant = baseCurrent
          - baseGmForward * forward.voltage()
          - baseGmReverse * reverse.voltage();
      double emitterConstant = emitterCurrent
          - emitterGmForward * forward.voltage()
          - emitterGmReverse * reverse.voltage();

      int forwardPositivePort = polarity > 0 ? 0 : 2;
      int forwardNegativePort = polarity > 0 ? 2 : 0;
      int reversePositivePort = polarity > 0 ? 0 : 1;
      int reverseNegativePort = polarity > 0 ? 1 : 0;

      stampTerminalCurrent(
          context,
          1,
          CircuitStampContext.GROUND,
          forwardPositivePort,
          forwardNegativePort,
          collectorGmForward,
          reversePositivePort,
          reverseNegativePort,
          collectorGmReverse,
          collectorConstant
      );
      stampTerminalCurrent(
          context,
          0,
          CircuitStampContext.GROUND,
          forwardPositivePort,
          forwardNegativePort,
          baseGmForward,
          reversePositivePort,
          reverseNegativePort,
          baseGmReverse,
          baseConstant
      );
      stampTerminalCurrent(
          context,
          2,
          CircuitStampContext.GROUND,
          forwardPositivePort,
          forwardNegativePort,
          emitterGmForward,
          reversePositivePort,
          reverseNegativePort,
          emitterGmReverse,
          emitterConstant
      );

      context.stampConductance(1, 2, BJT_OUTPUT_CONDUCTANCE);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      int polarity = polarity();
      previousForwardJunction = polarity * (network.getVoltageAt(this, 0) - network.getVoltageAt(this, 2));
      previousReverseJunction = polarity * (network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1));
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return BJT_MAX_STEP;
    }
  }

  public static final class NPNTransistorNode extends AbstractBjtNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_FORWARD_BETA = 100.0;
    private static final double DEFAULT_REVERSE_BETA = 0.1;

    public NPNTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_FORWARD_BETA, DEFAULT_REVERSE_BETA);
    }

    public NPNTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      this(saturationCurrent, thermalVoltage, beta, DEFAULT_REVERSE_BETA);
    }

    public NPNTransistorNode(double saturationCurrent, double thermalVoltage, double forwardBeta, double reverseBeta) {
      super(saturationCurrent, thermalVoltage, forwardBeta, reverseBeta);
    }

    @Override
    protected int polarity() {
      return 1;
    }
  }

  public static final class PNPTransistorNode extends AbstractBjtNode {
    private static final double DEFAULT_SATURATION_CURRENT = 1.0e-14;
    private static final double DEFAULT_THERMAL_VOLTAGE = 0.02585;
    private static final double DEFAULT_FORWARD_BETA = 100.0;
    private static final double DEFAULT_REVERSE_BETA = 0.1;

    public PNPTransistorNode() {
      this(DEFAULT_SATURATION_CURRENT, DEFAULT_THERMAL_VOLTAGE, DEFAULT_FORWARD_BETA, DEFAULT_REVERSE_BETA);
    }

    public PNPTransistorNode(double saturationCurrent, double thermalVoltage, double beta) {
      this(saturationCurrent, thermalVoltage, beta, DEFAULT_REVERSE_BETA);
    }

    public PNPTransistorNode(double saturationCurrent, double thermalVoltage, double forwardBeta, double reverseBeta) {
      super(saturationCurrent, thermalVoltage, forwardBeta, reverseBeta);
    }

    @Override
    protected int polarity() {
      return -1;
    }
  }

  private abstract static class AbstractMosfetNode extends PowerNode {
    private final double thresholdVoltage;
    private final double transconductance;
    private final double bodyDiodeSaturationCurrent;
    private final double bodyDiodeThermalVoltage;
    private double previousBodyDiodeVoltage;

    protected AbstractMosfetNode(
        double thresholdVoltage,
        double transconductance,
        double bodyDiodeSaturationCurrent,
        double bodyDiodeThermalVoltage
    ) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.transconductance = transconductance;
      this.bodyDiodeSaturationCurrent = bodyDiodeSaturationCurrent;
      this.bodyDiodeThermalVoltage = bodyDiodeThermalVoltage;
    }

    protected abstract boolean isPmos();

    @Override
    public void stamp(CircuitStampContext context) {
      double terminal0Voltage = context.getPreviousVoltage(0);
      double gateVoltage = context.getPreviousVoltage(1);
      double terminal2Voltage = context.getPreviousVoltage(2);

      if (isPmos()) {
        stampPmosChannel(context, terminal0Voltage, gateVoltage, terminal2Voltage);
        JunctionLinearization bodyDiode = linearizeJunction(
            terminal0Voltage - terminal2Voltage,
            previousBodyDiodeVoltage,
            bodyDiodeSaturationCurrent,
            bodyDiodeThermalVoltage,
            JUNCTION_SHUNT_CONDUCTANCE
        );
        stampLinearizedBranch(context, 0, 2, bodyDiode);
      } else {
        stampNmosChannel(context, terminal0Voltage, gateVoltage, terminal2Voltage);
        JunctionLinearization bodyDiode = linearizeJunction(
            terminal2Voltage - terminal0Voltage,
            previousBodyDiodeVoltage,
            bodyDiodeSaturationCurrent,
            bodyDiodeThermalVoltage,
            JUNCTION_SHUNT_CONDUCTANCE
        );
        stampLinearizedBranch(context, 2, 0, bodyDiode);
      }

      context.stampConductance(1, 2, MOS_GATE_LEAKAGE);
    }

    private void stampNmosChannel(CircuitStampContext context, double drainVoltage, double gateVoltage, double sourceVoltage) {
      int drainPort = 0;
      int sourcePort = 2;
      if (drainVoltage < sourceVoltage) {
        drainPort = 2;
        sourcePort = 0;
        double tmp = drainVoltage;
        drainVoltage = sourceVoltage;
        sourceVoltage = tmp;
      }

      double vgs = gateVoltage - sourceVoltage;
      double vds = drainVoltage - sourceVoltage;
      double overdrive = vgs - thresholdVoltage;

      double id;
      double gm;
      double gds;
      if (overdrive <= 0.0) {
        id = MOS_OFF_CONDUCTANCE * vds;
        gm = 0.0;
        gds = MOS_OFF_CONDUCTANCE;
      } else if (vds < overdrive) {
        double baseCurrent = transconductance * (2.0 * overdrive * vds - vds * vds);
        double slopeFactor = 1.0 + MOS_CHANNEL_LENGTH_MODULATION * vds;
        id = baseCurrent * slopeFactor;
        gm = 2.0 * transconductance * vds * slopeFactor;
        gds = transconductance * (2.0 * overdrive - 2.0 * vds) * slopeFactor
            + baseCurrent * MOS_CHANNEL_LENGTH_MODULATION;
        gds = Math.max(gds, MOS_OFF_CONDUCTANCE);
      } else {
        double baseCurrent = transconductance * overdrive * overdrive;
        id = baseCurrent * (1.0 + MOS_CHANNEL_LENGTH_MODULATION * vds);
        gm = 2.0 * transconductance * overdrive * (1.0 + MOS_CHANNEL_LENGTH_MODULATION * vds);
        gds = Math.max(baseCurrent * MOS_CHANNEL_LENGTH_MODULATION, MOS_OFF_CONDUCTANCE);
      }

      context.stampConductance(drainPort, sourcePort, gds);
      context.stampVCCS(drainPort, sourcePort, 1, sourcePort, gm);
      context.stampCurrentSource(drainPort, sourcePort, id - gds * vds - gm * vgs);
    }

    private void stampPmosChannel(CircuitStampContext context, double drainVoltage, double gateVoltage, double sourceVoltage) {
      int sourcePort = 2;
      int drainPort = 0;
      if (sourceVoltage < drainVoltage) {
        sourcePort = 0;
        drainPort = 2;
        double tmp = drainVoltage;
        drainVoltage = sourceVoltage;
        sourceVoltage = tmp;
      }

      double vsg = sourceVoltage - gateVoltage;
      double vsd = sourceVoltage - drainVoltage;
      double overdrive = vsg - thresholdVoltage;

      double isd;
      double gm;
      double gsd;
      if (overdrive <= 0.0) {
        isd = MOS_OFF_CONDUCTANCE * vsd;
        gm = 0.0;
        gsd = MOS_OFF_CONDUCTANCE;
      } else if (vsd < overdrive) {
        double baseCurrent = transconductance * (2.0 * overdrive * vsd - vsd * vsd);
        double slopeFactor = 1.0 + MOS_CHANNEL_LENGTH_MODULATION * vsd;
        isd = baseCurrent * slopeFactor;
        gm = 2.0 * transconductance * vsd * slopeFactor;
        gsd = transconductance * (2.0 * overdrive - 2.0 * vsd) * slopeFactor
            + baseCurrent * MOS_CHANNEL_LENGTH_MODULATION;
        gsd = Math.max(gsd, MOS_OFF_CONDUCTANCE);
      } else {
        double baseCurrent = transconductance * overdrive * overdrive;
        isd = baseCurrent * (1.0 + MOS_CHANNEL_LENGTH_MODULATION * vsd);
        gm = 2.0 * transconductance * overdrive * (1.0 + MOS_CHANNEL_LENGTH_MODULATION * vsd);
        gsd = Math.max(baseCurrent * MOS_CHANNEL_LENGTH_MODULATION, MOS_OFF_CONDUCTANCE);
      }

      context.stampConductance(sourcePort, drainPort, gsd);
      context.stampVCCS(sourcePort, drainPort, sourcePort, 1, gm);
      context.stampCurrentSource(sourcePort, drainPort, isd - gsd * vsd - gm * vsg);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      previousBodyDiodeVoltage = isPmos()
          ? network.getVoltageAt(this, 0) - network.getVoltageAt(this, 2)
          : network.getVoltageAt(this, 2) - network.getVoltageAt(this, 0);
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return MOS_MAX_STEP;
    }
  }

  public static final class NMOSTransistorNode extends AbstractMosfetNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double DEFAULT_BODY_DIODE_SATURATION_CURRENT = 1.0e-12;
    private static final double DEFAULT_BODY_DIODE_THERMAL_VOLTAGE = 0.02585;

    public NMOSTransistorNode() {
      this(DEFAULT_THRESHOLD, DEFAULT_TRANSCONDUCTANCE);
    }

    public NMOSTransistorNode(double thresholdVoltage, double transconductance) {
      super(
          thresholdVoltage,
          transconductance,
          DEFAULT_BODY_DIODE_SATURATION_CURRENT,
          DEFAULT_BODY_DIODE_THERMAL_VOLTAGE
      );
    }

    @Override
    protected boolean isPmos() {
      return false;
    }
  }

  public static final class PMOSTransistorNode extends AbstractMosfetNode {
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final double DEFAULT_TRANSCONDUCTANCE = 1.0e-3;
    private static final double DEFAULT_BODY_DIODE_SATURATION_CURRENT = 1.0e-12;
    private static final double DEFAULT_BODY_DIODE_THERMAL_VOLTAGE = 0.02585;

    public PMOSTransistorNode() {
      this(DEFAULT_THRESHOLD, DEFAULT_TRANSCONDUCTANCE);
    }

    public PMOSTransistorNode(double thresholdVoltage, double transconductance) {
      super(
          thresholdVoltage,
          transconductance,
          DEFAULT_BODY_DIODE_SATURATION_CURRENT,
          DEFAULT_BODY_DIODE_THERMAL_VOLTAGE
      );
    }

    @Override
    protected boolean isPmos() {
      return true;
    }
  }
}
