package org.valkyrienskies.horizons.potato_battery.impl.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;
import org.joml.Vector3ic;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

import javax.annotation.Nullable;
import java.util.Map;

public class PowerNetworkClient implements IPowerNetwork<ClientLevel> {
  private final ClientLevel level;

  public PowerNetworkClient(ClientLevel level) {
    this.level = level;
  }

  @Override
  public IPBSolver getSolver() {
    return null;
  }

  @Override
  public ClientLevel getLevel() {
    return level;
  }

  @Override
  public PhysLevel getPhysLevel() {
    return null;
  }

  @Override
  public void addNode(BlockPos pos, IPowerNode node) {
    return;
  }

  @Override
  public void removeNode(BlockPos pos) {
    return;
  }

  @Override
  public void tick() {

  }

  @Override
  public void physTick() {

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
}
