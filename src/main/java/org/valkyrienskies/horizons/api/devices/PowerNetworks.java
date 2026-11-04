package org.valkyrienskies.horizons.api.devices;

import net.minecraft.world.level.Level;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Each server dimension has one electrical network. */
public final class PowerNetworks {
  private static final ConcurrentHashMap<Level, PowerNetworkServer> NETWORKS = new ConcurrentHashMap<>();
  private PowerNetworks() {}

  public static PowerNetworkServer get(Level level) {
    if (level.isClientSide) throw new IllegalArgumentException("Electrical networks are server-owned");
    return NETWORKS.computeIfAbsent(level, ignored -> {
      PowerNetworkServer network = new PowerNetworkServer();
      network.markLoaded();
      return network;
    });
  }

  public static Collection<PowerNetworkServer> getNetworks() { return List.copyOf(NETWORKS.values()); }
  public static void clear() { NETWORKS.clear(); }
}
