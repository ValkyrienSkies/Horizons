package org.valkyrienskies.horizons.api.circuits;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

/**
 * An addon-defined component. Pin order is the node's port order; geometry is relative to the
 * placement origin before clockwise rotation. Shared pins connect electrically, while body cells
 * exclude other bodies, pins and wire endpoints. No client-only classes are required to register.
 */
public record CircuitComponentType(ResourceLocation id, Supplier<? extends Item> item,
    String translationKey, List<Pin> pins, List<Cell> bodies, Sprite sprite, Simulation simulation) {
  public CircuitComponentType {
    Objects.requireNonNull(id);
    Objects.requireNonNull(item);
    Objects.requireNonNull(translationKey);
    Objects.requireNonNull(sprite);
    Objects.requireNonNull(simulation);
    pins = List.copyOf(pins);
    bodies = List.copyOf(bodies);
    if (translationKey.isBlank() || pins.isEmpty()) throw new IllegalArgumentException("Missing component name or pins");
    var occupied = new HashSet<>(bodies);
    if (occupied.size() != bodies.size()) throw new IllegalArgumentException("Duplicate body cell");
    for (Pin pin : pins) {
      if (occupied.contains(pin.cell())) throw new IllegalArgumentException("Body overlaps pin");
    }
    if (pins.stream().map(Pin::cell).distinct().count() != pins.size())
      throw new IllegalArgumentException("Duplicate pin");
  }

  public record Cell(int x, int y) {}

  public record Pin(int x, int y, String translationKey) {
    public Pin {
      Objects.requireNonNull(translationKey);
      if (translationKey.isBlank()) throw new IllegalArgumentException("Missing pin name");
    }
    public Cell cell() { return new Cell(x, y); }
  }

  /** A complete PNG, rendered at one pixel per texture pixel and 16 pixels per grid cell. */
  public record Sprite(ResourceLocation texture, int width, int height, int originX, int originY) {
    public Sprite {
      Objects.requireNonNull(texture);
      if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid sprite size");
    }
  }

  /**
   * Called under the power-network state lock. Create a fresh, disconnected node on every call.
   * State is copied through edits and saved with the board. Stateful implementations must validate
   * their NBT and should include their own version field when evolving the format.
   */
  @FunctionalInterface
  public interface Simulation {
    IPowerNode createNode();
    default CompoundTag saveState(IPowerNode node) { return new CompoundTag(); }
    default void loadState(IPowerNode node, CompoundTag state) {
      if (!state.isEmpty()) throw new IllegalArgumentException("Unexpected component state");
    }
  }

  public IPowerNode createNode() {
    IPowerNode node = Objects.requireNonNull(simulation.createNode());
    if (node.getPorts() != pins.size()) throw new IllegalArgumentException("Pin/port count mismatch for " + id);
    return node;
  }
}
