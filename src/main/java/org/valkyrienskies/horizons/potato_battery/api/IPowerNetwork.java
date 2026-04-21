package org.valkyrienskies.horizons.potato_battery.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

import javax.annotation.Nullable;
import java.util.Collection;

public interface IPowerNetwork<T extends Level> {
  double DEFAULT_TIME_STEP = 1.0 / 60.0;

  IPBSolver getSolver();
  Collection<IPowerNode> getNodes();
  double getTimeStepSeconds();

  void addNode(BlockPos pos, IPowerNode node);
  void removeNode(BlockPos pos);

  void tick(T level);
  void physTick(PhysLevel physLevel);
  void energyTick(double timeStep);

  void onChunkUnloaded();
  void onChunkLoaded();

  /**
   * Calculates the current over a connection between this node and the connected node.
   * @param nodeA The node on one end of the connection.
   * @param nodeB The node on the other end of the connection. If null, uses nodeA.
   * @param port The port on this node.
   * @param otherPort The port on the other node.
   * @return The current over the connection, or 0 if the connection is not valid.
   */
  double getCurrentOver(IPowerNode nodeA, @Nullable IPowerNode nodeB, int port, int otherPort);

  double getVoltageAt(IPowerNode node, int port);

  NodeEnergyData getNodeEnergyData(IPowerNode node, int port);

  void setNodeEnergyData(IPowerNode node, int port, NodeEnergyData energyData);

  void clearNodeEnergyData();

  void sync();
}
