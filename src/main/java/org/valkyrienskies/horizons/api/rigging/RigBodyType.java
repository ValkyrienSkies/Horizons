package org.valkyrienskies.horizons.api.rigging;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Addon registration for a model, centered cube collider, mass, and per-body data factory. */
public final class RigBodyType<T extends RigBody> {
  @FunctionalInterface public interface Factory<T> { T create(Level level, long bodyId, CompoundTag data); }
  private final ResourceLocation id, model;
  private final double cubeSize, mass;
  private final Vector3d modelCenter;
  private final Factory<T> factory;

  public RigBodyType(ResourceLocation id, ResourceLocation model, double cubeSize, double mass,
      Vector3dc modelCenter, Factory<T> factory) {
    if (!Double.isFinite(cubeSize) || cubeSize <= 0 || !Double.isFinite(mass) || mass <= 0)
      throw new IllegalArgumentException("Invalid rigid body dimensions or mass");
    this.id = id; this.model = model; this.cubeSize = cubeSize; this.mass = mass;
    this.modelCenter = new Vector3d(modelCenter); this.factory = factory;
  }
  public ResourceLocation getId() { return id; }
  public ResourceLocation getModel() { return model; }
  public double getCubeSize() { return cubeSize; }
  public double getMass() { return mass; }
  public Vector3dc getModelCenter() { return new Vector3d(modelCenter); }
  public T create(Level level, long id, CompoundTag data) { return factory.create(level, id, data); }
}
