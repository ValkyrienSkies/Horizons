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

  public static final class ComparatorNode extends PowerNode {
    private final double thresholdVoltage;
    private final double highVoltage;
    private final double lowVoltage;
    private double outputVoltage;

    public ComparatorNode() {
      this(0.0, 5.0, 0.0);
    }

    public ComparatorNode(double thresholdVoltage, double highVoltage, double lowVoltage) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.highVoltage = highVoltage;
      this.lowVoltage = lowVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double a = network.getVoltageAt(this, 0);
      double b = network.getVoltageAt(this, 1);
      outputVoltage = a - b >= thresholdVoltage ? highVoltage : lowVoltage;
    }
  }

  public static final class SchmittTriggerNode extends PowerNode {
    private final double risingThreshold;
    private final double fallingThreshold;
    private final double highVoltage;
    private final double lowVoltage;
    private boolean high;

    public SchmittTriggerNode(double risingThreshold, double fallingThreshold, double highVoltage, double lowVoltage) {
      super(2);
      this.risingThreshold = risingThreshold;
      this.fallingThreshold = fallingThreshold;
      this.highVoltage = highVoltage;
      this.lowVoltage = lowVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return high ? 1L : 0L;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, high ? highVoltage : lowVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double input = network.getVoltageAt(this, 0);
      if (!high && input >= risingThreshold) {
        high = true;
      } else if (high && input <= fallingThreshold) {
        high = false;
      }
    }
  }

  public static final class WindowComparatorNode extends PowerNode {
    private final double lowThreshold;
    private final double highThreshold;
    private final double highVoltage;
    private double outputVoltage;

    public WindowComparatorNode(double lowThreshold, double highThreshold, double highVoltage) {
      super(2);
      this.lowThreshold = lowThreshold;
      this.highThreshold = highThreshold;
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double input = network.getVoltageAt(this, 0);
      outputVoltage = input >= lowThreshold && input <= highThreshold ? highVoltage : 0.0;
    }
  }

  public static final class LatchNode extends PowerNode {
    private final double highVoltage;
    private boolean high;

    public LatchNode() {
      this(5.0);
    }

    public LatchNode(double highVoltage) {
      super(3);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return high ? 1L : 0L;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, high ? highVoltage : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      if (network.getVoltageAt(this, 1) > highVoltage * 0.5) {
        high = false;
      } else if (network.getVoltageAt(this, 0) > highVoltage * 0.5) {
        high = true;
      }
    }

    public void reset() {
      high = false;
    }
  }

  public static final class CounterNode extends PowerNode {
    private final int maxCount;
    private final double highVoltage;
    private int count;
    private double previousClock;
    private double previousReset;

    public CounterNode(int maxCount, double highVoltage) {
      super(3);
      this.maxCount = Math.max(1, maxCount);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return count;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, (count / (double) maxCount) * highVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double clock = network.getVoltageAt(this, 0);
      double reset = network.getVoltageAt(this, 1);
      if (reset > highVoltage * 0.5 && previousReset <= highVoltage * 0.5) {
        count = 0;
      } else if (clock > highVoltage * 0.5 && previousClock <= highVoltage * 0.5) {
        count = (count + 1) % (maxCount + 1);
      }
      previousClock = clock;
      previousReset = reset;
    }

    public int getCount() {
      return count;
    }
  }

  public static final class ModCounterNode extends PowerNode {
    private final int maxCount;
    private final double highVoltage;
    private int count;
    private boolean carryPulse;
    private double previousClock;
    private double previousReset;

    public ModCounterNode(int maxCount, double highVoltage) {
      super(4);
      this.maxCount = Math.max(1, maxCount);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 2;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return 31L * count + (carryPulse ? 1L : 0L);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, (count / (double) Math.max(1, maxCount - 1)) * highVoltage);
      context.stampVoltageSource(1, 3, CircuitStampContext.GROUND, carryPulse ? highVoltage : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double clock = network.getVoltageAt(this, 0);
      double reset = network.getVoltageAt(this, 1);
      carryPulse = false;
      if (reset > highVoltage * 0.5 && previousReset <= highVoltage * 0.5) {
        count = 0;
      } else if (clock > highVoltage * 0.5 && previousClock <= highVoltage * 0.5) {
        count++;
        if (count >= maxCount) {
          count = 0;
          carryPulse = true;
        }
      }
      previousClock = clock;
      previousReset = reset;
    }

    public int getCount() {
      return count;
    }

    public void reset() {
      count = 0;
      carryPulse = false;
      previousClock = 0.0;
      previousReset = 0.0;
    }
  }

  public static final class OscillatorNode extends PowerNode {
    private final double frequencyHz;
    private final double dutyCycle;
    private final double highVoltage;
    private double phase;

    public OscillatorNode(double frequencyHz, double dutyCycle, double highVoltage) {
      super(1);
      this.frequencyHz = frequencyHz;
      this.dutyCycle = dutyCycle;
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(phase);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, phase < dutyCycle ? highVoltage : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      phase += frequencyHz * timeStepSeconds;
      phase -= Math.floor(phase);
    }

    public void reset() {
      phase = 0.0;
    }
  }

  public static final class ClockDividerNode extends PowerNode {
    private final int divideBy;
    private final double highVoltage;
    private int edgeCount;
    private boolean high;
    private double previousClock;
    private double previousReset;

    public ClockDividerNode(int divideBy, double highVoltage) {
      super(3);
      this.divideBy = Math.max(1, divideBy);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return 31L * edgeCount + (high ? 1L : 0L);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, high ? highVoltage : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double clock = network.getVoltageAt(this, 0);
      double reset = network.getVoltageAt(this, 1);
      if (reset > highVoltage * 0.5 && previousReset <= highVoltage * 0.5) {
        edgeCount = 0;
        high = false;
      } else if (clock > highVoltage * 0.5 && previousClock <= highVoltage * 0.5) {
        edgeCount++;
        if (edgeCount >= divideBy) {
          edgeCount = 0;
          high = !high;
        }
      }
      previousClock = clock;
      previousReset = reset;
    }
  }

  public static final class MuxNode extends PowerNode {
    private final double thresholdVoltage;
    private double outputVoltage;

    public MuxNode() {
      this(2.5);
    }

    public MuxNode(double thresholdVoltage) {
      super(4);
      this.thresholdVoltage = thresholdVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 2) > thresholdVoltage
          ? network.getVoltageAt(this, 1)
          : network.getVoltageAt(this, 0);
    }
  }

  public static final class OrNode extends PowerNode {
    private final double thresholdVoltage;
    private final double highVoltage;
    private double outputVoltage;

    public OrNode() {
      this(2.5, 5.0);
    }

    public OrNode(double thresholdVoltage, double highVoltage) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 0) > thresholdVoltage || network.getVoltageAt(this, 1) > thresholdVoltage
          ? highVoltage
          : 0.0;
    }
  }

  public static final class AndNode extends PowerNode {
    private final double thresholdVoltage;
    private final double highVoltage;
    private double outputVoltage;

    public AndNode() {
      this(2.5, 5.0);
    }

    public AndNode(double thresholdVoltage, double highVoltage) {
      super(3);
      this.thresholdVoltage = thresholdVoltage;
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 0) > thresholdVoltage && network.getVoltageAt(this, 1) > thresholdVoltage
          ? highVoltage
          : 0.0;
    }
  }

  public static final class NotNode extends PowerNode {
    private final double thresholdVoltage;
    private final double highVoltage;
    private double outputVoltage;

    public NotNode() {
      this(2.5, 5.0);
    }

    public NotNode(double thresholdVoltage, double highVoltage) {
      super(2);
      this.thresholdVoltage = thresholdVoltage;
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 0) > thresholdVoltage ? 0.0 : highVoltage;
    }
  }

  public static final class MaxNode extends PowerNode {
    private final double highVoltage;
    private double outputVoltage;

    public MaxNode(int inputs, double highVoltage) {
      super(inputs + 1);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, getPorts() - 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double max = 0.0;
      for (int i = 0; i < getPorts() - 1; i++) {
        max = Math.max(max, network.getVoltageAt(this, i));
      }
      outputVoltage = Math.max(0.0, Math.min(highVoltage, max));
    }
  }

  public static final class SumClampNode extends PowerNode {
    private final double clampVoltage;
    private double outputVoltage;

    public SumClampNode(int inputs, double clampVoltage) {
      super(inputs + 1);
      this.clampVoltage = clampVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, getPorts() - 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double sum = 0.0;
      for (int i = 0; i < getPorts() - 1; i++) {
        sum += network.getVoltageAt(this, i);
      }
      if (!Double.isFinite(sum)) {
        sum = 0.0;
      }
      outputVoltage = Math.max(-clampVoltage, Math.min(clampVoltage, sum));
    }
  }

  public static final class OneShotNode extends PowerNode {
    private final double pulseSeconds;
    private final double highVoltage;
    private final boolean retriggerable;
    private double remainingSeconds;
    private double previousTrigger;
    private double previousReset;

    public OneShotNode(double pulseSeconds, double highVoltage, boolean retriggerable) {
      super(3);
      this.pulseSeconds = pulseSeconds;
      this.highVoltage = highVoltage;
      this.retriggerable = retriggerable;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(remainingSeconds);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, remainingSeconds > 0.0 ? highVoltage : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double trigger = network.getVoltageAt(this, 0);
      double reset = network.getVoltageAt(this, 1);
      if (reset > highVoltage * 0.5 && previousReset <= highVoltage * 0.5) {
        remainingSeconds = 0.0;
      } else if (trigger > highVoltage * 0.5 && previousTrigger <= highVoltage * 0.5) {
        if (retriggerable || remainingSeconds <= 0.0) {
          remainingSeconds = pulseSeconds;
        }
      }
      if (remainingSeconds > 0.0) {
        remainingSeconds = Math.max(0.0, remainingSeconds - timeStepSeconds);
      }
      previousTrigger = trigger;
      previousReset = reset;
    }

    public void reset() {
      remainingSeconds = 0.0;
      previousTrigger = 0.0;
      previousReset = 0.0;
    }
  }

  public static final class ToneBurstNode extends PowerNode {
    private final double frequencyHz;
    private final double pulseSeconds;
    private final double amplitudeVoltage;
    private double previousTriggerA;
    private double previousTriggerB;
    private double remainingSeconds;
    private double phase;
    private boolean high;
    private double outputVoltage;

    public ToneBurstNode(double frequencyHz, double pulseSeconds, double amplitudeVoltage) {
      super(4);
      this.frequencyHz = frequencyHz;
      this.pulseSeconds = pulseSeconds;
      this.amplitudeVoltage = amplitudeVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      long fingerprint = Double.doubleToLongBits(remainingSeconds);
      return 31L * fingerprint + (high ? 1L : 0L);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double triggerA = network.getVoltageAt(this, 0);
      double triggerB = network.getVoltageAt(this, 1);
      double inhibit = network.getVoltageAt(this, 2);
      if (inhibit <= amplitudeVoltage * 0.5
          && ((triggerA > amplitudeVoltage * 0.5 && previousTriggerA <= amplitudeVoltage * 0.5)
          || (triggerB > amplitudeVoltage * 0.5 && previousTriggerB <= amplitudeVoltage * 0.5))) {
        remainingSeconds = pulseSeconds;
        phase = 0.0;
        high = false;
      }
      previousTriggerA = triggerA;
      previousTriggerB = triggerB;
      if (inhibit > amplitudeVoltage * 0.5 || remainingSeconds <= 0.0) {
        outputVoltage = 0.0;
        remainingSeconds = Math.max(0.0, remainingSeconds - timeStepSeconds);
        return;
      }
      remainingSeconds = Math.max(0.0, remainingSeconds - timeStepSeconds);
      phase += frequencyHz * timeStepSeconds;
      if (phase >= 0.5) {
        phase -= 0.5;
        high = !high;
      }
      double envelope = Math.min(1.0, remainingSeconds / pulseSeconds);
      outputVoltage = (high ? 1.0 : -1.0) * envelope * amplitudeVoltage;
    }

    public void reset() {
      previousTriggerA = 0.0;
      previousTriggerB = 0.0;
      remainingSeconds = 0.0;
      phase = 0.0;
      high = false;
      outputVoltage = 0.0;
    }
  }

  public static final class PulseAccumulatingScaleNode extends PowerNode {
    private final double incrementPerPulse;
    private final double maxBoost;
    private final double highVoltage;
    private double manualScale = 1.0;
    private double accumulatedBoost;
    private double previousPulse;

    public PulseAccumulatingScaleNode(double incrementPerPulse, double maxBoost, double highVoltage) {
      super(3);
      this.incrementPerPulse = Math.max(0.0, incrementPerPulse);
      this.maxBoost = Math.max(0.0, maxBoost);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return 31L * Double.doubleToLongBits(manualScale) + Double.doubleToLongBits(accumulatedBoost);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, scale() * highVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double pulse = network.getVoltageAt(this, 0);
      double reset = network.getVoltageAt(this, 1);
      if (reset > highVoltage * 0.5) {
        accumulatedBoost = 0.0;
      } else if (pulse > highVoltage * 0.5 && previousPulse <= highVoltage * 0.5) {
        accumulatedBoost = Math.min(maxBoost, accumulatedBoost + incrementPerPulse);
      }
      previousPulse = pulse;
    }

    public void setManualScale(double manualScale) {
      this.manualScale = Math.max(0.25, manualScale);
    }

    public void reset() {
      accumulatedBoost = 0.0;
      previousPulse = 0.0;
    }

    public double scale() {
      return manualScale * (1.0 + accumulatedBoost);
    }
  }

  public static final class QuantizedSlewNode extends PowerNode {
    private final int steps;
    private final double highVoltage;
    private int step;

    public QuantizedSlewNode(int steps, double highVoltage) {
      super(2);
      this.steps = Math.max(1, steps);
      this.highVoltage = highVoltage;
      this.step = this.steps / 2;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return step;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, (step / (double) steps) * highVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      int targetStep = (int) Math.round(
          Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 0) / Math.max(highVoltage, 1.0e-9))) * steps
      );
      if (targetStep > step) {
        step++;
      } else if (targetStep < step) {
        step--;
      }
    }

    public void reset() {
      step = steps / 2;
    }

    public int getStep() {
      return step;
    }
  }

  public static final class RectangleRasterNode extends PowerNode {
    private final double halfWidth;
    private final double halfHeight;
    private final double highVoltage;
    private double outputVoltage;

    public RectangleRasterNode(double halfWidth, double halfHeight, double highVoltage) {
      super(5);
      this.halfWidth = Math.max(0.0, halfWidth);
      this.halfHeight = Math.max(0.0, halfHeight);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 4, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double x = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 0) / highVoltage));
      double y = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 1) / highVoltage));
      double centerX = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 2) / highVoltage));
      double centerY = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 3) / highVoltage));
      outputVoltage = Math.abs(x - centerX) <= halfWidth && Math.abs(y - centerY) <= halfHeight ? highVoltage : 0.0;
    }
  }

  public static final class VerticalMeterNode extends PowerNode {
    private final double minX;
    private final double maxX;
    private final double topY;
    private final double fullHeight;
    private final double highVoltage;
    private double outputVoltage;

    public VerticalMeterNode(double minX, double maxX, double topY, double fullHeight, double highVoltage) {
      super(4);
      this.minX = minX;
      this.maxX = maxX;
      this.topY = topY;
      this.fullHeight = Math.max(0.0, fullHeight);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double x = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 0) / highVoltage));
      double y = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 1) / highVoltage));
      double level = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 2) / highVoltage));
      double minY = Math.max(0.0, topY - level * fullHeight);
      outputVoltage = x >= minX && x <= maxX && y <= topY && y >= minY ? highVoltage : 0.0;
    }
  }

  public static final class StripeRasterNode extends PowerNode {
    private final double centerX;
    private final double halfWidth;
    private final int segmentCount;
    private final double highVoltage;
    private double outputVoltage;

    public StripeRasterNode(double centerX, double halfWidth, int segmentCount, double highVoltage) {
      super(3);
      this.centerX = centerX;
      this.halfWidth = Math.max(0.0, halfWidth);
      this.segmentCount = Math.max(1, segmentCount);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double x = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 0) / highVoltage));
      double y = Math.max(0.0, Math.min(1.0, network.getVoltageAt(this, 1) / highVoltage));
      outputVoltage = Math.abs(x - centerX) <= halfWidth && ((int) Math.floor(y * segmentCount) % 2 == 0)
          ? highVoltage
          : 0.0;
    }
  }

  public static final class SampleHoldNode extends PowerNode {
    private final double highVoltage;
    private double heldVoltage;
    private double previousSampleTrigger;

    public SampleHoldNode(double highVoltage) {
      super(3);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(heldVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, heldVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double sampleTrigger = network.getVoltageAt(this, 1);
      if (sampleTrigger > highVoltage * 0.5 && previousSampleTrigger <= highVoltage * 0.5) {
        heldVoltage = network.getVoltageAt(this, 0);
      }
      previousSampleTrigger = sampleTrigger;
    }

    public void reset() {
      heldVoltage = 0.0;
      previousSampleTrigger = 0.0;
    }
  }

  public static final class DeltaNode extends PowerNode {
    private final double highVoltage;
    private double previousInput;
    private double deltaVoltage;

    public DeltaNode(double highVoltage) {
      super(3);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(deltaVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, deltaVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double reset = network.getVoltageAt(this, 1);
      double input = network.getVoltageAt(this, 0);
      if (reset > highVoltage * 0.5) {
        deltaVoltage = 0.0;
        previousInput = input;
        return;
      }
      deltaVoltage = input - previousInput;
      previousInput = input;
    }

    public void reset() {
      previousInput = 0.0;
      deltaVoltage = 0.0;
    }
  }

  public static final class GainNode extends PowerNode {
    private final double gain;
    private double outputVoltage;

    public GainNode(double gain) {
      super(2);
      this.gain = gain;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 0) * gain;
    }
  }

  public static final class MultiplyNode extends PowerNode {
    private final double scale;
    private double outputVoltage;

    public MultiplyNode(double scale) {
      super(3);
      this.scale = scale;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      outputVoltage = network.getVoltageAt(this, 0) * network.getVoltageAt(this, 1) * scale;
    }
  }

  public static final class SafeDivideNode extends PowerNode {
    private final double minimumDenominatorMagnitude;
    private final double maximumAbsoluteOutput;
    private double outputVoltage;

    public SafeDivideNode(double minimumDenominatorMagnitude, double maximumAbsoluteOutput) {
      super(3);
      this.minimumDenominatorMagnitude = Math.max(1.0e-9, minimumDenominatorMagnitude);
      this.maximumAbsoluteOutput = Math.max(minimumDenominatorMagnitude, maximumAbsoluteOutput);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double numerator = network.getVoltageAt(this, 0);
      double denominator = network.getVoltageAt(this, 1);
      if (Math.abs(denominator) < minimumDenominatorMagnitude) {
        denominator = Math.copySign(minimumDenominatorMagnitude, denominator == 0.0 ? 1.0 : denominator);
      }
      outputVoltage = Math.max(-maximumAbsoluteOutput, Math.min(maximumAbsoluteOutput, numerator / denominator));
    }
  }

  public static final class AccumulatorNode extends PowerNode {
    private final double minimumValue;
    private final double maximumValue;
    private double accumulatedValue;

    public AccumulatorNode(double minimumValue, double maximumValue) {
      super(3);
      this.minimumValue = Math.min(minimumValue, maximumValue);
      this.maximumValue = Math.max(minimumValue, maximumValue);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(accumulatedValue);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, accumulatedValue);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double reset = network.getVoltageAt(this, 1);
      if (reset > 2.5) {
        accumulatedValue = 0.0;
      } else {
        accumulatedValue += network.getVoltageAt(this, 0) * timeStepSeconds;
        accumulatedValue = Math.max(minimumValue, Math.min(maximumValue, accumulatedValue));
      }
    }

    public void reset() {
      accumulatedValue = 0.0;
    }
  }

  public static final class StepEmitterNode extends PowerNode {
    private final double thresholdStep;
    private final double highVoltage;
    private double previousInput;
    private double pulseVoltage;

    public StepEmitterNode(double thresholdStep, double highVoltage) {
      super(3);
      this.thresholdStep = Math.max(1.0e-9, thresholdStep);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(pulseVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, pulseVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double reset = network.getVoltageAt(this, 1);
      double input = network.getVoltageAt(this, 0);
      if (reset > highVoltage * 0.5) {
        pulseVoltage = 0.0;
        previousInput = input;
        return;
      }
      long previousBucket = (long) Math.floor(previousInput / thresholdStep);
      long currentBucket = (long) Math.floor(input / thresholdStep);
      pulseVoltage = currentBucket > previousBucket ? highVoltage : 0.0;
      previousInput = input;
    }

    public void reset() {
      previousInput = 0.0;
      pulseVoltage = 0.0;
    }
  }

  public static final class RangeCompareNode extends PowerNode {
    private final double highVoltage;
    private double outputVoltage;

    public RangeCompareNode(double highVoltage) {
      super(4);
      this.highVoltage = highVoltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double input = network.getVoltageAt(this, 0);
      double min = Math.min(network.getVoltageAt(this, 1), network.getVoltageAt(this, 2));
      double max = Math.max(network.getVoltageAt(this, 1), network.getVoltageAt(this, 2));
      outputVoltage = input >= min && input <= max ? highVoltage : 0.0;
    }
  }

  public static final class ReflectClampNode extends PowerNode {
    private final double minimumValue;
    private final double maximumValue;
    private double outputVoltage;

    public ReflectClampNode(double minimumValue, double maximumValue) {
      super(2);
      this.minimumValue = Math.min(minimumValue, maximumValue);
      this.maximumValue = Math.max(minimumValue, maximumValue);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(outputVoltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, outputVoltage);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double value = network.getVoltageAt(this, 0);
      double span = maximumValue - minimumValue;
      if (span <= 0.0) {
        outputVoltage = minimumValue;
        return;
      }
      double reflected = value;
      while (reflected < minimumValue || reflected > maximumValue) {
        if (reflected < minimumValue) {
          reflected = minimumValue + (minimumValue - reflected);
        }
        if (reflected > maximumValue) {
          reflected = maximumValue - (reflected - maximumValue);
        }
      }
      outputVoltage = reflected;
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
