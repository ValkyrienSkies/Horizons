package org.valkyrienskies.horizons.api.devices.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;

import java.util.List;

public interface ISocket {

  BlockPos getBlockPos();
  BlockEntity getBlockEntity();

  Vector3dc getPos();
  Vector3dc getNormal();
  AABBdc getBounds();

  List<ISocket> getConnectedSockets();

  boolean isConnectedTo(ISocket other);
  boolean connect(ISocket other);
  boolean disconnect(ISocket other);
}
