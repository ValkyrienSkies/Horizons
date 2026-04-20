package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.BatteryNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.SolverDebugHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AstableSpikeDiagnostics {
  private static final double TIME_STEP = 1.0 / 240.0;
  private static final double SPIKE_THRESHOLD = 12.0;
  private static final int PRE_SPIKE_CONTEXT = 12;

  private AstableSpikeDiagnostics() {
  }

  public static void main(String[] args) throws IOException {
    int steps = Integer.getInteger("power.astable.steps", 2000);
    String solverName = System.getProperty("power.solver", "ejml").trim().toLowerCase(Locale.ROOT);
    IPBSolver solver = switch (solverName) {
      case "ejml" -> new EJMLSolver();
      case "jklu" -> new JKLUSolver();
      default -> throw new IllegalArgumentException("Unknown solver '" + solverName + "', expected 'ejml' or 'jklu'");
    };

    AstableCircuit circuit = new AstableCircuit(solver);
    Path outputDir = Path.of("build", "reports", "astable-diagnostics");
    Files.createDirectories(outputDir);
    Path csv = outputDir.resolve(solverName + "-long-run.csv");
    List<String> rows = new ArrayList<>(steps + 1);
    rows.add("step,time,vc1,vc2,vb1,vb2,max_abs_voltage,max_iterations,nonconverged_substeps");

    double worstMagnitude = 0.0;
    int worstStep = -1;
    double worstTime = 0.0;
    int firstSpikeStep = -1;
    int firstNonconvergedStep = -1;
    int worstIterationStep = -1;
    int worstIterationCount = 0;
    List<String> spikeContext = new ArrayList<>();
    ArrayList<String> recentRows = new ArrayList<>(PRE_SPIKE_CONTEXT);
    StepStats stepStats = new StepStats();
    SolverDebugHooks.setListener(stepStats::accept);

    try {
      for (int step = 0; step < steps; step++) {
        stepStats.reset();
        circuit.network.physTick();
        double time = (step + 1) * TIME_STEP;
        double vc1 = circuit.network.getVoltageAt(circuit.q1, 1);
        double vc2 = circuit.network.getVoltageAt(circuit.q2, 1);
        double vb1 = circuit.network.getVoltageAt(circuit.q1, 0);
        double vb2 = circuit.network.getVoltageAt(circuit.q2, 0);
        double maxAbsVoltage = Math.max(Math.max(Math.abs(vc1), Math.abs(vc2)), Math.max(Math.abs(vb1), Math.abs(vb2)));

        String row = String.format(
            Locale.ROOT,
            "%d,%.6f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%d",
            step + 1,
            time,
            vc1,
            vc2,
            vb1,
            vb2,
            maxAbsVoltage,
            stepStats.maxIterations,
            stepStats.nonConvergedSubsteps
        );
        rows.add(row);

        if (recentRows.size() == PRE_SPIKE_CONTEXT) {
          recentRows.remove(0);
        }
        recentRows.add(row);

        if (maxAbsVoltage > worstMagnitude) {
          worstMagnitude = maxAbsVoltage;
          worstStep = step + 1;
          worstTime = time;
        }
        if (stepStats.maxIterations > worstIterationCount) {
          worstIterationCount = stepStats.maxIterations;
          worstIterationStep = step + 1;
        }
        if (firstNonconvergedStep < 0 && stepStats.nonConvergedSubsteps > 0) {
          firstNonconvergedStep = step + 1;
        }

        if (firstSpikeStep < 0 && maxAbsVoltage >= SPIKE_THRESHOLD) {
          firstSpikeStep = step + 1;
          spikeContext.addAll(recentRows);
        } else if (firstSpikeStep > 0 && spikeContext.size() < PRE_SPIKE_CONTEXT * 2) {
          spikeContext.add(row);
        }
      }
    } finally {
      SolverDebugHooks.clearListener();
    }

    Files.write(csv, rows, StandardCharsets.UTF_8);

    System.out.printf(
        Locale.ROOT,
        "Wrote %s%nWorst |V| = %.6f V at step %d (t=%.6f s)%nWorst iteration count = %d at step %d%n",
        csv.toAbsolutePath(),
        worstMagnitude,
        worstStep,
        worstTime,
        worstIterationCount,
        worstIterationStep
    );
    if (firstNonconvergedStep > 0) {
      System.out.printf(Locale.ROOT, "First nonconverged substep at step %d%n", firstNonconvergedStep);
    } else {
      System.out.println("No nonconverged substeps observed.");
    }

    if (firstSpikeStep > 0) {
      System.out.printf(Locale.ROOT, "First spike >= %.2f V at step %d%n", SPIKE_THRESHOLD, firstSpikeStep);
      System.out.println("Spike context:");
      for (String line : spikeContext) {
        System.out.println(line);
      }
    } else {
      System.out.printf(Locale.ROOT, "No spike >= %.2f V observed in %d steps%n", SPIKE_THRESHOLD, steps);
    }
  }

  private static final class StepStats {
    private int maxIterations;
    private int nonConvergedSubsteps;

    private void reset() {
      maxIterations = 0;
      nonConvergedSubsteps = 0;
    }

    private void accept(SolverDebugHooks.SolveReport report) {
      maxIterations = Math.max(maxIterations, report.iterationCount());
      if (!report.converged()) {
        nonConvergedSubsteps++;
      }
    }
  }

  private static final class AstableCircuit {
    private final PowerNetworkServer network;
    private final NPNTransistorNode q1 = new NPNTransistorNode();
    private final NPNTransistorNode q2 = new NPNTransistorNode();

    private AstableCircuit(IPBSolver solver) {
      network = new CircuitComponents.FixedStepNetwork(solver, TIME_STEP);
      BatteryNode battery = new BatteryNode(5.0);
      ResistorNode rc1 = new ResistorNode(1.0e3);
      ResistorNode rc2 = new ResistorNode(1.0e3);
      ResistorNode rb1 = new ResistorNode(47.0e3);
      ResistorNode rb2 = new ResistorNode(56.0e3);
      CapacitorNode c1 = new CapacitorNode(4.7e-6);
      CapacitorNode c2 = new CapacitorNode(4.7e-6);
      DiodeNode q1BaseEmitterReverseClamp = new DiodeNode();
      DiodeNode q2BaseEmitterReverseClamp = new DiodeNode();
      DiodeNode q1BaseCollectorForwardClamp = new DiodeNode();
      DiodeNode q2BaseCollectorForwardClamp = new DiodeNode();
      GroundNode ground = new GroundNode();

      addNode(0, battery);
      addNode(1, rc1);
      addNode(2, rc2);
      addNode(3, rb1);
      addNode(4, rb2);
      addNode(5, c1);
      addNode(6, c2);
      addNode(7, q1);
      addNode(8, q2);
      addNode(9, q1BaseEmitterReverseClamp);
      addNode(10, q2BaseEmitterReverseClamp);
      addNode(11, q1BaseCollectorForwardClamp);
      addNode(12, q2BaseCollectorForwardClamp);
      addNode(13, ground);

      connectBidirectional(battery, 0, rc1, 0);
      connectBidirectional(rc1, 0, rc2, 0);
      connectBidirectional(rc2, 0, rb1, 0);
      connectBidirectional(rb1, 0, rb2, 0);

      connectBidirectional(battery, 1, ground, 0);
      connectBidirectional(ground, 0, q1, 2);
      connectBidirectional(q1, 2, q2, 2);

      connectBidirectional(rc1, 1, q1, 1);
      connectBidirectional(q1, 1, c1, 0);

      connectBidirectional(rc2, 1, q2, 1);
      connectBidirectional(q2, 1, c2, 0);

      connectBidirectional(rb1, 1, q1, 0);
      connectBidirectional(q1, 0, c2, 1);

      connectBidirectional(rb2, 1, q2, 0);
      connectBidirectional(q2, 0, c1, 1);

      connectBidirectional(q1, 2, q1BaseEmitterReverseClamp, 0);
      connectBidirectional(q1BaseEmitterReverseClamp, 1, q1, 0);
      connectBidirectional(q2, 2, q2BaseEmitterReverseClamp, 0);
      connectBidirectional(q2BaseEmitterReverseClamp, 1, q2, 0);
      connectBidirectional(q1, 0, q1BaseCollectorForwardClamp, 0);
      connectBidirectional(q1BaseCollectorForwardClamp, 1, q1, 1);
      connectBidirectional(q2, 0, q2BaseCollectorForwardClamp, 0);
      connectBidirectional(q2BaseCollectorForwardClamp, 1, q2, 1);
    }

    private void addNode(int x, PowerNode node) {
      network.addNode(new BlockPos(x, 0, 0), node);
    }

    private static void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort) {
      a.addConnection(b, aPort, bPort, 1.0e-6);
      b.addConnection(a, bPort, aPort, 1.0e-6);
    }
  }
}
