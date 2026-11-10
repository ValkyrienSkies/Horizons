package org.valkyrienskies.horizons.content.circuit;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire;
import static org.junit.jupiter.api.Assertions.*;

class CircuitDesignTest {
  @Test void immutableAndRoundtrips() {
    var parts = new ArrayList<Placed>();
    parts.add(new Placed("resistor", 2, 2, 0));
    CircuitDesign design = new CircuitDesign(parts, List.of(new Wire(new Point(2, 2), CircuitDesign.socket(0), 7)));
    parts.clear();
    assertEquals(1, design.components().size());
    assertThrows(UnsupportedOperationException.class, () -> design.components().clear());
    assertThrows(UnsupportedOperationException.class, () -> design.wires().clear());
    assertEquals(design, CircuitDesign.read(design.write()));
    assertEquals(CircuitDesign.empty(), CircuitDesign.read(CircuitDesign.empty().write()));
    assertEquals(1, design.withoutWire(0).components().size());
    assertEquals(1, design.withoutComponent(0).wires().size());
  }

  @Test void geometryAndClockwiseRotation() {
    assertEquals(List.of(new Point(4, 5), new Point(5, 4), new Point(5, 6)), CircuitDesign.pins(new Placed("nmos", 5, 5, 0)));
    assertEquals(List.of(new Point(5, 4), new Point(6, 5), new Point(4, 5)), CircuitDesign.pins(new Placed("nmos", 5, 5, 1)));
    assertEquals(List.of(new Point(6, 5), new Point(5, 6), new Point(5, 4)), CircuitDesign.pins(new Placed("nmos", 5, 5, 2)));
    assertEquals(List.of(new Point(5, 6), new Point(4, 5), new Point(6, 5)), CircuitDesign.pins(new Placed("nmos", 5, 5, 3)));
    assertEquals(List.of(new Point(5, 5), new Point(5, 6)), CircuitDesign.pins(new Placed("capacitor", 5, 5, 1)));
    assertTrue(CircuitDesign.bodies(new Placed("resistor", 5, 5, 0)).isEmpty());
    assertEquals(List.of(new Point(5, 5)), CircuitDesign.bodies(new Placed("or_gate", 5, 5, 0)));
    assertEquals(List.of(new Point(4, 4), new Point(4, 6), new Point(6, 5)), CircuitDesign.pins(new Placed("and_gate", 5, 5, 0)));
    assertEquals(new Point(9, 11), CircuitDesign.socket(7));
    assertThrows(IllegalArgumentException.class, () -> CircuitDesign.socket(8));
  }

  @Test void rejectsInvalidComponentsAndOverlapsButAllowsSharedPins() {
    for (Placed part : List.of(new Placed("unknown", 1, 1, 0), new Placed("resistor", 10, 1, 0),
        new Placed("nmos", 0, 1, 0), new Placed("capacitor", -1, 0, 0), new Placed("and_gate", 1, 1, 4)))
      assertThrows(IllegalArgumentException.class, () -> CircuitDesign.empty().withComponent(part));
    CircuitDesign design = CircuitDesign.empty().withComponent(new Placed("nmos", 5, 5, 0));
    assertThrows(IllegalArgumentException.class, () -> design.withComponent(new Placed("or_gate", 5, 5, 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withComponent(new Placed("resistor", 5, 5, 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withComponent(new Placed("nmos", 4, 5, 0)));
    assertDoesNotThrow(() -> design.withComponent(new Placed("resistor", 3, 5, 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(new Point(5, 5), new Point(0, 0), 0)));
    assertThrows(IllegalArgumentException.class, () -> new CircuitDesign(java.util.Collections.nCopies(122, new Placed("resistor", 0, 0, 0)), List.of()));
    assertEquals(121, new CircuitDesign(java.util.Collections.nCopies(121, new Placed("resistor", 0, 0, 0)), List.of()).components().size());
  }

  @Test void rejectsInvalidWiresButAllowsCrossingsAndEverySocket() {
    Point first = new Point(0, 0);
    Point second = new Point(10, 10);
    CircuitDesign design = CircuitDesign.empty().withWire(new Wire(first, second, 0));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(second, first, 7)));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(first, first, 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(first, new Point(0, -1), 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(first, second, 8)));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(null, second, 0)));
    assertDoesNotThrow(() -> design.withWire(new Wire(new Point(0, 10), new Point(10, 0), 1)));
    for (int index = 0; index < 8; index++) assertDoesNotThrow(CircuitDesign.empty().withWire(new Wire(first, CircuitDesign.socket(index), 0))::write);
    List<Wire> wires = new ArrayList<>();
    for (int start = 0; start < 121 && wires.size() < 513; start++)
      for (int end = start + 1; end < 121 && wires.size() < 513; end++)
        wires.add(new Wire(new Point(start % 11, start / 11), new Point(end % 11, end / 11), 0));
    assertEquals(512, new CircuitDesign(List.of(), wires.subList(0, 512)).wires().size());
    assertThrows(IllegalArgumentException.class, () -> new CircuitDesign(List.of(), wires));
  }

  @Test void strictNbtDoesNotSilentlyDefaultMalformedFields() {
    assertThrows(IllegalArgumentException.class, () -> CircuitDesign.read(new CompoundTag()));
    CompoundTag tag = CircuitDesign.empty().withComponent(new Placed("resistor", 1, 1, 0)).write();
    tag.getList("Components", 10).getCompound(0).remove("Rotation");
    assertThrows(IllegalArgumentException.class, () -> CircuitDesign.read(tag));
    ListTag wrongList = new ListTag(); wrongList.add(StringTag.valueOf("bad"));
    tag.put("Components", wrongList);
    assertThrows(IllegalArgumentException.class, () -> CircuitDesign.read(tag));
    CompoundTag wrongScalar = CircuitDesign.empty().write(); wrongScalar.putInt("Wires", 0);
    assertThrows(IllegalArgumentException.class, () -> CircuitDesign.read(wrongScalar));
  }
}
