package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;

public final class SolverPhaseDiagnostics {
  private SolverPhaseDiagnostics() {
  }

  public static SolveFeedback stepWithFeedback(IPBSolver solver, IPowerNetwork<?> network, int subSteps) {
    if (!(solver instanceof AbstractStampingSolver stampingSolver)) {
      solver.step(network, subSteps);
      return new SolveFeedback(0, Math.max(subSteps, 1), false, false);
    }
    AbstractStampingSolver.StepSolveFeedback feedback = stampingSolver.stepWithFeedback(network, subSteps);
    return new SolveFeedback(
        feedback.nonlinearIterations(),
        feedback.substeps(),
        feedback.iterationLimitHit(),
        feedback.solveFailed()
    );
  }

  public static PhaseBenchmarkResult benchmarkStep(IPBSolver solver, IPowerNetwork<?> network, int subSteps) {
    if (!(solver instanceof AbstractStampingSolver stampingSolver)) {
      throw new IllegalArgumentException("Unsupported solver type for phase diagnostics: " + solver.getClass().getName());
    }
    AbstractStampingSolver.StepPhaseStats stats = stampingSolver.benchmarkStep(network, subSteps);
    return new PhaseBenchmarkResult(
        stats.topologyNanos(),
        stats.stampNanos(),
        stats.cscNanos(),
        stats.factorNanos(),
        stats.solveNanos(),
        stats.writeBackNanos(),
        stats.nonlinearIterations(),
        stats.nodeCount(),
        stats.branchCount(),
        stats.unknownCount(),
        stats.substeps(),
        stats.totalMeasuredNanos(),
        stats.nonZeros(),
        stats.patternReuseCount()
    );
  }

  public record PhaseBenchmarkResult(
      long topologyNanos,
      long stampNanos,
      long cscNanos,
      long factorNanos,
      long solveNanos,
      long writeBackNanos,
      int nonlinearIterations,
      int nodeCount,
      int branchCount,
      int unknownCount,
      int substeps,
      long totalMeasuredNanos,
      long nonZeros,
      int patternReuseCount
  ) {}

  public record SolveFeedback(
      int nonlinearIterations,
      int substeps,
      boolean iterationLimitHit,
      boolean solveFailed
  ) {}
}
