package org.valkyrienskies.horizons.impl.render;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.mod.api.ValkyrienSkies;

/** Handles the transforms for sockets on ships */
public final class SocketRenderSpace {
  private SocketRenderSpace() {}
  public static Vector3d toWorld(ISocket socket, Vector3dc blockLocal) {
    var pos = socket.getBlockPos();
    Vector3d point = new Vector3d(blockLocal).add(pos.getX(), pos.getY(), pos.getZ());
    var ship = ValkyrienSkies.getShipManagingBlock(socket.getBlockEntity().getLevel(), pos);
    if (ship instanceof ClientShip clientShip) return clientShip.getRenderTransform().getShipToWorld().transformPosition(point);
    return ValkyrienSkies.positionToWorld(ship, point);
  }
  public static Vec3 toLocal(BlockEntity be, Vec3 world) {
    var ship = ValkyrienSkies.getShipManagingBlock(be.getLevel(), be.getBlockPos());
    if (ship instanceof ClientShip clientShip) return ValkyrienSkies.positionToShip(clientShip.getRenderTransform(), world);
    return ValkyrienSkies.positionToShip(ship, world);
  }
}
