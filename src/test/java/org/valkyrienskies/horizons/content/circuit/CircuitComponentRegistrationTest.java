package org.valkyrienskies.horizons.content.circuit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType;
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Cell;
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Pin;
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Sprite;
import org.valkyrienskies.horizons.api.circuits.CircuitComponents;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire;
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeTypes;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.VoltageSourceNode;
import static org.junit.jupiter.api.Assertions.*;

class CircuitComponentRegistrationTest {
  private static CircuitComponentType addon;
  private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test_addon", "variable_resistor");
  private static final Sprite SPRITE = new Sprite(ResourceLocation.fromNamespaceAndPath("test_addon", "textures/variable_resistor.png"), 64, 32, 16, 16);

  private static class VariableResistor extends PowerNode {
    double resistance = 200;
    VariableResistor() { super(2); }
    @Override public void stamp(CircuitStampContext context) { context.stampResistance(0, 1, resistance); }
  }

  @BeforeAll static void registerComponents() {
    BuiltinCircuitComponents.initialize();
    addon = CircuitComponents.register(new CircuitComponentType(ID,
        () -> { throw new AssertionError("Simulation must not initialize the Forge item registry"); },
        "item.test_addon.variable_resistor", List.of(new Pin(-1, 0, "pin.test_addon.a"), new Pin(2, 0, "pin.test_addon.b")),
        List.of(new Cell(0, 0), new Cell(1, 0)), SPRITE, new CircuitComponentType.Simulation() {
          @Override public IPowerNode createNode() { return new VariableResistor(); }
          @Override public CompoundTag saveState(IPowerNode node) {
            CompoundTag state = new CompoundTag();
            state.putDouble("Resistance", ((VariableResistor) node).resistance);
            return state;
          }
          @Override public void loadState(IPowerNode node, CompoundTag state) {
            double resistance = state.getDouble("Resistance");
            if (!Double.isFinite(resistance) || resistance <= 0) throw new IllegalArgumentException("Invalid resistance");
            ((VariableResistor) node).resistance = resistance;
          }
        }));
  }

  static Stream<Supplier<IPBSolver>> solvers() { return Stream.of(EJMLSolver::new, JKLUSolver::new); }

  @Test void addonGeometryRotatesAndSurvivesSchematicRoundTrip() {
    Placed placed = new Placed(ID, 5, 5, 1);
    assertEquals(List.of(new Point(5, 4), new Point(5, 7)), CircuitDesign.pins(placed));
    assertEquals(List.of(new Point(5, 5), new Point(5, 6)), CircuitDesign.bodies(placed));
    CircuitDesign design = CircuitDesign.empty().withComponent(placed);
    assertEquals(design, CircuitDesign.read(design.write()));
    assertEquals(ID.toString(), design.write().getList("Components", 10).getCompound(0).getString("Type"));
    assertThrows(IllegalArgumentException.class, () -> design.withWire(new Wire(new Point(5, 6), new Point(0, 0), 0)));
    assertThrows(IllegalArgumentException.class, () -> design.withComponent(new Placed("resistor", 5, 6, 0)));
    assertSame(addon, CircuitComponents.get(ID));
  }

  @ParameterizedTest @MethodSource("solvers")
  void addonRunsInNetworkAndKeepsItsOwnStateThroughEditsAndArchive(Supplier<IPBSolver> solver) {
    var placed = new Placed(ID, 5, 5, 0);
    var design = new CircuitDesign(List.of(placed), List.of(
        new Wire(CircuitDesign.socket(0), new Point(4, 5), 0), new Wire(new Point(7, 5), CircuitDesign.socket(4), 0)));
    var board = new CircuitBoardNode(design);
    ((VariableResistor) board.getChildren().get(0)).resistance = 400;
    var network = new PowerNetworkServer(solver.get());
    network.markLoaded();
    var source = new VoltageSourceNode(12);
    var load = new ResistorNode(200);
    network.addNode(board); network.addNode(source); network.addNode(load);
    network.withState(() -> {
      connect(source, 0, board, 0); connect(board, 4, load, 0); connect(load, 1, source, 1);
      return null;
    });
    for (int step = 0; step < 20; step++) network.energyTick(0.05);
    assertEquals(4, network.getVoltageBetween(load, 0, load, 1), 1e-4);
    board.setDesign(design.withWire(new Wire(new Point(0, 0), new Point(1, 0), 6)));
    assertEquals(400, ((VariableResistor) board.getChildren().get(0)).resistance);
    assertTrue(board.isConnected(source, 0, 0));
    var restored = (CircuitBoardNode) PowerNodeTypes.decode(PowerNodeTypes.encode(board));
    assertNotNull(restored);
    assertEquals(board.getDesign(), restored.getDesign());
    assertEquals(400, ((VariableResistor) restored.getChildren().get(0)).resistance);
    var invalid = board.writeState();
    invalid.getList("ComponentStates", 10).getCompound(0).putDouble("Resistance", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> CircuitBoardNode.readState(invalid));
  }

  @Test void legacyUnqualifiedIdsAndCapacitorVoltageAreMigrated() {
    var design = CircuitDesign.empty().withComponent(new Placed("capacitor", 5, 5, 0));
    var legacy = new CompoundTag();
    var savedDesign = design.write();
    savedDesign.getList("Components", 10).getCompound(0).putString("Type", "capacitor");
    legacy.put("Design", savedDesign);
    var states = new ListTag(); states.add(DoubleTag.valueOf(3.75));
    legacy.put("ComponentStates", states);
    var archive = new CompoundTag();
    archive.putString("Type", "potato_battery:circuit_board"); archive.putInt("Version", 1); archive.put("State", legacy);
    var restored = (CircuitBoardNode) PowerNodeTypes.decode(archive);
    assertNotNull(restored);
    assertEquals(design, restored.getDesign());
    assertEquals(3.75, restored.writeState().getList("ComponentStates", 10).getCompound(0).getDouble("Voltage"));
  }

  @Test void registrationRejectsDuplicatesAndCopiesGeometry() {
    assertThrows(IllegalArgumentException.class, () -> CircuitComponents.register(addon));
    var pins = new ArrayList<>(List.of(new Pin(0, 0, "pin.test.one")));
    var type = new CircuitComponentType(ResourceLocation.fromNamespaceAndPath("test_addon", "one_pin"), () -> null,
        "item.test.one_pin", pins, List.of(), SPRITE, () -> new PowerNode(1) {});
    pins.clear();
    assertEquals(1, type.pins().size());
    assertThrows(UnsupportedOperationException.class, () -> type.pins().clear());
    assertThrows(IllegalArgumentException.class, () -> new CircuitComponentType(type.id(), type.item(), type.translationKey(),
        type.pins(), List.of(new Cell(0, 0)), SPRITE, type.simulation()));
    var wrongPorts = new CircuitComponentType(type.id(), type.item(), type.translationKey(), type.pins(), List.of(), SPRITE,
        () -> new ResistorNode(100));
    assertThrows(IllegalArgumentException.class, wrongPorts::createNode);
  }

  private static void connect(IPowerNode first, int port, IPowerNode second, int otherPort) {
    first.addConnection(second, port, otherPort); second.addConnection(first, otherPort, port);
  }
}
