package org.valkyrienskies.horizons.potato_battery.api.network.node;

import org.valkyrienskies.horizons.potato_battery.api.network.Connection;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Represents a node in the power network.
 * <p>
 * A node can have multiple connections to other nodes, and multiple connection points.
 */
public interface IPowerNode {

  //CONNECTIONS

  int getPorts();

  /**
   * Adds a connection between this node and the specified node.
   * @param node The node to connect to.
   * @param port The port on this node to connect to.
   * @param otherPort The port on the other node to connect to.
   * @return Connection if the connection was successfully added, null otherwise.
   */
  @Nullable
  Connection addConnection(IPowerNode node, int port, int otherPort);
  /**
   * Adds a connection between this node and the specified node.
   * @param connection The connection to add.
   * @param port The port on this node to connect to.
   * @return Connection if the connection was successfully added, null otherwise.
   */
  @Nullable
  Connection addConnection(Connection connection, int port);

  /**
   * Removes a connection between this node and the specified node.
   * @param node The node to disconnect from.
   * @param port The port on this node to disconnect from.
   * @param otherPort The port on the other node to disconnect from.
   * @return True if the connection was successfully removed, false otherwise.
   */
  boolean removeConnection(IPowerNode node, int port, int otherPort);
  /**
   * Removes a connection between this node and the specified node.
   * @param connection The connection to remove.
   * @param port The port on this node to disconnect from.
   * @return True if the connection was successfully removed, false otherwise.
   */
  boolean removeConnection(Connection connection, int port);

  /**
   * Removes all connections from one of this node's ports.
   * @param port The port to remove connections from.
   */
  void removeAllConnections(int port);
  /**
   * Removes all connections from this node.
   */
  void removeAllConnections();

  /**
   * Checks if this node can connect to the specified node.
   * @param node The node to check.
   * @param port The port on this node.
   * @param otherPort The port on the other node.
   * @return True if this node can connect to the specified node, false otherwise.
   */
  boolean canConnect(IPowerNode node, int port, int otherPort);
  /**
   * Checks if this node can use the provided connection.
   * @param connection The connection to check.
   * @param port The port on this node.
   * @return True if this node can use the provided connection, false otherwise.
   */
  boolean canConnect(Connection connection, int port);

  /**
   * Gets a list of all connections from this node.
   * @return A list of all connections from this node.
   */
  List<Connection> getConnections();
  /**
   * Gets a list of all connections from one of this node's ports.
   * @param port The port to get connections from.
   * @return A list of all connections from the specified port.
   */
  List<Connection> getConnections(int port);

  boolean isConnected(IPowerNode node);
  boolean isConnected(IPowerNode node, int port);
  boolean isConnected(IPowerNode node, int port, int otherPort);

  //PROPERTIES
  double getResistance();
  double getConductivity();
  double getCapacitance();
  double getInductance();

  /**
   * Gets the source of the current at the specified port. If the port is not a source, it returns 0.
   * @param port The port to get the current source of.
   * @return The current source of the specified port, or 0 if the port is not a source.
   */
  double getCurrentSource(int port);

  //CONNECTION HELPERS

  double getResistanceOver(IPowerNode node, int port, int otherPort);
  double getResistanceOver(Connection connection, int port);


  //EVENTS
  void onAdded();
  void onRemoved();
}
