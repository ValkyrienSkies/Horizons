package org.valkyrienskies.horizons.api.devices;

import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.TickTask;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.valkyrienskies.horizons.impl.devices.persistence.PowerNetworkSavedData;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Each server dimension has one electrical network. */
public final class PowerNetworks {
  private static final ConcurrentHashMap<ServerLevel, PowerNetworkSavedData> NETWORKS = new ConcurrentHashMap<>();
  private PowerNetworks() {}

  public static PowerNetworkServer get(Level level) {
    return getData(level).getArchive().getNetwork();
  }

  public static PowerNetworkSavedData getData(Level level) {
    if (!(level instanceof ServerLevel server)) throw new IllegalArgumentException("Electrical networks are server-owned");
    return NETWORKS.computeIfAbsent(server, ignored -> server.getDataStorage().computeIfAbsent(
        PowerNetworkSavedData::load, PowerNetworkSavedData::new, PowerNetworkSavedData.FILE_NAME));
  }

  public static Collection<PowerNetworkServer> getNetworks() {
    return NETWORKS.values().stream().map(data -> data.getArchive().getNetwork()).toList();
  }
  public static void clear() { NETWORKS.clear(); }

  /** Explicit registration also works with Kotlin for Forge, which does not auto-subscribe Java classes. */
  public static void registerEvents(IEventBus bus) {
    bus.addListener(PowerNetworks::chunkLoaded);
    bus.addListener(PowerNetworks::chunkUnloaded);
    bus.addListener(PowerNetworks::levelUnloaded);
  }

  private static void chunkLoaded(ChunkEvent.Load event) {
    if (!(event.getLevel() instanceof ServerLevel level)) return;
    var chunk = event.getChunk();
    // ChunkEvent.Load can precede FULL promotion. Never fetch neighboring chunks here.
    level.getServer().tell(new TickTask(level.getServer().getTickCount(), () -> {
      if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != chunk) return;
      var data = NETWORKS.get(level);
      if (data == null) return;
      data.getArchive().activateChunk(chunk.getPos().toLong());
      for (var owner : data.getArchive().getOwners(chunk.getPos().toLong())) {
        // A missing addon must not erase its opaque archived records.
        var ownerType = ResourceLocation.tryParse(owner.type());
        if (ownerType == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(ownerType)) continue;
        var be = chunk.getBlockEntity(owner.pos());
        if (!(be instanceof IDevice device) || !PowerNetworkSavedData.ownerType(device).equals(owner.type())) {
          data.getArchive().removeNode(owner.id());
        }
      }
    }));
  }

  private static void chunkUnloaded(ChunkEvent.Unload event) {
    if (!(event.getLevel() instanceof ServerLevel level)) return;
    var current = level.getChunkSource().getChunkNow(event.getChunk().getPos().x, event.getChunk().getPos().z);
    if (current != null && current != event.getChunk()) return;
    PowerNetworkSavedData data = NETWORKS.get(level);
    if (data != null) data.suspendChunk(event.getChunk().getPos().toLong());
  }

  private static void levelUnloaded(LevelEvent.Unload event) {
    if (!(event.getLevel() instanceof ServerLevel level)) return;
    PowerNetworkSavedData data = NETWORKS.get(level);
    if (data != null) {
      data.suspendAll();
      level.getDataStorage().save();
      NETWORKS.remove(level);
    }
  }
}
