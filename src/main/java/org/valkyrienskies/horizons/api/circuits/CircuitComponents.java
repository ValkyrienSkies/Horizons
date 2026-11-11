package org.valkyrienskies.horizons.api.circuits;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Common-side component registry, keyed by stable namespaced IDs on both client and server.
 * Register in your mod constructor or enqueued common setup, using your normal Forge item
 * registration as the lazy item supplier (a RegistryObject implements Supplier). For example:
 * <pre>{@code
 * var item = ITEMS.register("diode", () -> new Item(new Item.Properties()));
 * CircuitComponents.register(new CircuitComponentType(ResourceLocation.fromNamespaceAndPath("addon", "diode"),
 *     item, "item.addon.diode", pins, bodies, sprite, () -> new DiodeNode()));
 * }</pre>
 * Horizons freezes the registry during load-complete, after item registration. Each component
 * needs its own item; item NBT is not a component parameter and is not stored in circuit designs.
 */
public final class CircuitComponents {
  private static final Map<ResourceLocation, CircuitComponentType> TYPES = new LinkedHashMap<>();
  private static final Map<Item, CircuitComponentType> ITEMS = new IdentityHashMap<>();
  private static boolean frozen;

  private CircuitComponents() {}

  public static synchronized CircuitComponentType register(CircuitComponentType type) {
    Objects.requireNonNull(type);
    if (frozen) throw new IllegalStateException("Circuit components must be registered during mod initialization");
    if (TYPES.putIfAbsent(type.id(), type) != null) throw new IllegalArgumentException("Duplicate component " + type.id());
    return type;
  }

  public static synchronized CircuitComponentType get(ResourceLocation id) {
    CircuitComponentType type = TYPES.get(id);
    if (type == null) throw new IllegalArgumentException("Unknown circuit component " + id);
    return type;
  }

  public static synchronized List<CircuitComponentType> values() { return List.copyOf(TYPES.values()); }

  public static synchronized @Nullable CircuitComponentType find(ItemStack stack) {
    if (stack.isEmpty()) return null;
    if (frozen) return ITEMS.get(stack.getItem());
    return TYPES.values().stream().filter(type -> stack.is(type.item().get())).findFirst().orElse(null);
  }

  /** Called by Horizons after all mods have registered their items and components. */
  public static synchronized void freeze() {
    if (frozen) return;
    Map<Item, CircuitComponentType> resolved = new IdentityHashMap<>();
    for (CircuitComponentType type : TYPES.values()) {
      Item item = Objects.requireNonNull(type.item().get(), "Missing component item " + type.id());
      if (item == Items.AIR || resolved.putIfAbsent(item, type) != null)
        throw new IllegalArgumentException("Invalid or duplicate component item for " + type.id());
    }
    ITEMS.putAll(resolved);
    frozen = true;
  }
}
