package org.valkyrienskies.horizons.impl.devices.persistence;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeTypes;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

import java.io.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Chunk-owned persistence, independent of BEs. Call on the game thread. Only active chunks have
 * decoded records/node instances; cold chunks retain compressed bytes and a primitive ID index.
 * Electrical snapshots and mutations run behind the solver's state barrier, without world access.
 */
public final class PowerNetworkArchive {
  public static final int FORMAT_VERSION = 1;
  private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
  private final PowerNetworkServer network;
  private final Runnable changed;
  private final Long2ObjectOpenHashMap<PackedChunk> packed = new Long2ObjectOpenHashMap<>();
  private final Long2ObjectOpenHashMap<ChunkData> active = new Long2ObjectOpenHashMap<>();
  private final Long2LongOpenHashMap nodeChunks = new Long2LongOpenHashMap();

  public PowerNetworkArchive(PowerNetworkServer network, Runnable changed) {
    this.network = network;
    this.changed = changed;
  }

  public PowerNetworkServer getNetwork() { return network; }
  public boolean hasActiveNodes() { return network.hasNodes(); }
  public int getArchivedChunkCount() { return packed.size(); }
  public boolean isChunkActive(long chunk) { return active.containsKey(chunk); }

  public void read(CompoundTag tag) {
    if (!packed.isEmpty() || !active.isEmpty()) throw new IllegalStateException("Archive is already initialized");
    if (tag.getInt("FormatVersion") != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported power archive format");
    long nextId = tag.getLong("NextNodeId");
    if (nextId < 0) throw new IllegalArgumentException("Invalid power node ID allocator");
    if (nextId > 0) network.reserveNodeIdsThrough(nextId - 1);
    for (Tag entry : tag.getList("Chunks", Tag.TAG_COMPOUND)) {
      CompoundTag chunk = (CompoundTag) entry;
      long position = chunk.getLong("ChunkPosition");
      long[] ids = chunk.getLongArray("NodeIds");
      byte[] payload = chunk.getByteArray("Payload");
      if (packed.containsKey(position) || payload.length == 0) throw new IllegalArgumentException("Invalid archived chunk " + position);
      for (long id : ids) {
        if (id < 0 || nodeChunks.containsKey(id)) throw new IllegalArgumentException("Duplicate/invalid archived node " + id);
        nodeChunks.put(id, position);
        network.reserveNodeIdsThrough(id);
      }
      packed.put(position, new PackedChunk(ids, payload));
    }
  }

  public CompoundTag write(CompoundTag tag) {
    return network.withState(() -> {
      for (var entry : active.long2ObjectEntrySet()) pack(entry.getLongKey(), entry.getValue());
      tag.putInt("FormatVersion", FORMAT_VERSION);
      tag.putLong("NextNodeId", network.getNextNodeId());
      ListTag chunks = new ListTag();
      for (var entry : packed.long2ObjectEntrySet()) {
        CompoundTag chunk = new CompoundTag();
        chunk.putLong("ChunkPosition", entry.getLongKey());
        chunk.putLongArray("NodeIds", entry.getValue().ids);
        chunk.putByteArray("Payload", entry.getValue().payload);
        chunks.add(chunk);
      }
      tag.put("Chunks", chunks);
      return tag;
    });
  }

  public void activateChunk(long chunk) {
    network.withState(() -> { activate(chunk); return null; });
  }

  private void activate(long chunk) {
    if (active.containsKey(chunk) || !packed.containsKey(chunk)) return;
    ChunkData data = unpack(chunk, packed.get(chunk));
    // Decode all nodes before mutating the live graph, so a malformed payload cannot half-load a chunk.
    Map<Long, IPowerNode> restored = new HashMap<>();
    for (NodeRecord record : data.nodes.values()) {
      IPowerNode node = PowerNodeTypes.decode(record.electrical);
      if (node != null) restored.put(record.id, node);
      else LOGGER.warn("Power node {} has unavailable type {}; keeping its saved record", record.id, record.electrical.getString("Type"));
    }
    active.put(chunk, data);
    restored.forEach(network::restoreNode);
    for (Cable cable : data.cables) bind(cable);
  }

  public void suspendChunk(long chunk) {
    network.withState(() -> {
      ChunkData data = active.get(chunk);
      if (data != null) {
        pack(chunk, data);
        network.suspendNodes(data.nodes.keySet());
        active.remove(chunk);
        changed.run();
      }
      return null;
    });
  }

  public void suspendAll() {
    network.withState(() -> {
      Set<Long> ids = new HashSet<>();
      for (var entry : active.long2ObjectEntrySet()) {
        pack(entry.getLongKey(), entry.getValue());
        ids.addAll(entry.getValue().nodes.keySet());
      }
      network.suspendNodes(ids);
      active.clear();
      if (!ids.isEmpty()) changed.run();
      return null;
    });
  }

  public NodeHandle attach(BlockPos pos, String ownerType, long savedId, Supplier<IPowerNode> factory) {
    long chunk = chunkOf(pos);
    activateChunk(chunk);
    ChunkData current = active.get(chunk);
    boolean needsNode = current == null || current.nodes.values().stream()
        .noneMatch(node -> node.pos.equals(pos) && node.ownerType.equals(ownerType));
    // Factories may read BE configuration. Run them on the calling game thread outside the solver barrier.
    IPowerNode candidate = needsNode ? factory.get() : null;
    CompoundTag candidateState = candidate == null ? null : PowerNodeTypes.encode(candidate);
    return network.withState(() -> {
      ChunkData data = active.computeIfAbsent(chunk, ignored -> new ChunkData());
      NodeRecord existing = data.nodes.values().stream().filter(node -> node.pos.equals(pos)).findFirst().orElse(null);
      if (existing != null && existing.ownerType.equals(ownerType)) {
        IPowerNode node = network.getNode(existing.id);
        if (node == null) throw new IllegalStateException("Cannot attach device: power node type is unavailable for " + existing.id);
        if (savedId >= 0 && existing.id != savedId) LOGGER.warn("Correcting stale BE power node ID {} to {} at {}", savedId, existing.id, pos);
        return new NodeHandle(existing.id, node);
      }
      if (existing != null) {
        remove(existing.id);
        network.withState(() -> null);
      }
      long requestedId = existing == null ? savedId : -1;
      long id;
      if (requestedId >= 0 && !nodeChunks.containsKey(requestedId) && network.getNode(requestedId) == null) {
        id = requestedId;
        network.reserveNodeIdsThrough(id);
        LOGGER.warn("Reconstructing missing power node {} at {} from its device", id, pos);
      } else {
        id = network.allocateNodeId();
        if (requestedId >= 0) LOGGER.warn("Power node {} belongs to another device; allocating {} at {}", requestedId, id, pos);
      }
      IPowerNode node = Objects.requireNonNull(candidate);
      data.nodes.put(id, new NodeRecord(id, pos.immutable(), ownerType, candidateState));
      data.dirty = true;
      nodeChunks.put(id, chunk);
      network.restoreNode(id, node);
      changed.run();
      return new NodeHandle(id, node);
    });
  }

  public List<Owner> getOwners(long chunk) {
    ChunkData data = active.get(chunk);
    if (data == null) return List.of();
    return data.nodes.values().stream().map(node -> new Owner(node.id, node.pos, node.ownerType)).toList();
  }

  public List<Cable> getConnections(long id) {
    if (!nodeChunks.containsKey(id)) return List.of();
    ChunkData data = active.get(nodeChunks.get(id));
    return data == null ? List.of() : data.cables.stream().filter(cable -> cable.contains(id)).toList();
  }

  public boolean connect(long idA, int portA, long idB, int portB, double resistance) {
    return network.withState(() -> {
      IPowerNode a = network.getNode(idA), b = network.getNode(idB);
      validatePort(a, portA);
      validatePort(b, portB);
      if (idA == idB && portA == portB) throw new IllegalArgumentException("Cannot connect a port to itself");
      if (!a.canConnect(b, portA, portB) || !b.canConnect(a, portB, portA)) throw new IllegalArgumentException("Nodes reject connection");
      NodeRecord ownerA = active.get(nodeChunks.get(idA)).nodes.get(idA);
      NodeRecord ownerB = active.get(nodeChunks.get(idB)).nodes.get(idB);
      Cable cable = Cable.create(new Endpoint(idA, ownerA.pos, portA), new Endpoint(idB, ownerB.pos, portB), resistance);
      if (getConnections(idA).stream().anyMatch(old -> old.samePorts(cable))) return false;
      edit(nodeChunks.get(idA), data -> data.cables.add(cable));
      if (nodeChunks.get(idA) != nodeChunks.get(idB)) edit(nodeChunks.get(idB), data -> data.cables.add(cable));
      bind(cable);
      changed.run();
      return true;
    });
  }

  public boolean disconnect(long idA, int portA, long idB, int portB) {
    return network.withState(() -> {
      Cable cable = getConnections(idA).stream().filter(edge -> edge.matches(idA, portA, idB, portB)).findFirst().orElse(null);
      if (cable == null) return false;
      removeCable(cable);
      changed.run();
      return true;
    });
  }

  public void removeNode(long id) {
    network.withState(() -> { remove(id); return null; });
  }

  private void remove(long id) {
    if (!nodeChunks.containsKey(id)) return;
    long chunk = nodeChunks.get(id);
    ChunkData data = active.containsKey(chunk) ? active.get(chunk) : unpack(chunk, packed.get(chunk));
    for (Cable cable : List.copyOf(data.cables)) if (cable.contains(id)) removeCable(cable);
    edit(chunk, records -> records.nodes.remove(id));
    nodeChunks.remove(id);
    network.removeNode(id);
    changed.run();
  }

  private void removeCable(Cable cable) {
    if (nodeChunks.containsKey(cable.a.nodeId)) edit(nodeChunks.get(cable.a.nodeId), data -> data.cables.remove(cable));
    if (nodeChunks.containsKey(cable.b.nodeId) && (!nodeChunks.containsKey(cable.a.nodeId)
        || nodeChunks.get(cable.a.nodeId) != nodeChunks.get(cable.b.nodeId))) {
      edit(nodeChunks.get(cable.b.nodeId), data -> data.cables.remove(cable));
    }
    IPowerNode a = network.getNode(cable.a.nodeId), b = network.getNode(cable.b.nodeId);
    if (a != null && b != null) network.updateTopology(() -> {
      a.removeConnection(b, cable.a.port, cable.b.port);
      b.removeConnection(a, cable.b.port, cable.a.port);
    });
  }

  private void bind(Cable cable) {
    IPowerNode a = network.getNode(cable.a.nodeId), b = network.getNode(cable.b.nodeId);
    if (a == null || b == null) return; // An unloaded or unavailable endpoint is an open circuit.
    validatePort(a, cable.a.port);
    validatePort(b, cable.b.port);
    network.updateTopology(() -> {
      a.addConnection(b, cable.a.port, cable.b.port, cable.resistance);
      b.addConnection(a, cable.b.port, cable.a.port, cable.resistance);
    });
  }

  private static void validatePort(IPowerNode node, int port) {
    if (node == null || port < 0 || port >= node.getPorts()) throw new IllegalArgumentException("Inactive node or invalid port " + port);
  }

  private void edit(long chunk, java.util.function.Consumer<ChunkData> edit) {
    ChunkData data = active.get(chunk);
    boolean cold = data == null;
    if (cold) data = unpack(chunk, packed.get(chunk));
    edit.accept(data);
    data.dirty = true;
    if (cold) pack(chunk, data); // Edit archived connections without instantiating nodes or loading world chunks.
  }

  private void pack(long chunk, ChunkData data) {
    for (NodeRecord record : data.nodes.values()) {
      IPowerNode node = network.getNode(record.id);
      if (node != null) {
        CompoundTag state = PowerNodeTypes.encode(node);
        if (!state.equals(record.electrical)) { record.electrical = state; data.dirty = true; }
      }
    }
    if (!data.dirty && packed.containsKey(chunk)) return;
    if (data.nodes.isEmpty()) { packed.remove(chunk); data.dirty = false; return; }
    CompoundTag tag = new CompoundTag();
    ListTag nodes = new ListTag(), cables = new ListTag();
    for (NodeRecord record : data.nodes.values()) {
      CompoundTag node = new CompoundTag();
      node.putLong("Id", record.id);
      node.putLong("Position", record.pos.asLong());
      node.putString("OwnerType", record.ownerType);
      node.put("Electrical", record.electrical.copy());
      nodes.add(node);
    }
    for (Cable cable : data.cables) cables.add(cable.save());
    tag.put("Nodes", nodes);
    tag.put("Cables", cables);
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      NbtIo.writeCompressed(tag, bytes);
      packed.put(chunk, new PackedChunk(data.nodes.keySet().stream().mapToLong(Long::longValue).toArray(), bytes.toByteArray()));
      data.dirty = false;
    } catch (IOException e) { throw new IllegalStateException("Could not compress power chunk " + chunk, e); }
  }

