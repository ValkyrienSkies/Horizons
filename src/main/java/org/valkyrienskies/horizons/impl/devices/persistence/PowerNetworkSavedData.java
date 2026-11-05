package org.valkyrienskies.horizons.impl.devices.persistence;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.valkyrienskies.horizons.api.devices.IDevice;
import org.valkyrienskies.horizons.api.devices.DeviceState;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import java.util.*;


public final class PowerNetworkSavedData extends SavedData {
  public static final String FILE_NAME = "horizons_power_networks";
  private final PowerNetworkArchive archive;
  private final Map<Long, IDevice> devices = new HashMap<>();
  private RuntimeException loadFailure;

  public PowerNetworkSavedData() {
    PowerNetworkServer network = new PowerNetworkServer();
    network.markLoaded();
    archive = new PowerNetworkArchive(network, this::setDirty);
  }

  public static PowerNetworkSavedData load(CompoundTag tag) {
    PowerNetworkSavedData data = new PowerNetworkSavedData();
    try {
      data.archive.read(tag);
    } catch (RuntimeException failure) {
      // DimensionDataStorage otherwise treats a failed loader as absent data and creates an empty archive.
      data.loadFailure = failure;
    }
    return data;
  }

  public PowerNetworkArchive getArchive() {
    if (loadFailure != null) throw new IllegalStateException("Cannot load saved power networks; archive was not replaced", loadFailure);
    return archive;
  }

  @Override public boolean isDirty() {
    // Addon node state can change during simulation without topology changing. Snapshot it at every world save.
    return loadFailure == null && (super.isDirty() || archive.hasActiveNodes());
  }

  @Override public CompoundTag save(CompoundTag tag) { return getArchive().write(tag); }

  public static String ownerType(IDevice device) {
    return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(device.asBlockEntity().getType()).toString();
  }

  public void attach(IDevice device) {
    var be = device.asBlockEntity();
    if (be.isRemoved()) return;
    if (be.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
      var chunk = level.getChunkSource().getChunkNow(be.getBlockPos().getX() >> 4, be.getBlockPos().getZ() >> 4);
      if (chunk != null && chunk.getBlockEntity(be.getBlockPos()) != be) return;
    }
    DeviceState state = device.getDeviceState();
    var handle = getArchive().attach(be.getBlockPos(), ownerType(device), state.nodeId, device::createPowerNode);
    IDevice existing = devices.get(handle.id());
    if (existing != null && existing != device && !existing.asBlockEntity().isRemoved()) {
      throw new IllegalStateException("Two loaded devices claim power node " + handle.id());
    }
    state.nodeId = handle.id();
    state.node = handle.node();
    state.network = archive.getNetwork();
    devices.put(handle.id(), device);
    syncNeighborhood(handle.id());
  }

  public void detach(IDevice device) {
    long id = device.getNodeId();
    if (devices.get(id) != device) return;
    Set<Long> peers = neighbors(id);
    devices.remove(id);
    device.getDeviceState().node = null;
    device.getDeviceState().network = null;
    sync(peers);
  }

  public void remove(IDevice device) {
    long id = device.getNodeId();
    // A stale/duplicated BE must not delete the legitimate owner's graph.
    if (devices.get(id) != device) return;
    Set<Long> peers = neighbors(id);
    archive.removeNode(id);
    devices.remove(id);
    device.getDeviceState().node = null;
    device.getDeviceState().network = null;
    sync(peers);
  }

  public IDevice getDevice(long id) { return devices.get(id); }

  public void suspendChunk(long chunk) {
    List<IDevice> removed = devices.values().stream()
        .filter(device -> PowerNetworkArchive.chunkOf(device.asBlockEntity().getBlockPos()) == chunk).toList();
    Set<Long> peers = new HashSet<>();
    for (IDevice device : removed) peers.addAll(neighbors(device.getNodeId()));
    archive.suspendChunk(chunk);
    for (IDevice device : removed) {
      devices.remove(device.getNodeId());
      device.getDeviceState().node = null;
      device.getDeviceState().network = null;
    }
    sync(peers);
  }

  public void suspendAll() {
    archive.suspendAll();
    for (IDevice device : devices.values()) {
      device.getDeviceState().node = null;
      device.getDeviceState().network = null;
    }
    devices.clear();
  }

  public void connectionsChanged(long a, long b) {
    syncNeighborhood(a);
    syncNeighborhood(b);
  }

  private Set<Long> neighbors(long id) {
    Set<Long> ids = new HashSet<>();
    for (var cable : archive.getConnections(id)) {
      ids.add(cable.a().nodeId());
      ids.add(cable.b().nodeId());
    }
    return ids;
  }
  private void syncNeighborhood(long id) {
    Set<Long> ids = neighbors(id);
    ids.add(id);
    sync(ids);
  }
  private void sync(Collection<Long> ids) {
    for (long id : ids) {
      IDevice device = devices.get(id);
      if (device != null && !device.asBlockEntity().isRemoved()) device.syncDevice();
    }
  }
}
