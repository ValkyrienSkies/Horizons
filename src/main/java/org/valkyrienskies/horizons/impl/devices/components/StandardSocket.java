package org.valkyrienskies.horizons.impl.devices.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;
import org.valkyrienskies.horizons.api.devices.IDevice;
import org.valkyrienskies.horizons.api.devices.PowerNetworks;
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

  private final ArrayList<ClientConnection> clientConnections = new ArrayList<>();

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
    if (blockEntity.getLevel() != null && blockEntity.getLevel().isClientSide) {
      ArrayList<ISocket> result = new ArrayList<>();
      for (ClientConnection connection : clientConnections) {
        if (!blockEntity.getLevel().hasChunkAt(connection.pos())) continue;
        if (blockEntity.getLevel().getBlockEntity(connection.pos()) instanceof SocketedBlockEntity device) {
          ISocket socket = device.getSocket(connection.port());
          if (socket != null) result.add(socket);
        }
      }
      return List.copyOf(result);
    }
    if (!(blockEntity instanceof IDevice device) || device.getPowerNetwork() == null) return List.of();
    var data = PowerNetworks.getData(blockEntity.getLevel());
    ArrayList<ISocket> sockets = new ArrayList<>();
    for (var cable : data.getArchive().getConnections(device.getNodeId())) {
      var endpoint = cable.other(device.getNodeId(), port);
      if (endpoint == null) continue;
      IDevice other = data.getDevice(endpoint.nodeId());
      if (other != null) {
        ISocket socket = other.getSocket(endpoint.port());
        if (socket != null) sockets.add(socket);
      }
    }
    return List.copyOf(sockets);
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
    return getConnectedSockets().contains(other);
  }

  @Override public boolean canAcceptConnection(ISocket other) {
    if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide
        || !(blockEntity instanceof IDevice device) || device.getPowerNetwork() == null) return false;
    return PowerNetworks.getData(blockEntity.getLevel()).getArchive().getConnections(device.getNodeId()).stream()
        .filter(cable -> cable.other(device.getNodeId(), port) != null).count() < maxConnections;
  }

  // The network archive is authoritative; these hooks remain available to specialized sockets.
  @Override public void onConnected(ISocket other) {}
  @Override public void onDisconnected(ISocket other) {}

  public void readClientConnections(ListTag list) {
    clientConnections.clear();
    for (int i = 0; i < list.size(); i++) {
      CompoundTag tag = list.getCompound(i);
      if (tag.getInt("Port") == port) clientConnections.add(new ClientConnection(BlockPos.of(tag.getLong("OtherPos")), tag.getInt("OtherPort")));
    }
  }

  @Override
  public void connect(ISocket other) throws SocketConnectionException {
    if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide
        || blockEntity.getLevel() != other.getBlockEntity().getLevel()
        || !(blockEntity instanceof IDevice device) || !(other.getBlockEntity() instanceof IDevice otherDevice)
        || device.getPowerNetwork() == null || device.getPowerNetwork() != otherDevice.getPowerNetwork()
        || device.getPowerNode() == null || otherDevice.getPowerNode() == null) {
      throw new SocketConnectionException("Sockets must belong to the same server network", false, false, false, false);
    }
    double distance = Math.min(getConnectionMaxDistance(), other.getConnectionMaxDistance());
    boolean inRange = getWorldPos().distanceSquared(other.getWorldPos()) <= distance * distance;
    boolean sameSocket = other == this;
    boolean alreadyConnected = isConnectedTo(other);
    boolean maxConnectionsReached = !canAcceptConnection(other) || !other.canAcceptConnection(this);
    if (inRange && !sameSocket && !alreadyConnected && !maxConnectionsReached) {
      var data = PowerNetworks.getData(blockEntity.getLevel());
      try {
        if (!data.getArchive().connect(device.getNodeId(), port, otherDevice.getNodeId(), other.getPort(), 1e-6)) {
          throw new IllegalArgumentException("Duplicate cable");
        }
      } catch (IllegalArgumentException e) {
        throw new SocketConnectionException(e.getMessage(), true, false, false, false);
      }
      onConnected(other);
      other.onConnected(this);
      data.connectionsChanged(device.getNodeId(), otherDevice.getNodeId());
      return;
    }
    throw new SocketConnectionException("Cannot connect to socket: " + other, inRange, sameSocket, alreadyConnected, maxConnectionsReached);
  }

  @Override
  public boolean disconnect(ISocket other) {
    if (!(blockEntity instanceof IDevice device) || !(other.getBlockEntity() instanceof IDevice otherDevice)
        || device.getPowerNetwork() == null || device.getPowerNetwork() != otherDevice.getPowerNetwork()) return false;
    var data = PowerNetworks.getData(blockEntity.getLevel());
    if (!data.getArchive().disconnect(device.getNodeId(), port, otherDevice.getNodeId(), other.getPort())) return false;
    onDisconnected(other);
    other.onDisconnected(this);
    data.connectionsChanged(device.getNodeId(), otherDevice.getNodeId());
    return true;
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
  private record ClientConnection(BlockPos pos, int port) {}
}
