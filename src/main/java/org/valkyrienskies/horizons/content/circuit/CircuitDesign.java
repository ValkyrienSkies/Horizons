package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CircuitDesign(List<Placed> components, List<Wire> wires) {
  public static final int SIZE = 11;
  public static final int MAX_COMPONENTS = 121;
  public static final int MAX_WIRES = 512;
  public record Point(int x, int y) {}
  public record Placed(String type, int x, int y, int rotation) {}
  public record Wire(Point from, Point to, int color) {}

  public CircuitDesign {
    if (components == null || wires == null || components.size() > MAX_COMPONENTS || wires.size() > MAX_WIRES)
      throw new IllegalArgumentException("Invalid design size");
    Set<Point> occupiedBodies = new HashSet<>();
    Set<Point> occupiedPins = new HashSet<>();
    for (Placed component : components) {
      validateComponent(component);
      for (Point body : bodies(component)) {
        if (!inGrid(body) || !occupiedBodies.add(body)) throw new IllegalArgumentException("Overlapping/outside body");
      }
      for (Point pin : pins(component)) {
        if (!inGrid(pin)) throw new IllegalArgumentException("Pin outside grid");
        occupiedPins.add(pin);
      }
    }
    if (occupiedBodies.stream().anyMatch(occupiedPins::contains)) throw new IllegalArgumentException("Body overlaps pin");
    Set<Set<Point>> edges = new HashSet<>();
    for (Wire wire : wires) {
      if (wire == null || !endpoint(wire.from) || !endpoint(wire.to) || wire.from.equals(wire.to)
          || wire.color < 0 || wire.color > 7 || occupiedBodies.contains(wire.from) || occupiedBodies.contains(wire.to))
        throw new IllegalArgumentException("Invalid wire");
      if (!edges.add(Set.of(wire.from, wire.to))) throw new IllegalArgumentException("Duplicate wire");
    }
    components = List.copyOf(components);
    wires = List.copyOf(wires);
  }

  private static void validateComponent(Placed placed) {
    if (placed == null || placed.type == null || placed.rotation < 0 || placed.rotation > 3
        || !inGrid(new Point(placed.x, placed.y))
        || !Set.of("resistor", "capacitor", "nmos", "and_gate", "or_gate").contains(placed.type))
      throw new IllegalArgumentException("Invalid component");
  }

  public static List<Point> pins(Placed placed) {
    validateComponent(placed);
    return switch (placed.type) {
      case "resistor", "capacitor" -> List.of(transform(placed, 0, 0), transform(placed, 1, 0));
      case "nmos" -> List.of(transform(placed, -1, 0), transform(placed, 0, -1), transform(placed, 0, 1));
      default -> List.of(transform(placed, -1, -1), transform(placed, -1, 1), transform(placed, 1, 0));
    };
  }

  public static List<Point> bodies(Placed placed) {
    validateComponent(placed);
    return placed.type.equals("resistor") || placed.type.equals("capacitor") ? List.of() : List.of(new Point(placed.x, placed.y));
  }

  private static Point transform(Placed placed, int dx, int dy) {
    return switch (placed.rotation) {
      case 0 -> new Point(placed.x + dx, placed.y + dy);
      case 1 -> new Point(placed.x - dy, placed.y + dx);
      case 2 -> new Point(placed.x - dx, placed.y - dy);
      default -> new Point(placed.x + dy, placed.y - dx);
    };
  }

  public static Point socket(int index) {
    if (index < 0 || index >= 8) throw new IllegalArgumentException("Invalid socket");
    return new Point(new int[] {1, 4, 6, 9}[index % 4], index < 4 ? -1 : 11);
  }

  private static boolean inGrid(Point point) { return point != null && point.x >= 0 && point.x < SIZE && point.y >= 0 && point.y < SIZE; }
  private static boolean endpoint(Point point) {
    if (inGrid(point)) return true;
    for (int index = 0; index < 8; index++) if (socket(index).equals(point)) return true;
    return false;
  }

  public static CircuitDesign empty() { return new CircuitDesign(List.of(), List.of()); }
  public CircuitDesign withComponent(Placed placed) {
    var updated = new ArrayList<>(components); updated.add(placed); return new CircuitDesign(updated, wires);
  }
  public CircuitDesign withoutComponent(int index) {
    if (index < 0 || index >= components.size()) throw new IllegalArgumentException("Invalid component index");
    var updated = new ArrayList<>(components); updated.remove(index); return new CircuitDesign(updated, wires);
  }
  public CircuitDesign withWire(Wire wire) {
    var updated = new ArrayList<>(wires); updated.add(wire); return new CircuitDesign(components, updated);
  }
  public CircuitDesign withoutWire(int index) {
    if (index < 0 || index >= wires.size()) throw new IllegalArgumentException("Invalid wire index");
    var updated = new ArrayList<>(wires); updated.remove(index); return new CircuitDesign(components, updated);
  }

  public CompoundTag write() {
    CompoundTag tag = new CompoundTag();
    ListTag parts = new ListTag();
    for (Placed placed : components) {
      CompoundTag part = new CompoundTag();
      part.putString("Type", placed.type); part.putInt("X", placed.x); part.putInt("Y", placed.y); part.putInt("Rotation", placed.rotation);
      parts.add(part);
    }
    ListTag edges = new ListTag();
    for (Wire wire : wires) {
      CompoundTag edge = new CompoundTag();
      edge.putInt("FromX", wire.from.x); edge.putInt("FromY", wire.from.y);
      edge.putInt("ToX", wire.to.x); edge.putInt("ToY", wire.to.y); edge.putInt("Color", wire.color);
      edges.add(edge);
    }
    tag.put("Components", parts); tag.put("Wires", edges);
    return tag;
  }

  public static CircuitDesign read(CompoundTag tag) {
    if (tag == null) throw new IllegalArgumentException("Missing design");
    ListTag parts = list(tag, "Components", MAX_COMPONENTS);
    ListTag edges = list(tag, "Wires", MAX_WIRES);
    List<Placed> components = new ArrayList<>();
    List<Wire> wires = new ArrayList<>();
    for (Tag value : parts) {
      CompoundTag part = (CompoundTag) value;
      if (!part.contains("Type", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing component type");
      components.add(new Placed(part.getString("Type"), integer(part, "X"), integer(part, "Y"), integer(part, "Rotation")));
    }
    for (Tag value : edges) {
      CompoundTag edge = (CompoundTag) value;
      wires.add(new Wire(new Point(integer(edge, "FromX"), integer(edge, "FromY")),
          new Point(integer(edge, "ToX"), integer(edge, "ToY")), integer(edge, "Color")));
    }
    return new CircuitDesign(components, wires);
  }

  private static ListTag list(CompoundTag tag, String key, int limit) {
    if (!(tag.get(key) instanceof ListTag list) || list.size() > limit
        || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid " + key);
    return list;
  }
  private static int integer(CompoundTag tag, String key) {
    if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Missing integer " + key);
    return tag.getInt(key);
  }
}
