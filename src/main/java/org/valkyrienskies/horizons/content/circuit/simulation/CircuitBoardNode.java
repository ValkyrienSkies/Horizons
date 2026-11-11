package org.valkyrienskies.horizons.content.circuit.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;

public final class CircuitBoardNode extends PowerNode implements CompositePowerNode {
  private volatile CircuitDesign design;
  private List<IPowerNode> components = List.of();
  private List<IPowerNode> children = List.of();
  private final Map<Point, IPowerNode> junctions = new LinkedHashMap<>();
  private volatile PowerNetworkServer network;

  public CircuitBoardNode(CircuitDesign design) { super(8); install(design); }
  public CircuitDesign getDesign() { return design; }
  @Override public int[] getReferenceTraversalPorts(int enteredPort) { return new int[] {enteredPort}; }
  @Override public List<IPowerNode> getChildren() { return children; }

  public void setDesign(CircuitDesign replacement) {
    if (replacement == null) throw new IllegalArgumentException("Missing design");
    PowerNetworkServer attached = network;
    if (attached == null) install(replacement);
    else attached.replaceCompositeChildren(this, () -> install(replacement));
  }

  private void install(CircuitDesign replacement) {
    if (replacement == null) throw new IllegalArgumentException("Missing design");
    Map<Placed, ArrayDeque<Double>> retained = new HashMap<>();
    if (design != null) {
      for (int index = 0; index < components.size(); index++) {
        if (components.get(index) instanceof CircuitComponentNodes.Capacitor capacitor)
          retained.computeIfAbsent(design.components().get(index), ignored -> new ArrayDeque<>()).add(capacitor.voltage());
      }
    }
    List<IPowerNode> built = new ArrayList<>();
    for (Placed placed : replacement.components()) {
      IPowerNode component = switch (placed.type()) {
        case "resistor" -> new ResistorNode(1000);
        case "capacitor" -> new CircuitComponentNodes.Capacitor();
        case "nmos" -> new CircuitComponentNodes.Nmos();
        case "and_gate" -> new CircuitComponentNodes.Gate(true);
        case "or_gate" -> new CircuitComponentNodes.Gate(false);
        default -> throw new IllegalArgumentException("Unknown component");
      };
      ArrayDeque<Double> state = retained.get(placed);
      if (component instanceof CircuitComponentNodes.Capacitor capacitor && state != null && !state.isEmpty()) capacitor.restore(state.remove());
      built.add(component);
    }
    components = List.copyOf(built);
    design = replacement;
    junctions.clear();
    for (int index = 0; index < 8; index++) junction(CircuitDesign.socket(index));
    for (Placed placed : design.components()) for (Point pin : CircuitDesign.pins(placed)) junction(pin);
    for (var wire : design.wires()) { junction(wire.from()); junction(wire.to()); }
    built.addAll(junctions.values());
    children = List.copyOf(built);
  }

  private IPowerNode junction(Point point) { return junctions.computeIfAbsent(point, ignored -> new PowerNode(1) {}); }

  @Override public void onNetworkAttached(PowerNetworkServer network) {
    this.network = network;
    for (int index = 0; index < 8; index++) connect(this, index, junctions.get(CircuitDesign.socket(index)), 0);
    for (int index = 0; index < components.size(); index++) {
      List<Point> pins = CircuitDesign.pins(design.components().get(index));
      for (int port = 0; port < pins.size(); port++) connect(components.get(index), port, junctions.get(pins.get(port)), 0);
    }
    for (var wire : design.wires()) connect(junctions.get(wire.from()), 0, junctions.get(wire.to()), 0);
  }

  @Override public void onNetworkDetached() { network = null; }

  private static void connect(IPowerNode first, int firstPort, IPowerNode second, int secondPort) {
    first.addConnection(second, firstPort, secondPort);
    second.addConnection(first, secondPort, firstPort);
  }

  public CompoundTag writeState() {
    CompoundTag tag = new CompoundTag();
    tag.put("Design", design.write());
    ListTag states = new ListTag();
    for (IPowerNode component : components)
      states.add(DoubleTag.valueOf(component instanceof CircuitComponentNodes.Capacitor capacitor ? capacitor.voltage() : 0));
    tag.put("ComponentStates", states);
    return tag;
  }

  public static CircuitBoardNode readState(CompoundTag tag) {
    if (!tag.contains("Design", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing circuit design");
    CircuitBoardNode board = new CircuitBoardNode(CircuitDesign.read(tag.getCompound("Design")));
    if (!(tag.get("ComponentStates") instanceof ListTag states) || states.size() != board.components.size()
        || (!states.isEmpty() && states.getElementType() != Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Invalid component states");
    for (int index = 0; index < states.size(); index++) {
      double value = states.getDouble(index);
      if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid component state");
      if (board.components.get(index) instanceof CircuitComponentNodes.Capacitor capacitor) capacitor.restore(value);
      else if (value != 0) throw new IllegalArgumentException("Unexpected component state");
    }
    return board;
  }
}
