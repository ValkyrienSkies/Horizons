package org.valkyrienskies.horizons.api.foundation.mixin;

import net.minecraft.client.Minecraft;
import org.valkyrienskies.horizons.api.devices.components.ISocket;

import javax.annotation.Nullable;

public interface SocketHitProvider {
  @Nullable
  ISocket getSocketHit();

  void setSocketHit(@Nullable ISocket socket);

  static ISocket getHit() {
    return ((SocketHitProvider) Minecraft.getInstance()).getSocketHit();
  }
}
