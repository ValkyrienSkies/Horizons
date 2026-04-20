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

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PowerNetworkServer implements IPowerNetwork<ServerLevel> {
  private static final int MAX_DYNAMIC_LINEAR_SUBSTEPS = 4;
  private static final int MAX_DYNAMIC_NONLINEAR_SUBSTEPS = 64;
  private static final double SLEEP_VOLTAGE_DELTA = 1.0e-6;
  private static final double SLEEP_CURRENT_DELTA = 1.0e-8;

  private final @Nullable ServerLevel level;
  private final @Nullable PhysLevel physLevel;

  private final IPBSolver solver;

  private final Long2ObjectOpenHashMap<IPowerNode> nodes = new Long2ObjectOpenHashMap<>();

  private final HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> nodeEnergyData = new HashMap<>();

  private final ConcurrentLinkedQueue<QueuedChange> updateQueue = new ConcurrentLinkedQueue<>();
  private boolean topologyDirty = true;
  private boolean sleeping;
  private double lastSolveMaxVoltageDelta;
  private double lastSolveMaxCurrentDelta;

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
          nodeEnergyData.remove(removed);
          removed.onRemoved();
        }
      } else {
        nodes.put(change.pos.asLong(), change.node);
        nodeEnergyData.put(change.node, new Long2ObjectOpenHashMap<>(change.node.getPorts()));
        change.node.onAdded();
      }
    });

    updateQueue.clear();
    if (hadQueuedChanges) {
      topologyDirty = true;
      sleeping = false;
    }

    SimulationPolicy simulationPolicy = classifySimulationPolicy();
    if (sleeping && !topologyDirty && simulationPolicy.mode == PowerNodeSimulationMode.STATIC_LINEAR) {
      return;
    }

    lastSolveMaxVoltageDelta = 0.0;
    lastSolveMaxCurrentDelta = 0.0;
    this.solver.step(this, simulationPolicy.subSteps);

    if (simulationPolicy.mode == PowerNodeSimulationMode.STATIC_LINEAR
        && lastSolveMaxVoltageDelta <= SLEEP_VOLTAGE_DELTA
        && lastSolveMaxCurrentDelta <= SLEEP_CURRENT_DELTA) {
      sleeping = true;
    } else {
      sleeping = false;
    }

    topologyDirty = false;
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
    Long2ObjectOpenHashMap<NodeEnergyData> portMap = nodeEnergyData.computeIfAbsent(node, ignored -> new Long2ObjectOpenHashMap<>(node.getPorts()));
    return portMap.computeIfAbsent(port, ignored -> new NodeEnergyData());
  }

  @Override
  public void setNodeEnergyData(IPowerNode node, int port, NodeEnergyData energyData) {
    Long2ObjectOpenHashMap<NodeEnergyData> portMap = nodeEnergyData.computeIfAbsent(node, ignored -> new Long2ObjectOpenHashMap<>(node.getPorts()));
    NodeEnergyData previous = portMap.get(port);
    if (previous != null) {
      lastSolveMaxVoltageDelta = Math.max(lastSolveMaxVoltageDelta, Math.abs(previous.getVoltage() - energyData.getVoltage()));
      lastSolveMaxCurrentDelta = Math.max(lastSolveMaxCurrentDelta, Math.abs(previous.getCurrent() - energyData.getCurrent()));
    }
    portMap.put(port, energyData);
  }

  @Override
  public void clearNodeEnergyData() {
    nodeEnergyData.values().forEach(Long2ObjectOpenHashMap::clear);
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
      return new SimulationPolicy(mode, 1);
    }

    double baseTimeStep = getTimeStepSeconds();
    int requestedSubSteps = (int) Math.ceil(baseTimeStep / suggestedMaxTimeStep);
    int cappedSubSteps = switch (mode) {
      case DYNAMIC_LINEAR -> Math.min(Math.max(requestedSubSteps, 1), MAX_DYNAMIC_LINEAR_SUBSTEPS);
      case DYNAMIC_NONLINEAR -> Math.min(Math.max(requestedSubSteps, 1), MAX_DYNAMIC_NONLINEAR_SUBSTEPS);
      default -> 1;
    };
    return new SimulationPolicy(mode, cappedSubSteps);
  }

  private record QueuedChange(BlockPos pos, @Nullable IPowerNode node) {}
  private record ConnectionLookup(org.valkyrienskies.horizons.potato_battery.api.network.Connection connection) {}
  private record SimulationPolicy(PowerNodeSimulationMode mode, int subSteps) {}
}
