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

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class AstableSolveBenchmarkDiagnostics {
  private static final double WIRE_RESISTANCE = 1.0e-6;
  private static final double TIME_STEP = 1.0 / 240.0;

  private AstableSolveBenchmarkDiagnostics() {
  }

  public static void main(String[] args) {
    int iterations = Integer.getInteger("power.benchmark.iterations", 30);
    for (SolverSpec solver : selectedSolvers(System.getProperty("power.solver", "all"))) {
      AstableScenario scenario = new AstableScenario(solver.factory().get());
      for (int i = 0; i < 12; i++) {
        scenario.network.physTick();
      }

      long wallNanos = 0L;
      long topologyNanos = 0L;
      long nonlinearIterations = 0L;
      long substeps = 0L;
      long iterationLimitHits = 0L;

      for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        scenario.network.physTick();
        wallNanos += System.nanoTime() - start;
        nonlinearIterations += scenario.network.getLastNonlinearIterations();
        substeps += scenario.network.getLastRequestedSubsteps();
        topologyNanos += 0L;
        iterationLimitHits += scenario.network.wasLastIterationLimitHit() ? 1 : 0;
      }

      double avgWallMicros = micros(wallNanos, iterations);
      double avgIterations = (double) nonlinearIterations / iterations;
      double avgSubsteps = (double) substeps / iterations;
      double simulatedTicksPerSecond = 1.0 / TIME_STEP;
      double actualTicksPerSecond = 1_000_000.0 / avgWallMicros;

      System.out.printf(Locale.ROOT, "%s astable solve benchmark:%n", solver.name());
      System.out.printf(Locale.ROOT, "  avg wall      = %.3f us%n", avgWallMicros);
      System.out.printf(Locale.ROOT, "  avg substeps  = %.2f%n", avgSubsteps);
      System.out.printf(Locale.ROOT, "  avg Newton it = %.2f%n", avgIterations);
      System.out.printf(Locale.ROOT, "  iteration cap hits = %d / %d%n", iterationLimitHits, iterations);
      System.out.printf(Locale.ROOT, "  current adaptive cap = %d%n", scenario.network.getAdaptiveDynamicNonlinearSubsteps());
      System.out.printf(Locale.ROOT, "  ideal TPS     = %.2f%n", simulatedTicksPerSecond);
      System.out.printf(Locale.ROOT, "  actual TPS    = %.2f%n", actualTicksPerSecond);
      System.out.printf(Locale.ROOT, "  slowdown      = %.2fx%n", simulatedTicksPerSecond / actualTicksPerSecond);
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
