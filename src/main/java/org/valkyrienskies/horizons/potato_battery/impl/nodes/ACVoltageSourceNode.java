package org.valkyrienskies.horizons.potato_battery.impl.nodes;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;

/** Ideal sinusoidal source: V(port 0) - V(port 1) = peakVoltage * sin(2 pi * phaseCycles). */
public class ACVoltageSourceNode extends PowerNode {
  private final double peakVoltage;
  private final double frequencyHz;
  private double phaseCycles;
  private long completedSubsteps;

  public ACVoltageSourceNode(double peakVoltage, double frequencyHz) {
    this(peakVoltage, frequencyHz, 0);
  }

  public ACVoltageSourceNode(double peakVoltage, double frequencyHz, double phaseCycles) {
    super(2);
    if (!Double.isFinite(peakVoltage) || peakVoltage < 0
        || !Double.isFinite(frequencyHz) || frequencyHz <= 0
        || !Double.isFinite(phaseCycles) || phaseCycles < 0 || phaseCycles >= 1) {
      throw new IllegalArgumentException("Invalid AC source parameters");
    }
    this.peakVoltage = peakVoltage;
    this.frequencyHz = frequencyHz;
    this.phaseCycles = phaseCycles;
  }

  public double getPeakVoltage() { return peakVoltage; }
  public double getFrequencyHz() { return frequencyHz; }
  public double getPhaseCycles() { return phaseCycles; }
  public double getVoltage() { return peakVoltage * Math.sin(2 * Math.PI * phaseCycles); }
  @Override public int getVoltageReferencePort() { return 1; }

  @Override public int getVoltageSourceCount() { return 1; }
  @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
  @Override public double getSuggestedMaxTimeStepSeconds() { return 1 / frequencyHz / 64; }
  // Keep the clock running even when every sample happens to land at the same phase.
  @Override public long getWakeFingerprint() { return completedSubsteps; }

  private double phaseAfter(double timeStepSeconds) {
    // Reduce the elapsed time first to avoid overflowing frequency * dt or accumulating unbounded time.
    double elapsedCycles = (timeStepSeconds % (1 / frequencyHz)) * frequencyHz;
    return (phaseCycles + elapsedCycles) % 1;
  }

  @Override public void stamp(CircuitStampContext context) {
    context.stampVoltageSource(0, 0, 1, peakVoltage * Math.sin(2 * Math.PI * phaseAfter(context.getTimeStep())));
  }

  @Override public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
    phaseCycles = phaseAfter(timeStepSeconds);
    completedSubsteps++;
  }
}
