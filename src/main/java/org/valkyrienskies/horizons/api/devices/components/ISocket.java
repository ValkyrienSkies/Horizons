package org.valkyrienskies.horizons.api.devices.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.horizons.impl.render.SocketRenderer;

import java.util.List;

public interface ISocket {

  BlockPos getBlockPos();
  BlockEntity getBlockEntity();

  Vector3dc getRelativePos();
  Vector3dc getWorldPos();

  Vector3dc getPos();
  Vector3dc getNormal();
  AABBdc getBounds();

  List<ISocket> getConnectedSockets();
  int getPort();

  double getConnectionMaxDistance();

  boolean isConnectedTo(ISocket other);
  void connect(ISocket other) throws SocketConnectionException;
  boolean disconnect(ISocket other);

  @OnlyIn(Dist.CLIENT)
  SocketRenderer.ISocketRenderer createRenderer();
}
