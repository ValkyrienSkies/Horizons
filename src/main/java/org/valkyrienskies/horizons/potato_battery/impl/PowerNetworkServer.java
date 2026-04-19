package org.valkyrienskies.horizons.potato_battery.impl;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PowerNetworkServer implements IPowerNetwork<ServerLevel> {
  private final @Nullable ServerLevel level;
  private final @Nullable PhysLevel physLevel;

  private final IPBSolver solver;

  private final Long2ObjectOpenHashMap<IPowerNode> nodes = new Long2ObjectOpenHashMap<>();

  private final HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> nodeEnergyData = new HashMap<>();

  private final ConcurrentLinkedQueue<QueuedChange> updateQueue = new ConcurrentLinkedQueue<>();

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

    this.solver.step(this, 1);
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
    nodeEnergyData.computeIfAbsent(node, ignored -> new Long2ObjectOpenHashMap<>(node.getPorts())).put(port, energyData);
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

  private record QueuedChange(BlockPos pos, @Nullable IPowerNode node) {}
  private record ConnectionLookup(org.valkyrienskies.horizons.potato_battery.api.network.Connection connection) {}
}
