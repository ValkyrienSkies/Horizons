package org.valkyrienskies.horizons.potato_battery.api.network;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.jetbrains.annotations.NotNull;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record Connection(
  IPowerNode node,
  int port,
  double resistance
) {

  public Connection(IPowerNode node, int port) {
    this(node, port, 1.0e-6);
  }

  @Override
  public @NotNull String toString() {
    return "Connection{" +
        "node=" + node +
        ", port=" + port +
        ", resistance=" + resistance +
        '}';
  }
}
