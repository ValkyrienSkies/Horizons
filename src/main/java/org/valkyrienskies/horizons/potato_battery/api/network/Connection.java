package org.valkyrienskies.horizons.potato_battery.api.network;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.jetbrains.annotations.NotNull;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record Connection(
  IPowerNode node,
  int port
) {

  @Override
  public @NotNull String toString() {
    return "Connection{" +
        "node=" + node +
        ", port=" + port +
        '}';
  }
}
