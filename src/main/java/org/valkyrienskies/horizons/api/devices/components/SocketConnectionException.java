package org.valkyrienskies.horizons.api.devices.components;

public class SocketConnectionException extends Exception {
  public final boolean inRange;
  public final boolean sameSocket;
  public final boolean alreadyConnected;
  public final boolean maxConnectionsReached;

  public SocketConnectionException(String message, Boolean... reasons) {
    super(message);
    this.inRange = reasons[0];
    this.sameSocket = reasons[1];
    this.alreadyConnected = reasons[2];
    this.maxConnectionsReached = reasons[3];
  }
}
