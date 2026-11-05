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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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

  private final IPBSolver solver;

  private final ConcurrentHashMap<Long, IPowerNode> nodes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Long> positionIds = new ConcurrentHashMap<>();
  private final AtomicLong nextNodeId = new AtomicLong();
  private double timeStepSeconds = DEFAULT_TIME_STEP;

  private volatile HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> publishedNodeEnergyData = new HashMap<>();
  private HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> stagingNodeEnergyData = new HashMap<>();
  private @Nullable Thread solveThread;
  private boolean solveInProgress;

  private final ConcurrentLinkedQueue<Runnable> updateQueue = new ConcurrentLinkedQueue<>();
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
  private boolean lastSolveFailed;
  private int settleConfirmationSolvesRemaining = 1;

  private boolean loaded = false;

  public PowerNetworkServer() {
    this(new JKLUSolver());
  }

  public PowerNetworkServer(IPBSolver solver) {
    this.solver = solver;
  }

  public void markLoaded() {
      loaded = true;
  }

  @Override
  public IPBSolver getSolver() {
    return solver;
  }

  @Override
  public Collection<IPowerNode> getNodes() {
    return java.util.List.copyOf(nodes.values());
  }

  @Override public boolean hasNodes() { return !nodes.isEmpty(); }

  @Override
  public double getTimeStepSeconds() {
    return timeStepSeconds;
  }

  @Override
  public long addNode(IPowerNode node) {
    long id = allocateNodeId();
    updateTopology(() -> {
      nodes.put(id, node);
      node.onAdded();
    });
    return id;
  }

  @Override
  public long addNode(BlockPos pos, IPowerNode node) {
    long id = addNode(node);
    Long previous = positionIds.put(pos.asLong(), id);
    if (previous != null) removeNode(previous);
    return id;
  }

  @Override
  public void removeNode(BlockPos pos) {
    Long id = positionIds.remove(pos.asLong());
    if (id != null) removeNode(id);
  }

  @Override
  public void removeNode(long id) {
    updateTopology(() -> detachNode(id, true));
  }

  private void detachNode(long id, boolean permanent) {
    detachNodes(java.util.Set.of(id), permanent);
  }

  private void detachNodes(java.util.Collection<Long> ids, boolean permanent) {
    java.util.Set<IPowerNode> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (long id : ids) {
      IPowerNode node = nodes.remove(id);
      if (node != null) removed.add(node);
    }
    if (removed.isEmpty()) return;
    positionIds.values().removeIf(ids::contains);
    for (IPowerNode node : nodes.values()) {
      for (int port = 0; port < node.getPorts(); port++) {
        for (var connection : node.getConnections(port)) {
          if (removed.contains(connection.node())) node.removeConnection(connection, port);
        }
      }
    }
    for (IPowerNode node : removed) {
      node.removeAllConnections();
      if (permanent) node.onRemoved();
    }
    HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> remaining = new HashMap<>(publishedNodeEnergyData);
    removed.forEach(remaining::remove);
    publishedNodeEnergyData = remaining;
    stagingNodeEnergyData = new HashMap<>();
  }

  public long allocateNodeId() {
    long id = nextNodeId.getAndUpdate(value -> {
      if (value == Long.MAX_VALUE) throw new IllegalStateException("Power node IDs exhausted");
      return value + 1;
    });
    return id;
  }

  public long getNextNodeId() { return nextNodeId.get(); }

  /** Advance the allocator past restored IDs, including nodes that are not currently active. */
  public void reserveNodeIdsThrough(long id) {
    if (id < 0 || id == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid node ID " + id);
    nextNodeId.accumulateAndGet(id + 1, Math::max);
  }

  /** Restores an already allocated identity, without waiting for another simulation step. */
  public synchronized void restoreNode(long id, IPowerNode node) {
    applyPendingChanges();
    reserveNodeIdsThrough(id);
    if (nodes.putIfAbsent(id, node) != null) throw new IllegalStateException("Duplicate active node " + id);
    node.onAdded();
    invalidateTopology();
  }

  /** Removes only the live instance. Persistence owns the frozen state and logical connections. */
  public synchronized void suspendNode(long id) {
    suspendNodes(java.util.Set.of(id));
  }

  public synchronized void suspendNodes(java.util.Collection<Long> ids) {
    applyPendingChanges();
    detachNodes(ids, false);
    invalidateTopology();
  }

  /** Snapshot/restore barrier. The callback must not access Minecraft worlds or block entities. */
  public synchronized <T> T withState(Supplier<T> action) {
    applyPendingChanges();
    try {
      return action.get();
    } finally {
      applyPendingChanges();
    }
  }

  private void invalidateTopology() {
    solver.invalidateTopology(this);
    topologyDirty = true;
    topologyRevision++;
    sleeping = false;
    settleConfirmationSolvesRemaining = 1;
  }

  private void applyPendingChanges() {
    boolean changed = false;
    Runnable change;
    while ((change = updateQueue.poll()) != null) {
      change.run();
      changed = true;
    }
    if (changed) invalidateTopology();
  }

  @Override
  public @Nullable IPowerNode getNode(long id) {
    return nodes.get(id);
  }

  @Override
  public void updateTopology(Runnable change) {
    updateQueue.add(change);
  }

  @Override
  public void tick(ServerLevel level) {

  }

  @Override
  public void physTick(PhysLevel physLevel) {

  }

  @Override
  public synchronized void energyTick(double timeStep) {
      if (!loaded) {
          return;
      }

    if (!Double.isFinite(timeStep) || timeStep <= 0) throw new IllegalArgumentException("Invalid time step");
    timeStepSeconds = timeStep;
    applyPendingChanges();

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
    lastSolveFailed = solveFeedback.solveFailed();

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
  public double getVoltageBetween(IPowerNode nodeA, int portA, IPowerNode nodeB, int portB) {
    var snapshot = publishedNodeEnergyData;
    NodeEnergyData a = getNodeEnergyData(snapshot, nodeA, portA);
    NodeEnergyData b = getNodeEnergyData(snapshot, nodeB, portB);
    return (a == null ? 0 : a.getVoltage()) - (b == null ? 0 : b.getVoltage());
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
    return new SolverPhaseDiagnostics.SolveFeedback(0, Math.max(subSteps, 1), false, false);
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

  private record ConnectionLookup(org.valkyrienskies.horizons.potato_battery.api.network.Connection connection) {}
  private record SimulationPolicy(PowerNodeSimulationMode mode, int subSteps, int requestedSubSteps) {}

  private void beginSolveSnapshot() {
    // Published maps remain immutable so game-thread readers can safely retain a snapshot.
    stagingNodeEnergyData = new HashMap<>();
    solveThread = Thread.currentThread();
    solveInProgress = true;
  }

  private void publishSolveSnapshot() {
    publishedNodeEnergyData = stagingNodeEnergyData;
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

  public boolean didLastSolveFail() {
    return lastSolveFailed;
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
