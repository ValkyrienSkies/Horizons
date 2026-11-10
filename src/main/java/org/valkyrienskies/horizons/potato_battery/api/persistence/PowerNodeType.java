package org.valkyrienskies.horizons.potato_battery.api.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Versioned, addon-facing codec for a node's parameters and persistent electrical state. */
public record PowerNodeType<T extends IPowerNode>(ResourceLocation id, Class<T> nodeClass, int version,
    BiFunction<Integer, CompoundTag, T> reader, BiConsumer<T, CompoundTag> writer) {
  public PowerNodeType {
    Objects.requireNonNull(id);
    Objects.requireNonNull(nodeClass);
    Objects.requireNonNull(reader);
    Objects.requireNonNull(writer);
    if (version < 1) throw new IllegalArgumentException("Invalid node format version");
  }

  public CompoundTag encode(IPowerNode node) {
    CompoundTag tag = new CompoundTag();
    tag.putString("Type", id.toString());
    tag.putInt("Version", version);
    CompoundTag state = new CompoundTag();
    writer.accept(nodeClass.cast(node), state);
    tag.put("State", state);
    return tag;
  }

  public T decode(CompoundTag tag) {
    return Objects.requireNonNull(reader.apply(tag.getInt("Version"), tag.getCompound("State").copy()));
  }
}