  private ChunkData unpack(long chunk, PackedChunk packedChunk) {
    if (packedChunk == null) throw new IllegalStateException("Missing power chunk " + chunk);
    try {
      CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(packedChunk.payload));
      ChunkData data = new ChunkData();
      for (Tag value : tag.getList("Nodes", Tag.TAG_COMPOUND)) {
        CompoundTag node = (CompoundTag) value;
        long id = node.getLong("Id");
        BlockPos pos = BlockPos.of(node.getLong("Position"));
        if (chunkOf(pos) != chunk || !nodeChunks.containsKey(id) || nodeChunks.get(id) != chunk
            || data.nodes.put(id, new NodeRecord(id, pos, node.getString("OwnerType"), node.getCompound("Electrical"))) != null) {
          throw new IllegalArgumentException("Invalid power node ownership " + id);
        }
      }
      if (data.nodes.size() != packedChunk.ids.length) throw new IllegalArgumentException("Power node index does not match payload");
      for (Tag value : tag.getList("Cables", Tag.TAG_COMPOUND)) {
        Cable cable = Cable.load((CompoundTag) value);
        if (!data.nodes.containsKey(cable.a.nodeId) && !data.nodes.containsKey(cable.b.nodeId)) throw new IllegalArgumentException("Unowned cable");
        data.cables.add(cable);
      }
      return data;
    } catch (IOException e) { throw new IllegalStateException("Could not decompress power chunk " + chunk, e); }
  }

  public static long chunkOf(BlockPos pos) { return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4); }
  public record NodeHandle(long id, IPowerNode node) {}
  public record Owner(long id, BlockPos pos, String type) {}
  public record Endpoint(long nodeId, BlockPos pos, int port) {}
  public record Cable(Endpoint a, Endpoint b, double resistance) {
    public Cable {
      if (a.nodeId < 0 || b.nodeId < 0 || a.port < 0 || b.port < 0 || !Double.isFinite(resistance) || resistance <= 0)
        throw new IllegalArgumentException("Invalid cable");
    }
    static Cable create(Endpoint a, Endpoint b, double resistance) {
      return a.nodeId < b.nodeId || (a.nodeId == b.nodeId && a.port < b.port)
          ? new Cable(a, b, resistance) : new Cable(b, a, resistance);
    }
    public boolean contains(long id) { return a.nodeId == id || b.nodeId == id; }
    public Endpoint other(long id, int port) {
      if (a.nodeId == id && a.port == port) return b;
      return b.nodeId == id && b.port == port ? a : null;
    }
    boolean samePorts(Cable other) {
      return a.nodeId == other.a.nodeId && a.port == other.a.port && b.nodeId == other.b.nodeId && b.port == other.b.port;
    }
    boolean matches(long idA, int portA, long idB, int portB) {
      return (a.nodeId == idA && a.port == portA && b.nodeId == idB && b.port == portB)
          || (a.nodeId == idB && a.port == portB && b.nodeId == idA && b.port == portA);
    }
    CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putLong("A", a.nodeId); tag.putLong("PosA", a.pos.asLong()); tag.putInt("PortA", a.port);
      tag.putLong("B", b.nodeId); tag.putLong("PosB", b.pos.asLong()); tag.putInt("PortB", b.port);
      tag.putDouble("Resistance", resistance);
      return tag;
    }
    static Cable load(CompoundTag tag) {
      return create(new Endpoint(tag.getLong("A"), BlockPos.of(tag.getLong("PosA")), tag.getInt("PortA")),
          new Endpoint(tag.getLong("B"), BlockPos.of(tag.getLong("PosB")), tag.getInt("PortB")), tag.getDouble("Resistance"));
    }
  }
  private record PackedChunk(long[] ids, byte[] payload) {}
  private static final class ChunkData {
    final Map<Long, NodeRecord> nodes = new LinkedHashMap<>();
    final Set<Cable> cables = new HashSet<>();
    boolean dirty;
  }
  private static final class NodeRecord {
    final long id;
    final BlockPos pos;
    final String ownerType;
    CompoundTag electrical;
    NodeRecord(long id, BlockPos pos, String ownerType, CompoundTag electrical) {
      this.id = id; this.pos = pos; this.ownerType = ownerType; this.electrical = electrical;
    }
  }
}
