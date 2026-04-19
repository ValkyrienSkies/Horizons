package org.valkyrienskies.horizons.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.foundation.mixin.SocketHitProvider;

import javax.annotation.Nullable;

@Mixin(Minecraft.class)
public class MixinMinecraft implements SocketHitProvider {

  @Unique
  @Nullable private ISocket horizons$socketHit;

  @Nullable
  @Override
  public ISocket getSocketHit() {
    return horizons$socketHit;
  }

  @Override
  public void setSocketHit(@Nullable ISocket socket) {
    this.horizons$socketHit = socket;
  }
}
