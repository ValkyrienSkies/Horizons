package org.valkyrienskies.horizons.api.devices.components;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import javax.annotation.Nullable;

/** Raycasts socket bounds in the block's coordinate space, including rotated/scaled ships. */
public final class SocketTargeting {
  private SocketTargeting() {}

  public static @Nullable ISocket find(BlockEntity be, Vec3 worldStart, Vec3 worldEnd) {
    if (!(be instanceof SocketedBlockEntity device)) return null;
    var ship = ValkyrienSkies.getShipManagingBlock(be.getLevel(), be.getBlockPos());
    Vec3 start = ValkyrienSkies.positionToShip(ship, worldStart);
    Vec3 end = ValkyrienSkies.positionToShip(ship, worldEnd);
    return findLocal(device, start, end);
  }

  public static @Nullable ISocket findLocal(SocketedBlockEntity device, Vec3 start, Vec3 end) {
    ISocket closest = null;
    double nearest = Double.POSITIVE_INFINITY;
    for (ISocket socket : device.getSockets()) {
      var b = socket.getBounds();
      var pos = socket.getBlockPos();
      AABB bounds = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).move(pos).inflate(0.01);
      var hit = bounds.clip(start, end);
      if (hit.isPresent() && start.distanceToSqr(hit.get()) < nearest) {
        closest = socket;
        nearest = start.distanceToSqr(hit.get());
      }
    }
    return closest;
  }
}
