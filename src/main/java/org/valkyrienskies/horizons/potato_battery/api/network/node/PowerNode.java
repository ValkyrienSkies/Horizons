package org.valkyrienskies.horizons.potato_battery.api.network.node;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.Connection;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;

public abstract class PowerNode implements IPowerNode {

  private final int ports;
  private final Long2ObjectOpenHashMap<HashSet<Connection>> connections;

  public PowerNode(int ports) {
    this.ports = ports;
    this.connections = new Long2ObjectOpenHashMap<>(ports);
  }

  public int getPorts() {
    return ports;
  }

  @Override
  @Nullable
  public Connection addConnection(IPowerNode node, int port, int otherPort) {
    return this.addConnection(node, port, otherPort, 1.0e-6);
  }

  @Override
  @Nullable
  public Connection addConnection(IPowerNode node, int port, int otherPort, double resistance) {
    return this.addConnection(new Connection(node, otherPort, resistance), port);
  }

  @Override
  @Nullable
  public Connection addConnection(Connection connection, int port) {
    return this.connections.computeIfAbsent(port, k -> new HashSet<>()).add(connection) ? connection : null;
  }

  @Override
  public boolean removeConnection(IPowerNode node, int port, int otherPort) {
    return this.connections.get(port) != null && this.connections.get(port).remove(new Connection(node, otherPort));
  }

  @Override
  public boolean removeConnection(Connection connection, int port) {
    return this.connections.get(port) != null && this.connections.get(port).remove(connection);
  }

  @Override
  public void removeAllConnections(int port) {
    this.connections.remove(port);
  }

  @Override
  public void removeAllConnections() {
    this.connections.clear();
  }

  @Override
  public boolean canConnect(IPowerNode node, int port, int otherPort) {
    return true;
  }

  @Override
  public boolean canConnect(Connection connection, int port) {
    return true;
  }

  @Override
  public List<Connection> getConnections() {
    return connections.values().stream().flatMap(HashSet::stream).toList();
  }

  @Override
  public List<Connection> getConnections(int port) {
    return connections.get(port) == null ? List.of() : connections.get(port).stream().toList();
  }

  @Override
  public boolean isConnected(IPowerNode node) {
    return connections.values().stream().flatMap(HashSet::stream).anyMatch(c -> c.node().equals(node));
  }

  @Override
  public boolean isConnected(IPowerNode node, int port) {
    return connections.get(port) != null && connections.get(port).stream().anyMatch(c -> c.node().equals(node));
  }

  @Override
  public boolean isConnected(IPowerNode node, int port, int otherPort) {
    return connections.get(port) != null && connections.get(port).stream().anyMatch(c -> c.node().equals(node) && c.port() == otherPort);
  }

  @Override
  public int getVoltageSourceCount() {
    return 0;
  }

  @Override
  public void stamp(org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext context) {
  }

  @Override
  public PowerNodeSimulationMode getSimulationMode() {
    return PowerNodeSimulationMode.STATIC_LINEAR;
  }

  @Override
  public double getSuggestedMaxTimeStepSeconds() {
    return Double.POSITIVE_INFINITY;
  }

  @Override
  public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
  }

  @Override
  public long getWakeFingerprint() {
    return 0L;
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRemoved() {

  }
}
