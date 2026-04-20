package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.JunctionNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NMOSTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.SolverPhaseDiagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class SolverPhaseBenchmarkDiagnostics {
  private static final double WIRE_RESISTANCE = 1.0e-6;

  private SolverPhaseBenchmarkDiagnostics() {
  }

  public static void main(String[] args) throws IOException {
    int iterations = Integer.getInteger("power.benchmark.iterations", 20);
    List<SolverSpec> solvers = selectedSolvers(System.getProperty("power.solver", "all"));
    Path outputDir = Path.of("build", "reports", "solver-phase-benchmarks");
    Files.createDirectories(outputDir);

    for (SolverSpec solverSpec : solvers) {
      List<String> rows = new ArrayList<>();
      rows.add("solver,scenario,iterations,wall_micros,topology_micros,stamp_micros,csc_micros,factor_micros,solve_micros,writeback_micros,other_micros,nonlinear_iterations,node_count,branch_count,unknown_count,substeps,average_nnz,pattern_reuse_ratio");

      List<ScenarioResult> results = List.of(
          benchmarkScenario("diode_clamp_bus", solverSpec.factory().get(), iterations, SolverPhaseBenchmarkDiagnostics::buildDiodeClampBus),
          benchmarkScenario("nmos_switch_bank", solverSpec.factory().get(), iterations, SolverPhaseBenchmarkDiagnostics::buildNmosSwitchBank)
      );

      System.out.println(solverSpec.name() + " solver phase benchmarks:");
      for (ScenarioResult result : results) {
        rows.add(result.toCsvRow(solverSpec.name(), iterations));
        System.out.printf(
            Locale.ROOT,
            "  %-16s wall=%.3f us stamp=%.3f us csc=%.3f us factor=%.3f us solve=%.3f us topo=%.3f us write=%.3f us iter=%.2f reuse=%.2f%n",
            result.name(),
            result.wallMicros(),
            result.stampMicros(),
            result.cscMicros(),
            result.factorMicros(),
            result.solveMicros(),
            result.topologyMicros(),
            result.writeBackMicros(),
            result.averageIterations(),
            result.patternReuseRatio()
        );
      }

      Path output = outputDir.resolve(solverSpec.name().toLowerCase(Locale.ROOT) + ".csv");
      Files.write(output, rows, StandardCharsets.UTF_8);
      System.out.println("Wrote " + output.toAbsolutePath());
    }
  }

  private static ScenarioResult benchmarkScenario(
      String name,
      IPBSolver solver,
      int iterations,
      NetworkFactory factory
  ) {
    PowerNetworkServer network = factory.create(solver);
    for (int i = 0; i < 6; i++) {
      network.physTick();
    }

    long wallNanos = 0L;
    long topologyNanos = 0L;
    long stampNanos = 0L;
    long cscNanos = 0L;
    long factorNanos = 0L;
    long solveNanos = 0L;
    long writeBackNanos = 0L;
    long measuredNanos = 0L;
    long nonlinearIterations = 0L;
    long totalNonZeros = 0L;
    long totalPatternReuse = 0L;
    int nodeCount = 0;
    int branchCount = 0;
    int unknownCount = 0;
    int substeps = 0;

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      SolverPhaseDiagnostics.PhaseBenchmarkResult result = SolverPhaseDiagnostics.benchmarkStep(solver, network, 1);
      wallNanos += System.nanoTime() - start;
      topologyNanos += result.topologyNanos();
      stampNanos += result.stampNanos();
      cscNanos += result.cscNanos();
      factorNanos += result.factorNanos();
      solveNanos += result.solveNanos();
      writeBackNanos += result.writeBackNanos();
      measuredNanos += result.totalMeasuredNanos();
      nonlinearIterations += result.nonlinearIterations();
      totalNonZeros += result.nonZeros();
      totalPatternReuse += result.patternReuseCount();
      nodeCount = result.nodeCount();
      branchCount = result.branchCount();
      unknownCount = result.unknownCount();
      substeps = result.substeps();
    }

    return new ScenarioResult(
        name,
        micros(wallNanos, iterations),
        micros(topologyNanos, iterations),
        micros(stampNanos, iterations),
        micros(cscNanos, iterations),
        micros(factorNanos, iterations),
        micros(solveNanos, iterations),
        micros(writeBackNanos, iterations),
        micros(Math.max(wallNanos - measuredNanos, 0L), iterations),
        (double) nonlinearIterations / iterations,
        nodeCount,
        branchCount,
        unknownCount,
        substeps,
        (double) totalNonZeros / iterations,
        (double) totalPatternReuse / Math.max(nonlinearIterations, 1L)
    );
  }

  private static double micros(long nanos, int iterations) {
    return (double) nanos / iterations / TimeUnit.MICROSECONDS.toNanos(1);
  }

  private static PowerNetworkServer buildDiodeClampBus(IPBSolver solver) {
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
    return network;
  }

  private static PowerNetworkServer buildNmosSwitchBank(IPBSolver solver) {
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

    return network;
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

  private interface NetworkFactory {
    PowerNetworkServer create(IPBSolver solver);
  }

  private record SolverSpec(String name, Supplier<IPBSolver> factory) {}

  private record ScenarioResult(
      String name,
      double wallMicros,
      double topologyMicros,
      double stampMicros,
      double cscMicros,
      double factorMicros,
      double solveMicros,
      double writeBackMicros,
      double otherMicros,
      double averageIterations,
      int nodeCount,
      int branchCount,
      int unknownCount,
      int substeps,
      double averageNonZeros,
      double patternReuseRatio
  ) {
    private String toCsvRow(String solverName, int iterations) {
      return String.format(
          Locale.ROOT,
          "%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.3f,%.3f",
          solverName,
          name,
          iterations,
          wallMicros,
          topologyMicros,
          stampMicros,
          cscMicros,
          factorMicros,
          solveMicros,
          writeBackMicros,
          otherMicros,
          averageIterations,
          nodeCount,
          branchCount,
          unknownCount,
          substeps,
          averageNonZeros,
          patternReuseRatio
      );
    }
  }
}
