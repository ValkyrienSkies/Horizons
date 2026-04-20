package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.BatteryNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedStepNetwork;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.SolverPhaseDiagnostics;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class AstablePhaseBenchmarkDiagnostics {
  private static final double WIRE_RESISTANCE = 1.0e-6;
  private static final double TIME_STEP = 1.0 / 120.0;

  private AstablePhaseBenchmarkDiagnostics() {
  }

  public static void main(String[] args) {
    int iterations = Integer.getInteger("power.benchmark.iterations", 20);
    for (SolverSpec solverSpec : selectedSolvers(System.getProperty("power.solver", "all"))) {
      IPBSolver solver = solverSpec.factory().get();
      AstableScenario scenario = new AstableScenario(solver);
      for (int i = 0; i < 12; i++) {
        scenario.network.physTick();
      }

      int substeps = scenario.network.getLastRequestedSubsteps();
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

      for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        SolverPhaseDiagnostics.PhaseBenchmarkResult result =
            SolverPhaseDiagnostics.benchmarkStep(solver, scenario.network, substeps);
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
      }

      System.out.printf(Locale.ROOT, "%s astable phase benchmark:%n", solverSpec.name());
      System.out.printf(Locale.ROOT, "  substeps      = %d%n", substeps);
      System.out.printf(Locale.ROOT, "  wall          = %.3f us%n", micros(wallNanos, iterations));
      System.out.printf(Locale.ROOT, "  topology      = %.3f us%n", micros(topologyNanos, iterations));
      System.out.printf(Locale.ROOT, "  stamp         = %.3f us%n", micros(stampNanos, iterations));
      System.out.printf(Locale.ROOT, "  csc           = %.3f us%n", micros(cscNanos, iterations));
      System.out.printf(Locale.ROOT, "  factor        = %.3f us%n", micros(factorNanos, iterations));
      System.out.printf(Locale.ROOT, "  solve         = %.3f us%n", micros(solveNanos, iterations));
      System.out.printf(Locale.ROOT, "  writeBack     = %.3f us%n", micros(writeBackNanos, iterations));
      System.out.printf(Locale.ROOT, "  other         = %.3f us%n", micros(Math.max(wallNanos - measuredNanos, 0L), iterations));
      System.out.printf(Locale.ROOT, "  Newton it     = %.2f%n", (double) nonlinearIterations / iterations);
      System.out.printf(Locale.ROOT, "  avg nnz       = %.2f%n", (double) totalNonZeros / iterations);
      System.out.printf(Locale.ROOT, "  pattern reuse = %.2f%n", (double) totalPatternReuse / Math.max(nonlinearIterations, 1L));
      System.out.printf(Locale.ROOT, "  topology      = nodes=%d branches=%d unknowns=%d%n", nodeCount, branchCount, unknownCount);
    }
  }

  private static double micros(long nanos, int iterations) {
    return (double) nanos / iterations / 1_000.0;
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

  private static final class AstableScenario {
    private final FixedStepNetwork network;

    private AstableScenario(IPBSolver solver) {
      this.network = new FixedStepNetwork(solver, TIME_STEP);
      BatteryNode battery = new BatteryNode(5.0);
      ResistorNode rc1 = new ResistorNode(1.0e3);
      ResistorNode rc2 = new ResistorNode(1.0e3);
      ResistorNode rb1 = new ResistorNode(47.0e3);
      ResistorNode rb2 = new ResistorNode(56.0e3);
      CapacitorNode c1 = new CapacitorNode(4.7e-6);
      CapacitorNode c2 = new CapacitorNode(4.7e-6);
      NPNTransistorNode q1 = new NPNTransistorNode();
      NPNTransistorNode q2 = new NPNTransistorNode();
      DiodeNode q1BaseEmitterReverseClamp = new DiodeNode();
      DiodeNode q2BaseEmitterReverseClamp = new DiodeNode();
      DiodeNode q1BaseCollectorForwardClamp = new DiodeNode();
      DiodeNode q2BaseCollectorForwardClamp = new DiodeNode();
      GroundNode ground = new GroundNode();

      addNode(network, 0, battery);
      addNode(network, 1, rc1);
      addNode(network, 2, rc2);
      addNode(network, 3, rb1);
      addNode(network, 4, rb2);
      addNode(network, 5, c1);
      addNode(network, 6, c2);
      addNode(network, 7, q1);
      addNode(network, 8, q2);
      addNode(network, 9, q1BaseEmitterReverseClamp);
      addNode(network, 10, q2BaseEmitterReverseClamp);
      addNode(network, 11, q1BaseCollectorForwardClamp);
      addNode(network, 12, q2BaseCollectorForwardClamp);
      addNode(network, 13, ground);

      connectBidirectional(battery, 0, rc1, 0, WIRE_RESISTANCE);
      connectBidirectional(rc1, 0, rc2, 0, WIRE_RESISTANCE);
      connectBidirectional(rc2, 0, rb1, 0, WIRE_RESISTANCE);
      connectBidirectional(rb1, 0, rb2, 0, WIRE_RESISTANCE);
      connectBidirectional(battery, 1, ground, 0, WIRE_RESISTANCE);
      connectBidirectional(ground, 0, q1, 2, WIRE_RESISTANCE);
      connectBidirectional(q1, 2, q2, 2, WIRE_RESISTANCE);
      connectBidirectional(rc1, 1, q1, 1, WIRE_RESISTANCE);
      connectBidirectional(q1, 1, c1, 0, WIRE_RESISTANCE);
      connectBidirectional(rc2, 1, q2, 1, WIRE_RESISTANCE);
      connectBidirectional(q2, 1, c2, 0, WIRE_RESISTANCE);
      connectBidirectional(rb1, 1, q1, 0, WIRE_RESISTANCE);
      connectBidirectional(q1, 0, c2, 1, WIRE_RESISTANCE);
      connectBidirectional(rb2, 1, q2, 0, WIRE_RESISTANCE);
      connectBidirectional(q2, 0, c1, 1, WIRE_RESISTANCE);
      connectBidirectional(q1, 2, q1BaseEmitterReverseClamp, 0, WIRE_RESISTANCE);
      connectBidirectional(q1BaseEmitterReverseClamp, 1, q1, 0, WIRE_RESISTANCE);
      connectBidirectional(q2, 2, q2BaseEmitterReverseClamp, 0, WIRE_RESISTANCE);
      connectBidirectional(q2BaseEmitterReverseClamp, 1, q2, 0, WIRE_RESISTANCE);
      connectBidirectional(q1, 0, q1BaseCollectorForwardClamp, 0, WIRE_RESISTANCE);
      connectBidirectional(q1BaseCollectorForwardClamp, 1, q1, 1, WIRE_RESISTANCE);
      connectBidirectional(q2, 0, q2BaseCollectorForwardClamp, 0, WIRE_RESISTANCE);
      connectBidirectional(q2BaseCollectorForwardClamp, 1, q2, 1, WIRE_RESISTANCE);
    }
  }
}
