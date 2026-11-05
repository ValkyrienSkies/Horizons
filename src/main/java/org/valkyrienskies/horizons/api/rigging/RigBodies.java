package org.valkyrienskies.horizons.api.rigging;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.bodies.VsBody;
import org.valkyrienskies.horizons.impl.rigging.RigBodyManager;
import javax.annotation.Nullable;
import java.util.*;

/** Register types during mod initialization. All world operations are game-thread only. */
public final class RigBodies {
  private static final Map<ResourceLocation, RigBodyType<?>> TYPES = new LinkedHashMap<>();
  private RigBodies() {}
  public static synchronized <T extends RigBody> RigBodyType<T> register(RigBodyType<T> type) {
    if (TYPES.putIfAbsent(type.getId(), type) != null) throw new IllegalArgumentException("Duplicate rig type " + type.getId());
    return type;
  }
  public static @Nullable RigBodyType<?> getType(ResourceLocation id) { return TYPES.get(id); }
  public static Collection<RigBodyType<?>> getTypes() { return List.copyOf(TYPES.values()); }
  public static RigBody spawn(ServerLevel level, RigBodyType<?> type, Vector3dc position, Quaterniondc rotation, CompoundTag data) {
    return RigBodyManager.spawn(level, type, position, rotation, data);
  }
  public static @Nullable RigBody get(Level level, long id) { return RigBodyManager.get(level, id); }
  public static Collection<RigBody> getAll(Level level) { return RigBodyManager.getAll(level); }
  public static @Nullable VsBody getVsBody(Level level, long id) { return RigBodyManager.getVsBody(level, id); }
  public static boolean isGrabbed(Level level, long id) { return RigBodyManager.isGrabbed(level, id); }
  public static void remove(ServerLevel level, long id) { RigBodyManager.remove(level, id); }
}
