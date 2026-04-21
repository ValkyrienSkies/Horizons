package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.BatteryNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.InductorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.JunctionNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NMOSTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ComponentBenchmarkDiagnostics {
  private static final double WIRE_RESISTANCE = 1.0e-6;

  private ComponentBenchmarkDiagnostics() {
  }

  public static void main(String[] args) throws IOException {
    int iterations = Integer.getInteger("power.benchmark.iterations", 80);
    List<SolverSpec> solvers = selectedSolvers(System.getProperty("power.solver", "all"));
    Path outputDir = Path.of("build", "reports", "component-benchmarks");
    Files.createDirectories(outputDir);

    for (SolverSpec solver : solvers) {
      List<String> rows = new ArrayList<>();
      rows.add("solver,scenario,iterations,average_micros,node_count");

      List<BenchmarkCase> cases = List.of(
          benchmarkCase("resistor_ladder", benchmarkResistorLadder(solver.factory().get(), iterations)),
          benchmarkCase("dense_mesh", benchmarkDenseMesh(solver.factory().get(), iterations)),
          benchmarkCase("rc_ladder", benchmarkRcLadder(solver.factory().get(), iterations)),
          benchmarkCase("rl_chain", benchmarkRlChain(solver.factory().get(), iterations)),
          benchmarkCase("diode_clamp_bus", benchmarkDiodeClampBus(solver.factory().get(), iterations)),
          benchmarkCase("npn_inverter_chain", benchmarkNpnInverterChain(solver.factory().get(), iterations)),
          benchmarkCase("nmos_switch_bank", benchmarkNmosSwitchBank(solver.factory().get(), iterations))
      );

      System.out.println(solver.name() + " component benchmarks:");
      for (BenchmarkCase benchmarkCase : cases) {
        BenchmarkResult result = benchmarkCase.result();
        rows.add(String.format(
            Locale.ROOT,
            "%s,%s,%d,%.3f,%d",
            solver.name(),
            benchmarkCase.name(),
            iterations,
            result.averageMicros(),
            result.nodeCount()
        ));
        System.out.printf(
            Locale.ROOT,
            "  %-18s avg=%.3f us nodes=%d%n",
            benchmarkCase.name(),
            result.averageMicros(),
            result.nodeCount()
        );
      }

      Path output = outputDir.resolve(solver.name().toLowerCase(Locale.ROOT) + ".csv");
      Files.write(output, rows, StandardCharsets.UTF_8);
      System.out.println("Wrote " + output.toAbsolutePath());
    }
  }

  private static BenchmarkCase benchmarkCase(String name, BenchmarkResult result) {
    return new BenchmarkCase(name, result);
  }

  private static BenchmarkResult benchmarkResistorLadder(IPBSolver solver, int iterations) {
    FixedVoltageNode source = new FixedVoltageNode(10.0);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    ResistorNode[] resistors = new ResistorNode[64];
    for (int i = 0; i < resistors.length; i++) {
      resistors[i] = new ResistorNode(10.0);
      addNode(network, i + 1, resistors[i]);
    }
    addNode(network, 100, ground);

    connectBidirectional(source, 0, resistors[0], 0, WIRE_RESISTANCE);
    for (int i = 0; i < resistors.length - 1; i++) {
      connectBidirectional(resistors[i], 1, resistors[i + 1], 0, WIRE_RESISTANCE);
    }
    connectBidirectional(resistors[resistors.length - 1], 1, ground, 0, WIRE_RESISTANCE);
    return benchmarkNetwork(network, iterations, resistors.length + 2);
  }

  private static BenchmarkResult benchmarkDenseMesh(IPBSolver solver, int iterations) {
    int width = 10;
    int height = 10;
    FixedVoltageNode source = new FixedVoltageNode(10.0);
    GroundNode ground = new GroundNode();
    JunctionNode sourceBus = new JunctionNode(height + 1);
    JunctionNode groundBus = new JunctionNode(height + 1);
    JunctionNode[][] grid = new JunctionNode[width][height];

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
        if (x + 1 < width) {
          connectBidirectional(grid[x][y], 2, grid[x + 1][y], 3, 10.0);
        }
        if (y + 1 < height) {
          connectBidirectional(grid[x][y], 4, grid[x][y + 1], 5, 10.0);
        }
      }
    }

    return benchmarkNetwork(network, iterations, width * height + 4);
  }

  private static BenchmarkResult benchmarkRcLadder(IPBSolver solver, int iterations) {
    BatteryNode battery = new BatteryNode(5.0);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new CircuitComponents.FixedStepNetwork(solver, 1.0 / 240.0);
    addNode(network, 0, battery);
    addNode(network, 1, ground);
    int nextX = 2;

    PowerNode previous = battery;
    int previousPort = 0;
    for (int i = 0; i < 12; i++) {
      ResistorNode resistor = new ResistorNode(4.7e3);
      CapacitorNode capacitor = new CapacitorNode(1.0e-6);
      addNode(network, nextX++, resistor);
      addNode(network, nextX++, capacitor);
      connectBidirectional(previous, previousPort, resistor, 0, WIRE_RESISTANCE);
      connectBidirectional(resistor, 1, capacitor, 0, WIRE_RESISTANCE);
      connectBidirectional(capacitor, 1, ground, 0, WIRE_RESISTANCE);
      previous = resistor;
      previousPort = 1;
    }
    connectBidirectional(battery, 1, ground, 0, WIRE_RESISTANCE);
    return benchmarkNetwork(network, iterations, 2 + 24);
  }

  private static BenchmarkResult benchmarkRlChain(IPBSolver solver, int iterations) {
    BatteryNode battery = new BatteryNode(12.0);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new CircuitComponents.FixedStepNetwork(solver, 1.0 / 240.0);
    addNode(network, 0, battery);
    addNode(network, 1, ground);
    int nextX = 2;

    PowerNode previous = battery;
    int previousPort = 0;
    for (int i = 0; i < 10; i++) {
      ResistorNode resistor = new ResistorNode(8.0);
      InductorNode inductor = new InductorNode(2.5e-3);
      addNode(network, nextX++, resistor);
      addNode(network, nextX++, inductor);
      connectBidirectional(previous, previousPort, resistor, 0, WIRE_RESISTANCE);
      connectBidirectional(resistor, 1, inductor, 0, WIRE_RESISTANCE);
      previous = inductor;
      previousPort = 1;
    }
    connectBidirectional((PowerNode) previous, previousPort, ground, 0, WIRE_RESISTANCE);
    connectBidirectional(battery, 1, ground, 0, WIRE_RESISTANCE);
    return benchmarkNetwork(network, iterations, 2 + 20);
  }

  private static BenchmarkResult benchmarkDiodeClampBus(IPBSolver solver, int iterations) {
    FixedVoltageNode source = new FixedVoltageNode(5.0);
    GroundNode ground = new GroundNode();
    JunctionNode bus = new JunctionNode(20);
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    addNode(network, 1, ground);
    addNode(network, 2, bus);
    connectBidirectional(source, 0, bus, 0, WIRE_RESISTANCE);

    int nextX = 3;
    for (int i = 0; i < 16; i++) {
      ResistorNode resistor = new ResistorNode(1.0e3 + i * 250.0);
      DiodeNode diode = new DiodeNode();
      addNode(network, nextX++, resistor);
      addNode(network, nextX++, diode);
      connectBidirectional(bus, i + 1, resistor, 0, WIRE_RESISTANCE);
      connectBidirectional(resistor, 1, diode, 0, WIRE_RESISTANCE);
      connectBidirectional(diode, 1, ground, 0, WIRE_RESISTANCE);
    }
    return benchmarkNetwork(network, iterations, 3 + 32);
  }

  private static BenchmarkResult benchmarkNpnInverterChain(IPBSolver solver, int iterations) {
    FixedVoltageNode source = new FixedVoltageNode(5.0);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    addNode(network, 1, ground);
    FixedVoltageNode input = new FixedVoltageNode(5.0);
    addNode(network, 2, input);
    int nextX = 3;

    PowerNode driveNode = input;
    int drivePort = 0;
    for (int i = 0; i < 8; i++) {
      ResistorNode collector = new ResistorNode(2.2e3);
      ResistorNode base = new ResistorNode(47.0e3);
      NPNTransistorNode transistor = new NPNTransistorNode();
      addNode(network, nextX++, collector);
      addNode(network, nextX++, base);
      addNode(network, nextX++, transistor);

      connectBidirectional(source, 0, collector, 0, WIRE_RESISTANCE);
      connectBidirectional(collector, 1, transistor, 1, WIRE_RESISTANCE);
      connectBidirectional(transistor, 2, ground, 0, WIRE_RESISTANCE);
      connectBidirectional(driveNode, drivePort, base, 0, WIRE_RESISTANCE);
      connectBidirectional(base, 1, transistor, 0, WIRE_RESISTANCE);

      driveNode = collector;
      drivePort = 1;
    }

    return benchmarkNetwork(network, iterations, 3 + 24);
  }

  private static BenchmarkResult benchmarkNmosSwitchBank(IPBSolver solver, int iterations) {
    FixedVoltageNode source = new FixedVoltageNode(5.0);
    GroundNode ground = new GroundNode();
    PowerNetworkServer network = new PowerNetworkServer(null, null, solver);
    addNode(network, 0, source);
    addNode(network, 1, ground);
    int nextX = 2;

    for (int i = 0; i < 16; i++) {
      FixedVoltageNode gate = new FixedVoltageNode((i % 2 == 0) ? 3.0 : 1.5);
      ResistorNode drain = new ResistorNode(1.0e3 + i * 50.0);
      NMOSTransistorNode transistor = new NMOSTransistorNode();
      addNode(network, nextX++, gate);
      addNode(network, nextX++, drain);
      addNode(network, nextX++, transistor);
      connectBidirectional(source, 0, drain, 0, WIRE_RESISTANCE);
      connectBidirectional(drain, 1, transistor, 0, WIRE_RESISTANCE);
      connectBidirectional(gate, 0, transistor, 1, WIRE_RESISTANCE);
      connectBidirectional(transistor, 2, ground, 0, WIRE_RESISTANCE);
    }

    return benchmarkNetwork(network, iterations, 2 + 48);
  }

  private static BenchmarkResult benchmarkNetwork(PowerNetworkServer network, int iterations, int nodeCount) {
    for (int i = 0; i < 8; i++) {
      network.energyTick();
    }
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      network.energyTick();
    }
    long elapsed = System.nanoTime() - start;
    double averageMicros = (double) elapsed / iterations / TimeUnit.MICROSECONDS.toNanos(1);
    return new BenchmarkResult(averageMicros, nodeCount);
  }

  private static void addNode(PowerNetworkServer network, int x, PowerNode node) {
    network.addNode(new BlockPos(x, 0, 0), node);
  }

  private static void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
    a.addConnection(b, aPort, bPort, resistance);
    b.addConnection(a, bPort, aPort, resistance);
  }

  private static List<SolverSpec> selectedSolvers(String solverName) {
    String normalized = solverName.toLowerCase(Locale.ROOT);
    if ("ejml".equals(normalized)) {
      return List.of(new SolverSpec("EJML", EJMLSolver::new));
    }
    if ("jklu".equals(normalized)) {
      return List.of(new SolverSpec("JKLU", JKLUSolver::new));
    }
    return List.of(
        new SolverSpec("EJML", EJMLSolver::new),
        new SolverSpec("JKLU", JKLUSolver::new)
    );
  }

  private record SolverSpec(String name, Supplier<IPBSolver> factory) {}

  private record BenchmarkCase(String name, BenchmarkResult result) {}

  private record BenchmarkResult(double averageMicros, int nodeCount) {}
}
