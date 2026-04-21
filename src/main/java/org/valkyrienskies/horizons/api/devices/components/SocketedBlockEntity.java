package org.valkyrienskies.horizons.api.devices.components;

import java.util.List;

public interface SocketedBlockEntity {
  List<ISocket> getSockets();
  ISocket getSocket(int port);
  int getPorts();
}
