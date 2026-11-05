package org.valkyrienskies.horizons.api.rigging;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.valkyrienskies.core.api.bodies.VsBody;
import javax.annotation.Nullable;

/** Game-thread interaction and saved data for a VS rigid body. VS owns its physical transform. */
public abstract class RigBody {
  private final Level level;
  private final long bodyId;
  private final RigBodyType<?> type;

  protected RigBody(Level level, long bodyId, RigBodyType<?> type) {
    this.level = level;
    this.bodyId = bodyId;
    this.type = type;
  }

  public final Level getLevel() { return level; }
  public final long getBodyId() { return bodyId; }
  public final RigBodyType<?> getType() { return type; }
  public final @Nullable VsBody getBody() { return RigBodies.getVsBody(level, bodyId); }
  public abstract CompoundTag saveData();
  public void tick() {}
  /** Called on the server before grab forces start. Return false to reject the grab. */
  public boolean onGrab(ServerPlayer player) { return true; }
  public void onRelease(ServerPlayer player) {}
  /** Called before the body and its custom data are permanently deleted. */
  public void onRemoved() {}
}
