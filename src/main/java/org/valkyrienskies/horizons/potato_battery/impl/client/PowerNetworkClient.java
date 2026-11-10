package org.valkyrienskies.horizons.potato_battery.impl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

public class PowerNetworkClient implements IPowerNetwork<ClientLevel> {

  public PowerNetworkClient() {}

  @Override
  public IPBSolver getSolver() {
    return null;
  }

  @Override
  public Collection<IPowerNode> getNodes() {
    return List.of();
  }

  @Override
  public double getTimeStepSeconds() {
    return DEFAULT_TIME_STEP;
  }

  @Override
  public long addNode(IPowerNode node) {
    throw new UnsupportedOperationException("Nodes are server-owned");
  }

  @Override
  public long addNode(BlockPos pos, IPowerNode node) {
    return addNode(node);
  }

  @Override
  public void removeNode(long id) {}

  @Override
  public @Nullable IPowerNode getNode(long id) { return null; }

  @Override
  public void updateTopology(Runnable change) {
    throw new UnsupportedOperationException("Nodes are server-owned");
  }

  @Override
  public void removeNode(BlockPos pos) {
    return;
  }

  @Override
  public void tick(ClientLevel level) {

  }

  @Override
  public void physTick(PhysLevel physLevel) {

  }

  @Override
  public void energyTick(double timeStep) {

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
  public double getVoltageAt(IPowerNode node, int port) {
    return 0;
  }

  @Override
  public NodeEnergyData getNodeEnergyData(IPowerNode node, int port) {
    return new NodeEnergyData();
  }

  @Override
  public void setNodeEnergyData(IPowerNode node, int port, NodeEnergyData energyData) {
  }

  @Override
  public void clearNodeEnergyData() {
  }

  @Override
  public void sync() {

  }
}
