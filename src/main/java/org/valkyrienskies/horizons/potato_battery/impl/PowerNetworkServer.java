package org.valkyrienskies.horizons.potato_battery.impl;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.SolverPhaseDiagnostics;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.TopologyCacheableNetwork;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PowerNetworkServer implements IPowerNetwork<ServerLevel>, TopologyCacheableNetwork {
  private static final int MIN_DYNAMIC_NONLINEAR_SUBSTEPS = 4;
  private static final int MAX_DYNAMIC_LINEAR_SUBSTEPS = 4;
  private static final int MAX_DYNAMIC_NONLINEAR_SUBSTEPS = 48;
  private static final double SLEEP_VOLTAGE_DELTA = 1.0e-6;
  private static final double SLEEP_CURRENT_DELTA = 1.0e-8;
  private static final double NONLINEAR_ITERATIONS_PER_SUBSTEP_INCREASE = 12.0;
  private static final double NONLINEAR_ITERATIONS_PER_SUBSTEP_DECREASE = 4.0;
  private static final double NONLINEAR_HIGH_MOTION_VOLTAGE_DELTA = 0.20;
  private static final double NONLINEAR_HIGH_MOTION_CURRENT_DELTA = 0.02;
  private static final double NONLINEAR_LOW_MOTION_VOLTAGE_DELTA = 0.02;
  private static final double NONLINEAR_LOW_MOTION_CURRENT_DELTA = 0.002;
  private static final int NONLINEAR_SETTLED_TICKS_TO_DECREASE = 3;

  private final @Nullable ServerLevel level;
  private final @Nullable PhysLevel physLevel;

  private final IPBSolver solver;

  private final Long2ObjectOpenHashMap<IPowerNode> nodes = new Long2ObjectOpenHashMap<>();

  private volatile HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> publishedNodeEnergyData = new HashMap<>();
  private HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> stagingNodeEnergyData = new HashMap<>();
  private @Nullable Thread solveThread;
  private boolean solveInProgress;

  private final ConcurrentLinkedQueue<QueuedChange> updateQueue = new ConcurrentLinkedQueue<>();
  private boolean topologyDirty = true;
  private long topologyRevision;
  private boolean sleeping;
  private double lastSolveMaxVoltageDelta;
  private double lastSolveMaxCurrentDelta;
  private long lastWakeFingerprint;
  private int adaptiveDynamicNonlinearSubsteps = MIN_DYNAMIC_NONLINEAR_SUBSTEPS;
  private int lowEffortNonlinearTicks;
  private int dynamicNonlinearSubstepCeiling = MAX_DYNAMIC_NONLINEAR_SUBSTEPS;
  private int lastRequestedSubsteps = 1;
  private int lastNonlinearIterations;
  private boolean lastIterationLimitHit;
  private int settleConfirmationSolvesRemaining = 1;

  public PowerNetworkServer(@Nullable ServerLevel level, @Nullable PhysLevel physLevel) {
    this(level, physLevel, new JKLUSolver());
  }

  public PowerNetworkServer(@Nullable ServerLevel level, @Nullable PhysLevel physLevel, IPBSolver solver) {
    this.level = level;
    this.physLevel = physLevel;
    this.solver = solver;
  }

  @Override
  public IPBSolver getSolver() {
    return solver;
  }

  @Override
  @Nullable
  public ServerLevel getLevel() {
    return level;
  }

  @Override
  @Nullable
  public PhysLevel getPhysLevel() {
    return physLevel;
  }

  @Override
  public Collection<IPowerNode> getNodes() {
    return nodes.values();
  }

  @Override
  public double getTimeStepSeconds() {
    return DEFAULT_TIME_STEP;
  }

  @Override
  public void addNode(BlockPos pos, IPowerNode node) {
    updateQueue.add(new QueuedChange(pos, node));
  }

  @Override
  public void removeNode(BlockPos pos) {
    updateQueue.add(new QueuedChange(pos, null));
  }

  @Override
  public void tick() {

  }

  @Override
  public void physTick() {
    boolean hadQueuedChanges = !updateQueue.isEmpty();
    updateQueue.forEach(change -> {
      if (change.node == null) {
        IPowerNode removed = nodes.remove(change.pos.asLong());
        if (removed != null) {
          publishedNodeEnergyData.remove(removed);
          stagingNodeEnergyData.remove(removed);
          removed.onRemoved();
        }
      } else {
        nodes.put(change.pos.asLong(), change.node);
        publishedNodeEnergyData.putIfAbsent(change.node, new Long2ObjectOpenHashMap<>(change.node.getPorts()));
        change.node.onAdded();
      }
    });

    updateQueue.clear();
    if (hadQueuedChanges) {
      topologyDirty = true;
      topologyRevision++;
      sleeping = false;
      settleConfirmationSolvesRemaining = 1;
    }

    SimulationPolicy simulationPolicy = classifySimulationPolicy();
    lastRequestedSubsteps = simulationPolicy.subSteps;
    long wakeFingerprint = computeWakeFingerprint();
    boolean wakeFingerprintChanged = wakeFingerprint != lastWakeFingerprint;
    if (wakeFingerprintChanged) {
      sleeping = false;
      settleConfirmationSolvesRemaining = 1;
    }

    if (sleeping
        && !topologyDirty
        && !wakeFingerprintChanged
        && simulationPolicy.mode != PowerNodeSimulationMode.DYNAMIC_NONLINEAR) {
      return;
    }

    lastSolveMaxVoltageDelta = 0.0;
    lastSolveMaxCurrentDelta = 0.0;
    beginSolveSnapshot();
    SolverPhaseDiagnostics.SolveFeedback solveFeedback = simulationPolicy.mode == PowerNodeSimulationMode.DYNAMIC_NONLINEAR
        ? SolverPhaseDiagnostics.stepWithFeedback(this.solver, this, simulationPolicy.subSteps)
        : stepWithoutFeedback(simulationPolicy.subSteps);
    publishSolveSnapshot();
    lastNonlinearIterations = solveFeedback.nonlinearIterations();
    lastIterationLimitHit = solveFeedback.iterationLimitHit();

    boolean settledLinear = lastSolveMaxVoltageDelta <= SLEEP_VOLTAGE_DELTA
        && lastSolveMaxCurrentDelta <= SLEEP_CURRENT_DELTA;
    if ((simulationPolicy.mode == PowerNodeSimulationMode.STATIC_LINEAR
        || simulationPolicy.mode == PowerNodeSimulationMode.DYNAMIC_LINEAR)
        && settledLinear) {
      if (settleConfirmationSolvesRemaining > 0) {
        settleConfirmationSolvesRemaining--;
        sleeping = false;
      } else {
        sleeping = true;
      }
    } else {
      sleeping = false;
      if (simulationPolicy.mode != PowerNodeSimulationMode.DYNAMIC_NONLINEAR) {
        settleConfirmationSolvesRemaining = 1;
      }
    }

    topologyDirty = false;
    lastWakeFingerprint = wakeFingerprint;
    updateAdaptiveNonlinearSubsteps(simulationPolicy, solveFeedback);
  }

  @Override
  public void onChunkUnloaded() {

  }

  @Override
  public void onChunkLoaded() {

  }

  @Override
  public double getCurrentOver(IPowerNode nodeA, @Nullable IPowerNode nodeB, int port, int otherPort) {
    ConnectionLookup lookup = resolveConnection(nodeA, nodeB, port, otherPort);
    if (lookup == null) {
      return 0.0;
    }

    double resistance = Math.max(lookup.connection.resistance(), 1.0e-12);
    double voltageA = getVoltageAt(nodeA, port);
    double voltageB = getVoltageAt(lookup.connection.node(), lookup.connection.port());
    return (voltageA - voltageB) / resistance;
  }

  @Override
  public double getVoltageAt(IPowerNode node, int port) {
    return getNodeEnergyData(node, port).getVoltage();
  }

  @Override
  public NodeEnergyData getNodeEnergyData(IPowerNode node, int port) {
    NodeEnergyData fromSolve = solveThread == Thread.currentThread() ? getNodeEnergyData(stagingNodeEnergyData, node, port) : null;
    if (fromSolve != null) {
      return fromSolve;
    }

    NodeEnergyData published = getNodeEnergyData(publishedNodeEnergyData, node, port);
    return published != null ? published : new NodeEnergyData();
  }

  @Override
  public void setNodeEnergyData(IPowerNode node, int port, NodeEnergyData energyData) {
    HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> target = mutableEnergyDataTarget();
    Long2ObjectOpenHashMap<NodeEnergyData> portMap = target.computeIfAbsent(node, ignored -> new Long2ObjectOpenHashMap<>(node.getPorts()));
    NodeEnergyData previous = portMap.get(port);
    if (previous == null) {
      previous = getNodeEnergyData(publishedNodeEnergyData, node, port);
    }
    if (previous != null) {
      lastSolveMaxVoltageDelta = Math.max(lastSolveMaxVoltageDelta, Math.abs(previous.getVoltage() - energyData.getVoltage()));
      lastSolveMaxCurrentDelta = Math.max(lastSolveMaxCurrentDelta, Math.abs(previous.getCurrent() - energyData.getCurrent()));
    }
    portMap.put(port, energyData);
  }

  @Override
  public void clearNodeEnergyData() {
    mutableEnergyDataTarget().clear();
  }

  @Override
  public void sync() {

  }

  private @Nullable ConnectionLookup resolveConnection(IPowerNode nodeA, @Nullable IPowerNode nodeB, int port, int otherPort) {
    for (var connection : nodeA.getConnections(port)) {
      if (connection.port() != otherPort) {
        continue;
      }

      if (nodeB == null || connection.node().equals(nodeB)) {
        return new ConnectionLookup(connection);
      }
    }

    return null;
  }

  private SimulationPolicy classifySimulationPolicy() {
    PowerNodeSimulationMode mode = PowerNodeSimulationMode.STATIC_LINEAR;
    double suggestedMaxTimeStep = Double.POSITIVE_INFINITY;

    for (IPowerNode node : nodes.values()) {
      PowerNodeSimulationMode nodeMode = node.getSimulationMode();
      if (nodeMode.ordinal() > mode.ordinal()) {
        mode = nodeMode;
      }
      suggestedMaxTimeStep = Math.min(suggestedMaxTimeStep, node.getSuggestedMaxTimeStepSeconds());
    }

    if (mode == PowerNodeSimulationMode.STATIC_LINEAR || !Double.isFinite(suggestedMaxTimeStep) || suggestedMaxTimeStep <= 0.0) {
      return new SimulationPolicy(mode, 1, 1);
    }

    double baseTimeStep = getTimeStepSeconds();
    int requestedSubSteps = (int) Math.ceil(baseTimeStep / suggestedMaxTimeStep);
    int cappedSubSteps = switch (mode) {
      case DYNAMIC_LINEAR -> Math.min(Math.max(requestedSubSteps, 1), MAX_DYNAMIC_LINEAR_SUBSTEPS);
      case DYNAMIC_NONLINEAR -> {
        int maxAllowed = Math.min(Math.max(requestedSubSteps, 1), dynamicNonlinearSubstepCeiling);
        int target = Math.min(Math.max(adaptiveDynamicNonlinearSubsteps, MIN_DYNAMIC_NONLINEAR_SUBSTEPS), maxAllowed);
        yield Math.max(target, 1);
      }
      default -> 1;
    };
    return new SimulationPolicy(mode, cappedSubSteps, Math.max(requestedSubSteps, 1));
  }

  private SolverPhaseDiagnostics.SolveFeedback stepWithoutFeedback(int subSteps) {
    this.solver.step(this, subSteps);
    return new SolverPhaseDiagnostics.SolveFeedback(0, Math.max(subSteps, 1), false);
  }

  private void updateAdaptiveNonlinearSubsteps(
      SimulationPolicy simulationPolicy,
      SolverPhaseDiagnostics.SolveFeedback solveFeedback
  ) {
    if (simulationPolicy.mode != PowerNodeSimulationMode.DYNAMIC_NONLINEAR) {
      adaptiveDynamicNonlinearSubsteps = MIN_DYNAMIC_NONLINEAR_SUBSTEPS;
      dynamicNonlinearSubstepCeiling = MAX_DYNAMIC_NONLINEAR_SUBSTEPS;
      lowEffortNonlinearTicks = 0;
      return;
    }

    int usedSubsteps = Math.max(solveFeedback.substeps(), 1);
    double iterationsPerSubstep = (double) solveFeedback.nonlinearIterations() / usedSubsteps;
    double voltageDeltaPerSubstep = lastSolveMaxVoltageDelta / usedSubsteps;
    double currentDeltaPerSubstep = lastSolveMaxCurrentDelta / usedSubsteps;
    int requestedCeiling = Math.min(Math.max(simulationPolicy.requestedSubSteps, MIN_DYNAMIC_NONLINEAR_SUBSTEPS), MAX_DYNAMIC_NONLINEAR_SUBSTEPS);
    dynamicNonlinearSubstepCeiling = Math.max(dynamicNonlinearSubstepCeiling, requestedCeiling);
    boolean highMotion = voltageDeltaPerSubstep >= NONLINEAR_HIGH_MOTION_VOLTAGE_DELTA
        || currentDeltaPerSubstep >= NONLINEAR_HIGH_MOTION_CURRENT_DELTA;
    boolean lowMotion = voltageDeltaPerSubstep <= NONLINEAR_LOW_MOTION_VOLTAGE_DELTA
        && currentDeltaPerSubstep <= NONLINEAR_LOW_MOTION_CURRENT_DELTA;

    if (solveFeedback.iterationLimitHit()
        || iterationsPerSubstep >= NONLINEAR_ITERATIONS_PER_SUBSTEP_INCREASE
        || (highMotion && iterationsPerSubstep >= NONLINEAR_ITERATIONS_PER_SUBSTEP_INCREASE * 0.75)) {
      int increased = adaptiveDynamicNonlinearSubsteps + Math.max(adaptiveDynamicNonlinearSubsteps / 2, 1);
      adaptiveDynamicNonlinearSubsteps = Math.min(increased, dynamicNonlinearSubstepCeiling);
      lowEffortNonlinearTicks = 0;
      return;
    }

    if (iterationsPerSubstep <= NONLINEAR_ITERATIONS_PER_SUBSTEP_DECREASE && lowMotion) {
      lowEffortNonlinearTicks++;
      if (lowEffortNonlinearTicks >= NONLINEAR_SETTLED_TICKS_TO_DECREASE) {
        adaptiveDynamicNonlinearSubsteps = Math.max(adaptiveDynamicNonlinearSubsteps / 2, MIN_DYNAMIC_NONLINEAR_SUBSTEPS);
        dynamicNonlinearSubstepCeiling = Math.max(adaptiveDynamicNonlinearSubsteps, requestedCeiling);
        lowEffortNonlinearTicks = 0;
      }
    } else {
      lowEffortNonlinearTicks = 0;
      if (!solveFeedback.iterationLimitHit()
          && iterationsPerSubstep < NONLINEAR_ITERATIONS_PER_SUBSTEP_INCREASE * 0.75
          && !highMotion) {
        dynamicNonlinearSubstepCeiling = Math.max(requestedCeiling, Math.max(adaptiveDynamicNonlinearSubsteps, MIN_DYNAMIC_NONLINEAR_SUBSTEPS));
      }
    }
  }

  private long computeWakeFingerprint() {
    long fingerprint = 0xcbf29ce484222325L;
    for (IPowerNode node : nodes.values()) {
      long nodeFingerprint = node.getWakeFingerprint();
      fingerprint ^= nodeFingerprint + 0x9e3779b97f4a7c15L + Long.rotateLeft(fingerprint, 6) + (fingerprint >>> 2);
    }
    return fingerprint;
  }

  private record QueuedChange(BlockPos pos, @Nullable IPowerNode node) {}
  private record ConnectionLookup(org.valkyrienskies.horizons.potato_battery.api.network.Connection connection) {}
  private record SimulationPolicy(PowerNodeSimulationMode mode, int subSteps, int requestedSubSteps) {}

  private void beginSolveSnapshot() {
    stagingNodeEnergyData.clear();
    solveThread = Thread.currentThread();
    solveInProgress = true;
  }

  private void publishSolveSnapshot() {
    HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> oldPublished = publishedNodeEnergyData;
    publishedNodeEnergyData = stagingNodeEnergyData;
    stagingNodeEnergyData = oldPublished;
    solveInProgress = false;
    solveThread = null;
  }

  private HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> mutableEnergyDataTarget() {
    return solveThread == Thread.currentThread() ? stagingNodeEnergyData : publishedNodeEnergyData;
  }

  private static @Nullable NodeEnergyData getNodeEnergyData(
      HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> snapshot,
      IPowerNode node,
      int port
  ) {
    Long2ObjectOpenHashMap<NodeEnergyData> portMap = snapshot.get(node);
    return portMap == null ? null : portMap.get(port);
  }

  public int getLastRequestedSubsteps() {
    return lastRequestedSubsteps;
  }

  public int getLastNonlinearIterations() {
    return lastNonlinearIterations;
  }

  public boolean wasLastIterationLimitHit() {
    return lastIterationLimitHit;
  }

  public int getAdaptiveDynamicNonlinearSubsteps() {
    return adaptiveDynamicNonlinearSubsteps;
  }

  public boolean isSolveInProgress() {
    return solveInProgress;
  }

  @Override
  public long getTopologyRevision() {
    return topologyRevision;
  }
}
