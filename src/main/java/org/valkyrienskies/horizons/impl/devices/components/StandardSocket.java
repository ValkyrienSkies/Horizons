package org.valkyrienskies.horizons.impl.devices.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.devices.components.SocketConnectionException;
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity;
import org.valkyrienskies.horizons.impl.render.SocketRenderer;
import org.valkyrienskies.mod.api.ValkyrienSkies;

import java.util.ArrayList;
import java.util.List;

public class StandardSocket implements ISocket {
  private final int port;
  private final int maxConnections;

  private final BlockEntity blockEntity;
  private final Vector3dc pos;
  private final Vector3dc normal;
  private final AABBdc bounds;

  private final ArrayList<ISocket> connectedSockets = new ArrayList<>();

  private double maxDistance = 10.0;

  public StandardSocket(int port, int maxConnections, BlockEntity blockEntity, Vector3dc pos, Vector3dc normal, AABBdc bounds) {
    this.port = port;
    this.maxConnections = maxConnections;
    this.blockEntity = blockEntity;
    this.pos = pos;
    this.normal = normal;
    this.bounds = bounds;
  }

  public StandardSocket(int port, int maxConnections, BlockEntity blockEntity, Vector3dc pos, Vector3dc normal, AABBdc bounds, double maxDistance) {
    this.port = port;
    this.maxConnections = maxConnections;
    this.blockEntity = blockEntity;
    this.pos = pos;
    this.normal = normal;
    this.bounds = bounds;
    this.maxDistance = maxDistance;
  }

  @Override
  public BlockPos getBlockPos() {
      return blockEntity.getBlockPos();
  }

  @Override
  public BlockEntity getBlockEntity() {
      return blockEntity;
  }

  @Override
  public Vector3dc getRelativePos() {
    BlockPos block = getBlockPos();
    return this.pos.add(block.getX(), block.getY(),block.getZ(), new Vector3d());
  }

  @Override
  public Vector3dc getWorldPos() {
    return ValkyrienSkies.positionToWorld(blockEntity.getLevel(), new Vector3d(getRelativePos()));
  }

  @Override
  public Vector3dc getPos() {
      return pos;
  }

  @Override
  public Vector3dc getNormal() {
      return normal;
  }

  @Override
  public AABBdc getBounds() {
      return bounds;
  }

  @Override
  public List<ISocket> getConnectedSockets() {
      return connectedSockets;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public double getConnectionMaxDistance() {
    return maxDistance;
  }

  @Override
  public boolean isConnectedTo(ISocket other) {
      return connectedSockets.contains(other);
  }

  @Override
  public void connect(ISocket other) throws SocketConnectionException {
    boolean inRange = getWorldPos().distanceSquared(other.getWorldPos()) <= getConnectionMaxDistance() * getConnectionMaxDistance();
    boolean sameSocket = other == this;
    boolean alreadyConnected = isConnectedTo(other);
    boolean maxConnectionsReached = connectedSockets.size() >= maxConnections;
    if (inRange && !sameSocket && !alreadyConnected && !maxConnectionsReached) {
      connectedSockets.add(other);
      other.getConnectedSockets().add(this);
      return;
    }
    throw new SocketConnectionException("Cannot connect to socket: " + other, inRange, sameSocket, alreadyConnected, maxConnectionsReached);
  }

  @Override
  public boolean disconnect(ISocket other) {
      return connectedSockets.remove(other) && other.getConnectedSockets().remove(this);
  }

  @Override
  public String toString() {
    BlockPos pos = getBlockPos();
    int x = pos.getX();
    int y = pos.getY();
    int z = pos.getZ();
    return "StandardSocket(BlockPos: [" + x + ", " + y + ", " + z + "], Port:" + port + ")";
  }

  @Override
  public SocketRenderer.ISocketRenderer createRenderer() {
    return new SocketRenderer.AABBSocketRenderer(this.getBounds());
  }
}
