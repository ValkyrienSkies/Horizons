package org.valkyrienskies.horizons.potato_battery.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;
import org.joml.Vector3ic;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.network.Connection;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

import javax.annotation.Nullable;
import java.util.Map;

public interface IPowerNetwork<T extends Level> {
  IPBSolver getSolver();
  T getLevel();
  PhysLevel getPhysLevel();

  void addNode(BlockPos pos, IPowerNode node);
  void removeNode(BlockPos pos);

  void tick();
  void physTick();

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

  void sync();
}
