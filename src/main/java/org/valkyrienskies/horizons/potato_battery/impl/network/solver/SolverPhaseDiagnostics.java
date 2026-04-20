package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;

public final class SolverPhaseDiagnostics {
  private SolverPhaseDiagnostics() {
  }

  public static PhaseBenchmarkResult benchmarkStep(IPBSolver solver, IPowerNetwork<?> network, int subSteps) {
    if (!(solver instanceof AbstractStampingSolver stampingSolver)) {
      throw new IllegalArgumentException("Unsupported solver type for phase diagnostics: " + solver.getClass().getName());
    }
    AbstractStampingSolver.StepPhaseStats stats = stampingSolver.benchmarkStep(network, subSteps);
    return new PhaseBenchmarkResult(
        stats.topologyNanos(),
        stats.stampNanos(),
        stats.solveNanos(),
        stats.writeBackNanos(),
        stats.nonlinearIterations(),
        stats.nodeCount(),
        stats.branchCount(),
        stats.unknownCount(),
        stats.substeps(),
        stats.totalMeasuredNanos()
    );
  }

  public record PhaseBenchmarkResult(
      long topologyNanos,
      long stampNanos,
      long solveNanos,
      long writeBackNanos,
      int nonlinearIterations,
      int nodeCount,
      int branchCount,
      int unknownCount,
      int substeps,
      long totalMeasuredNanos
  ) {}
}
