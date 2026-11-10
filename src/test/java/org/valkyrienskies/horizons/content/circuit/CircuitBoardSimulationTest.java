package org.valkyrienskies.horizons.content.circuit;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire;
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode;
import org.valkyrienskies.horizons.impl.devices.persistence.PowerNetworkArchive;
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

class CircuitBoardSimulationTest {
  static Stream<Supplier<IPBSolver>> solvers() { return Stream.of(EJMLSolver::new, JKLUSolver::new); }
  private static PowerNetworkServer network(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver); network.markLoaded(); return network;
  }
  private static Wire wire(Point from, Point to) { return new Wire(from, to, 0); }
  private static CircuitDesign twoTerminal(String type) {
    return new CircuitDesign(List.of(new Placed(type, 4, 4, 0)), List.of(
        wire(CircuitDesign.socket(0), new Point(4, 4)), wire(new Point(5, 4), CircuitDesign.socket(4))));
  }
  private static void connect(IPowerNode first, int port, IPowerNode second, int otherPort) {
    first.addConnection(second, port, otherPort); second.addConnection(first, otherPort, port);
  }
  private static void settle(PowerNetworkServer network) { for (int tick = 0; tick < 20; tick++) network.energyTick(0.05); }

  @ParameterizedTest @MethodSource("solvers")
  void wireOnlyBoxPowersLampThroughFrontLeftSockets(Supplier<IPBSolver> solver) {
    PowerNetworkServer network = network(solver.get());
    PowerNetworkArchive archive = new PowerNetworkArchive(network, () -> {});
    CircuitDesign design = new CircuitDesign(List.of(), List.of(wire(new Point(1, -1), new Point(1, 11))));
    var board = archive.attach(new BlockPos(0, 64, 0), "test:circuit_box", -1, () -> new CircuitBoardNode(design));
    var battery = archive.attach(new BlockPos(2, 64, 0), "test:battery", -1, () -> new VoltageSourceNode(12));
    var lamp = archive.attach(new BlockPos(4, 64, 0), "test:lamp", -1, () -> new ResistorNode(1.44));
    archive.connect(battery.id(), 0, board.id(), 3, 1e-6);
    archive.connect(board.id(), 7, lamp.id(), 0, 1e-6);
    archive.connect(lamp.id(), 1, battery.id(), 1, 1e-6);
    settle(network);
    assertEquals(12, network.getVoltageBetween(lamp.node(), 0, lamp.node(), 1), 1e-3);
    assertEquals(100, ((ResistorNode) lamp.node()).getDissipatedPower(network), 0.01);
    CircuitBoardNode node = (CircuitBoardNode) board.node();
    node.setDesign(new CircuitDesign(List.of(), List.of(wire(new Point(9, -1), new Point(9, 11)))));
    settle(network);
    assertEquals(0, network.getVoltageBetween(lamp.node(), 0, lamp.node(), 1), 1e-6);
    node.setDesign(design);
    settle(network);
    assertEquals(12, network.getVoltageBetween(lamp.node(), 0, lamp.node(), 1), 1e-3);
    CompoundTag saved = archive.write(new CompoundTag());
    PowerNetworkServer restored = network(solver.get());
    PowerNetworkArchive restoredArchive = new PowerNetworkArchive(restored, () -> {});
    restoredArchive.read(saved);
    restoredArchive.activateChunk(PowerNetworkArchive.chunkOf(new BlockPos(0, 64, 0)));
    settle(restored);
    IPowerNode restoredLamp = restored.getNode(lamp.id());
    assertEquals(12, restored.getVoltageBetween(restoredLamp, 0, restoredLamp, 1), 1e-3);
  }

  @ParameterizedTest @MethodSource("solvers")
  void realResistorChildrenShareNetworkAndEditsKeepExternalCables(Supplier<IPBSolver> solver) {
    PowerNetworkServer network = network(solver.get());
    CircuitBoardNode board = new CircuitBoardNode(twoTerminal("resistor"));
    VoltageSourceNode source = new VoltageSourceNode(5);
    long boardId = network.addNode(board); network.addNode(source);
    network.withState(() -> { connect(board, 0, source, 0); connect(board, 4, source, 1); return null; });
    settle(network);
    ResistorNode resistor = (ResistorNode) board.getChildren().get(0);
    assertEquals(0.025, resistor.getDissipatedPower(network), 1e-5);
    List<IPowerNode> previousChildren = board.getChildren();
    network.withState(() -> { board.setDesign(twoTerminal("resistor").withoutWire(0)); return null; });
    assertTrue(board.isConnected(source, 0, 0)); assertTrue(board.isConnected(source, 4, 1));
    assertTrue(previousChildren.stream().noneMatch(network.getNodes()::contains));
    settle(network);
    assertEquals(0, ((ResistorNode) board.getChildren().get(0)).getDissipatedPower(network), 1e-8);
    network.removeNode(boardId); network.withState(() -> null);
    assertEquals(List.of(source), List.copyOf(network.getNodes())); assertTrue(source.getConnections().isEmpty());
  }

  @ParameterizedTest @MethodSource("solvers")
  void endpointConnectivityDoesNotJoinCrossings(Supplier<IPBSolver> solver) {
    PowerNetworkServer network = network(solver.get());
    CircuitDesign design = new CircuitDesign(List.of(), List.of(
        wire(CircuitDesign.socket(0), new Point(0, 0)), wire(new Point(0, 0), new Point(10, 10)),
        wire(new Point(10, 10), CircuitDesign.socket(1)), wire(CircuitDesign.socket(4), new Point(0, 10)),
        wire(new Point(0, 10), new Point(10, 0)), wire(new Point(10, 0), CircuitDesign.socket(5))));
    CircuitBoardNode board = new CircuitBoardNode(design);
    VoltageSourceNode source = new VoltageSourceNode(5); ResistorNode load = new ResistorNode(1000);
    network.addNode(board); network.addNode(source); network.addNode(load);
    network.withState(() -> {
      connect(source, 0, board, 0); connect(source, 1, board, 4);
      connect(load, 0, board, 1); connect(load, 1, board, 5); return null;
    });
    settle(network);
    assertEquals(0.025, load.getDissipatedPower(network), 1e-5);
    assertFalse(network.didLastSolveFail());
  }

  @ParameterizedTest @MethodSource("solvers")
  void archiveReconstructsChildrenRetainsCapacitorAndReservesIds(Supplier<IPBSolver> solver) {
    PowerNetworkServer network = network(solver.get());
    PowerNetworkArchive archive = new PowerNetworkArchive(network, () -> {});
    BlockPos position = new BlockPos(0, 64, 0);
    var attached = archive.attach(position, "test:board", -1, () -> new CircuitBoardNode(twoTerminal("capacitor")));
    var supply = archive.attach(new BlockPos(32, 64, 0), "test:supply", -1, () -> new VoltageSourceNode(5));
    archive.connect(attached.id(), 0, supply.id(), 0, 1e-6); archive.connect(attached.id(), 4, supply.id(), 1, 1e-6);
    settle(network);
    CircuitBoardNode board = (CircuitBoardNode) attached.node();
    double voltage = board.writeState().getList("ComponentStates", 6).getDouble(0);
    assertEquals(5, voltage, 1e-3);
    board.setDesign(board.getDesign().withWire(wire(new Point(0, 0), new Point(0, 1))));
    assertEquals(voltage, board.writeState().getList("ComponentStates", 6).getDouble(0));
    int activeCount = network.getNodes().size();
    long nextId = network.getNextNodeId();
    CompoundTag saved = archive.write(new CompoundTag());
    for (int cycle = 0; cycle < 3; cycle++) {
      archive.suspendChunk(PowerNetworkArchive.chunkOf(position));
      assertEquals(1, network.getNodes().size());
      archive.activateChunk(PowerNetworkArchive.chunkOf(position));
      assertEquals(activeCount, network.getNodes().size());
      CircuitBoardNode restored = (CircuitBoardNode) network.getNode(attached.id());
      assertEquals(voltage, restored.writeState().getList("ComponentStates", 6).getDouble(0));
      assertTrue(restored.isConnected(supply.node(), 0, 0));
    }
    PowerNetworkServer restarted = network(solver.get());
    PowerNetworkArchive second = new PowerNetworkArchive(restarted, () -> {});
    second.read(saved);
    second.activateChunk(PowerNetworkArchive.chunkOf(position));
    assertTrue(restarted.getNextNodeId() > nextId);
    CircuitBoardNode restored = (CircuitBoardNode) restarted.getNode(attached.id());
    assertEquals(voltage, restored.writeState().getList("ComponentStates", 6).getDouble(0));
    second.activateChunk(PowerNetworkArchive.chunkOf(new BlockPos(32, 64, 0)));
    assertEquals(activeCount, restarted.getNodes().size());
    settle(restarted);
    assertEquals(5, restarted.getVoltageAt(restored, 0) - restarted.getVoltageAt(restored, 4), 1e-3);
    second.removeNode(attached.id());
    assertEquals(1, restarted.getNodes().size());
  }

  @ParameterizedTest @MethodSource("solvers")
  void gatesAndNmosDriveActualLoads(Supplier<IPBSolver> solver) {
    for (String type : List.of("and_gate", "or_gate", "nmos")) {
      for (int inputs = 0; inputs < 4; inputs++) {
        PowerNetworkServer network = network(solver.get());
        Placed placed = new Placed(type, 5, 5, 0);
        List<Point> pins = CircuitDesign.pins(placed);
        CircuitDesign design = new CircuitDesign(List.of(placed), List.of(wire(CircuitDesign.socket(0), pins.get(0)),
            wire(CircuitDesign.socket(1), pins.get(1)), wire(CircuitDesign.socket(4), pins.get(2))));
        CircuitBoardNode board = new CircuitBoardNode(design);
        PowerNode first = grounded((inputs & 1) != 0 ? 5 : 0);
        PowerNode second = grounded((inputs & 2) != 0 ? 5 : 0);
        ResistorNode load = new ResistorNode(1000); PowerNode ground = grounded(0);
        network.addNode(board); network.addNode(first); network.addNode(second); network.addNode(load); network.addNode(ground);
        network.withState(() -> {
          connect(first, 0, board, 0); connect(second, 0, board, 1);
          connect(board, 4, load, 0); connect(ground, 0, load, 1); return null;
        });
        settle(network);
        double output = network.getVoltageAt(board, 4);
        if (type.equals("nmos")) {
          if (inputs == 3) assertTrue(output > 2, "NMOS conducts with gate and drain high: " + output);
          else assertEquals(0, output, 1e-3);
        } else {
          boolean high = type.equals("and_gate") ? inputs == 3 : inputs != 0;
          assertEquals(high ? 5.0 * 1000 / 1010 : 0, output, 0.01);
        }
        assertFalse(network.didLastSolveFail());
      }
    }
  }

  private static PowerNode grounded(double voltage) {
    return new PowerNode(1) {
      @Override public int getVoltageSourceCount() { return 1; }
      @Override public void stamp(CircuitStampContext context) { context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, voltage); }
    };
  }

  @Test void codecRejectsMalformedState() {
    CircuitBoardNode original = new CircuitBoardNode(twoTerminal("capacitor"));
    CircuitBoardNode decoded = (CircuitBoardNode) PowerNodeTypes.decode(PowerNodeTypes.encode(original));
    assertNotNull(decoded); assertEquals(original.getDesign(), decoded.getDesign());
    CompoundTag state = original.writeState(); state.remove("ComponentStates");
    assertThrows(IllegalArgumentException.class, () -> CircuitBoardNode.readState(state));
  }

  @ParameterizedTest @MethodSource("solvers")
  void capacitorChargesThroughRealResistorAndRetainsVoltageWithoutSupply(Supplier<IPBSolver> solver) {
    PowerNetworkServer network = network(solver.get());
    CircuitDesign design = new CircuitDesign(List.of(new Placed("resistor", 3, 4, 0), new Placed("capacitor", 4, 4, 0)),
        List.of(wire(CircuitDesign.socket(0), new Point(3, 4)), wire(new Point(5, 4), CircuitDesign.socket(4))));
    CircuitBoardNode board = new CircuitBoardNode(design);
    VoltageSourceNode source = new VoltageSourceNode(5);
    long boardId = network.addNode(board); network.addNode(source);
    network.withState(() -> { connect(board, 0, source, 0); connect(board, 4, source, 1); return null; });
    settle(network);
    double charged = board.writeState().getList("ComponentStates", 6).getDouble(1);
    assertEquals(5 * (1 - Math.exp(-1)), charged, 0.04);
    CompoundTag encoded = PowerNodeTypes.encode(board);
    network.suspendNode(boardId);
    CircuitBoardNode restored = (CircuitBoardNode) PowerNodeTypes.decode(encoded);
    network.restoreNode(boardId, restored);
    settle(network);
    assertEquals(charged, restored.writeState().getList("ComponentStates", 6).getDouble(1), 0.001);
    assertEquals(0, source.getConnections().size());
  }
}
