package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerSolverTest {
  private static final double SUPPLY_VOLTAGE = 10.0;
  private static final double WIRE_RESISTANCE = 1.0e-6;
  private static final double VOLTAGE_TOLERANCE = 1.0e-4;
  private static final double CURRENT_TOLERANCE = 1.0e-5;

  static Stream<Arguments> solvers() {
    return Stream.of(
        Arguments.of("EJML", (Supplier<IPBSolver>) EJMLSolver::new),
        Arguments.of("JKLU", (Supplier<IPBSolver>) JKLUSolver::new)
    );
  }

  @ParameterizedTest(name = "{0} solves a single load resistor")
  @MethodSource("solvers")
  void singleResistorMatchesOhmsLaw(String solverName, Supplier<IPBSolver> solverFactory) {
    FixedVoltageNode source = new FixedVoltageNode(SUPPLY_VOLTAGE);
    ResistorNode load = new ResistorNode(5.0);
    GroundNode ground = new GroundNode();

    PowerNetworkServer network = runCircuit(solverFactory.get(), source, load, ground);

    double expectedCurrent = SUPPLY_VOLTAGE / (5.0 + 2.0 * WIRE_RESISTANCE);
    assertEquals(expectedCurrent, network.getCurrentOver(source, load, 0, 0), CURRENT_TOLERANCE);
    assertEquals(SUPPLY_VOLTAGE - expectedCurrent * WIRE_RESISTANCE, network.getVoltageAt(load, 0), VOLTAGE_TOLERANCE);
    assertEquals(expectedCurrent * WIRE_RESISTANCE, network.getVoltageAt(load, 1), VOLTAGE_TOLERANCE);
  }

  @ParameterizedTest(name = "{0} solves a voltage divider")
  @MethodSource("solvers")
  void equalVoltageDividerProducesMidpoint(String solverName, Supplier<IPBSolver> solverFactory) {
    FixedVoltageNode source = new FixedVoltageNode(SUPPLY_VOLTAGE);
    ResistorNode resistorA = new ResistorNode(100.0);
    ResistorNode resistorB = new ResistorNode(100.0);
    GroundNode ground = new GroundNode();

    PowerNetworkServer network = new PowerNetworkServer(null, null, solverFactory.get());
    addNode(network, 0, source);
    addNode(network, 1, resistorA);
    addNode(network, 2, resistorB);
    addNode(network, 3, ground);

    connectBidirectional(source, 0, resistorA, 0, WIRE_RESISTANCE);
    connectBidirectional(resistorA, 1, resistorB, 0, WIRE_RESISTANCE);
    connectBidirectional(resistorB, 1, ground, 0, WIRE_RESISTANCE);
    network.physTick();

    double expectedCurrent = SUPPLY_VOLTAGE / (200.0 + 3.0 * WIRE_RESISTANCE);
    double expectedMidpoint = expectedCurrent * (100.0 + WIRE_RESISTANCE);

    assertEquals(expectedCurrent, network.getCurrentOver(resistorA, resistorB, 1, 0), CURRENT_TOLERANCE);
    assertEquals(expectedMidpoint, network.getVoltageAt(resistorA, 1), VOLTAGE_TOLERANCE);
    assertEquals(expectedMidpoint, network.getVoltageAt(resistorB, 0), VOLTAGE_TOLERANCE);
  }

  @ParameterizedTest(name = "{0} solves a two terminal battery")
  @MethodSource("solvers")
  void twoTerminalBatteryMatchesExpectedTerminalVoltage(String solverName, Supplier<IPBSolver> solverFactory) {
    BatteryNode battery = new BatteryNode(SUPPLY_VOLTAGE);
    ResistorNode load = new ResistorNode(5.0);
    GroundNode ground = new GroundNode();

    PowerNetworkServer network = new PowerNetworkServer(null, null, solverFactory.get());
    addNode(network, 0, battery);
    addNode(network, 1, load);
    addNode(network, 2, ground);

    connectBidirectional(battery, 0, load, 0, WIRE_RESISTANCE);
    connectBidirectional(load, 1, ground, 0, WIRE_RESISTANCE);
    connectBidirectional(battery, 1, ground, 0, WIRE_RESISTANCE);
    network.physTick();

    double expectedCurrent = SUPPLY_VOLTAGE / (5.0 + 3.0 * WIRE_RESISTANCE);
    assertEquals(expectedCurrent, network.getCurrentOver(battery, load, 0, 0), CURRENT_TOLERANCE);
    assertEquals(SUPPLY_VOLTAGE, network.getVoltageAt(battery, 0) - network.getVoltageAt(battery, 1), VOLTAGE_TOLERANCE);
    assertEquals(expectedCurrent * WIRE_RESISTANCE, network.getVoltageAt(battery, 1), VOLTAGE_TOLERANCE);
    assertEquals(SUPPLY_VOLTAGE + expectedCurrent * WIRE_RESISTANCE, network.getVoltageAt(battery, 0), VOLTAGE_TOLERANCE);
  }

  @ParameterizedTest(name = "{0} benchmark on resistor ladder")
  @MethodSource("solvers")
  void ladderBenchmarkStaysStable(String solverName, Supplier<IPBSolver> solverFactory) {
    BenchmarkResult benchmark = benchmarkSeriesLadder(solverFactory.get(), 64, 10.0, 200);

    double expectedCurrent = SUPPLY_VOLTAGE / (64.0 * 10.0 + 65.0 * WIRE_RESISTANCE);
    assertEquals(expectedCurrent, benchmark.current(), CURRENT_TOLERANCE);
    assertEquals(SUPPLY_VOLTAGE, benchmark.sourceVoltage(), VOLTAGE_TOLERANCE);
    assertTrue(benchmark.averageMicros() > 0.0);

    System.out.printf(
        "%s ladder benchmark: sections=%d iterations=%d avg=%.3f us current=%.6f A%n",
        solverName,
        64,
        200,
        benchmark.averageMicros(),
        benchmark.current()
    );
  }

  @ParameterizedTest(name = "{0} benchmark on dense resistor mesh")
  @MethodSource("solvers")
  void denseMeshBenchmarkStaysStable(String solverName, Supplier<IPBSolver> solverFactory) {
    BenchmarkResult benchmark = benchmarkDenseMesh(solverFactory.get(), 12, 12, 10.0, 100);

    assertEquals(SUPPLY_VOLTAGE, benchmark.sourceVoltage(), VOLTAGE_TOLERANCE);
    assertTrue(benchmark.current() > 0.0);
    assertTrue(benchmark.centerVoltage() > 0.0 && benchmark.centerVoltage() < SUPPLY_VOLTAGE);
    assertTrue(benchmark.averageMicros() > 0.0);

    System.out.printf(
        "%s dense mesh benchmark: size=%dx%d iterations=%d avg=%.3f us current=%.6f A center=%.6f V%n",
        solverName,
        12,
        12,
        100,
        benchmark.averageMicros(),
        benchmark.current(),
        benchmark.centerVoltage()
    );
  }

  @ParameterizedTest(name = "{0} dense mesh center voltage stays in bounds")
  @MethodSource("solvers")
  void denseMeshProducesPlausibleVoltages(String solverName, Supplier<IPBSolver> solverFactory) {
    BenchmarkResult benchmark = benchmarkDenseMesh(solverFactory.get(), 8, 8, 10.0, 1);
    assertTrue(benchmark.current() > 0.0, solverName + " should produce positive source current");
    assertTrue(benchmark.centerVoltage() > 0.0, solverName + " center node should be above ground");
    assertTrue(benchmark.centerVoltage() < SUPPLY_VOLTAGE, solverName + " center node should be below source voltage");
  }

  private static PowerNetworkServer runCircuit(IPBSolver solver, FixedVoltageNode source, ResistorNode load, GroundNode ground) {
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    addNode(network, 1, load);
    addNode(network, 2, ground);

    connectBidirectional(source, 0, load, 0, WIRE_RESISTANCE);
    connectBidirectional(load, 1, ground, 0, WIRE_RESISTANCE);
    network.physTick();
    return network;
  }

  private static BenchmarkResult benchmarkSeriesLadder(IPBSolver solver, int sections, double sectionResistance, int iterations) {
    FixedVoltageNode source = new FixedVoltageNode(SUPPLY_VOLTAGE);
    GroundNode ground = new GroundNode();
    ResistorNode[] resistors = new ResistorNode[sections];

    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    for (int i = 0; i < sections; i++) {
      resistors[i] = new ResistorNode(sectionResistance);
      addNode(network, i + 1, resistors[i]);
    }
    addNode(network, sections + 1, ground);

    connectBidirectional(source, 0, resistors[0], 0, WIRE_RESISTANCE);
    for (int i = 0; i < sections - 1; i++) {
      connectBidirectional(resistors[i], 1, resistors[i + 1], 0, WIRE_RESISTANCE);
    }
    connectBidirectional(resistors[sections - 1], 1, ground, 0, WIRE_RESISTANCE);

    network.physTick();

    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      network.physTick();
    }
    long elapsed = System.nanoTime() - start;

    double averageMicros = (double) elapsed / iterations / TimeUnit.MICROSECONDS.toNanos(1);
    double current = network.getCurrentOver(source, resistors[0], 0, 0);
    double sourceVoltage = network.getVoltageAt(source, 0);
    return new BenchmarkResult(averageMicros, current, sourceVoltage, network.getVoltageAt(resistors[sections / 2], 0));
  }

  private static BenchmarkResult benchmarkDenseMesh(IPBSolver solver, int width, int height, double sectionResistance, int iterations) {
    JunctionNode sourceBus = new JunctionNode(height + 1);
    JunctionNode groundBus = new JunctionNode(height + 1);
    JunctionNode[][] grid = new JunctionNode[width][height];

    FixedVoltageNode source = new FixedVoltageNode(SUPPLY_VOLTAGE);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    addNode(network, 1, sourceBus);
    addNode(network, 2, ground);
    addNode(network, 3, groundBus);

    connectBidirectional(source, 0, sourceBus, 0, WIRE_RESISTANCE);
    connectBidirectional(ground, 0, groundBus, 0, WIRE_RESISTANCE);

    int nextX = 4;
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        grid[x][y] = new JunctionNode(6);
        addNode(network, nextX++, grid[x][y]);
      }
    }

    for (int y = 0; y < height; y++) {
      connectBidirectional(sourceBus, y + 1, grid[0][y], 3, WIRE_RESISTANCE);
      connectBidirectional(groundBus, y + 1, grid[width - 1][y], 2, WIRE_RESISTANCE);
    }

    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        JunctionNode node = grid[x][y];
        if (x + 1 < width) {
          connectBidirectional(node, 2, grid[x + 1][y], 3, sectionResistance);
        }
        if (y + 1 < height) {
          connectBidirectional(node, 4, grid[x][y + 1], 5, sectionResistance);
        }
      }
    }

    network.physTick();

    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      network.physTick();
    }
    long elapsed = System.nanoTime() - start;

    double averageMicros = (double) elapsed / iterations / TimeUnit.MICROSECONDS.toNanos(1);
    double sourceCurrent = Math.abs(network.getCurrentOver(source, sourceBus, 0, 0));

    JunctionNode center = grid[width / 2][height / 2];
    double centerVoltage = averageNodeVoltage(network, center);
    return new BenchmarkResult(averageMicros, sourceCurrent, network.getVoltageAt(source, 0), centerVoltage);
  }

  private static double averageNodeVoltage(PowerNetworkServer network, PowerNode node) {
    double total = 0.0;
    for (int port = 0; port < node.getPorts(); port++) {
      total += network.getVoltageAt(node, port);
    }
    return total / node.getPorts();
  }

  private static void addNode(PowerNetworkServer network, int x, PowerNode node) {
    network.addNode(new BlockPos(x, 0, 0), node);
  }

  private static void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
    a.addConnection(b, aPort, bPort, resistance);
    b.addConnection(a, bPort, aPort, resistance);
  }

  private record BenchmarkResult(double averageMicros, double current, double sourceVoltage, double centerVoltage) {}

  private static final class FixedVoltageNode extends PowerNode {
    private final double voltage;

    private FixedVoltageNode(double voltage) {
      super(1);
      this.voltage = voltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, voltage);
    }
  }

  private static final class GroundNode extends PowerNode {
    private GroundNode() {
      super(1);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, 0.0);
    }
  }

  private static final class ResistorNode extends PowerNode {
    private final double resistance;

    private ResistorNode(double resistance) {
      super(2);
      this.resistance = resistance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampResistance(0, 1, resistance);
    }
  }

  private static final class BatteryNode extends PowerNode {
    private final double voltage;

    private BatteryNode(double voltage) {
      super(2);
      this.voltage = voltage;
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, 1, voltage);
    }
  }

  private static final class JunctionNode extends PowerNode {
    private JunctionNode(int ports) {
      super(ports);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      for (int port = 1; port < getPorts(); port++) {
        context.stampResistance(0, port, 1.0e-9);
      }
    }
  }
}
