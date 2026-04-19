package org.valkyrienskies.horizons.potato_battery.impl;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.BaseSolver;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PowerNetworkServer implements IPowerNetwork<ServerLevel> {
  private final ServerLevel level;
  private final PhysLevel physLevel;

  private final IPBSolver solver = new BaseSolver();

  private final Long2ObjectOpenHashMap<IPowerNode> nodes = new Long2ObjectOpenHashMap<>();

  private final HashMap<IPowerNode, Long2ObjectOpenHashMap<NodeEnergyData>> nodeEnergyData = new HashMap<>();

  private final ConcurrentLinkedQueue<QueuedChange> updateQueue = new ConcurrentLinkedQueue<>();

  public PowerNetworkServer(ServerLevel level, PhysLevel physLevel) {
    this.level = level;
    this.physLevel = physLevel;
  }

  @Override
  public IPBSolver getSolver() {
    return solver;
  }

  @Override
  public ServerLevel getLevel() {
    return level;
  }

  @Override
  public PhysLevel getPhysLevel() {
    return physLevel;
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
        }
      } else {
        nodes.put(change.pos.asLong(), change.node);
        nodeEnergyData.put(change.node, new Long2ObjectOpenHashMap<>(change.node.getPorts()));
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
    return 0;
  }

  @Override
  public void sync() {

  }

  private record QueuedChange(BlockPos pos, @Nullable IPowerNode node) {}
}
