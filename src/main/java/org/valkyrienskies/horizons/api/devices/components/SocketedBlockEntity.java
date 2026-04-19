package org.valkyrienskies.horizons.api.devices.components;

public interface SocketedBlockEntity {
  ISocket getSocket(int port);
  int getPorts();
}
