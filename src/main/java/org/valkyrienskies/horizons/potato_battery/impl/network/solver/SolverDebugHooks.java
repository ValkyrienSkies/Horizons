package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;

public final class SolverDebugHooks {
  private static volatile Listener listener;

  private SolverDebugHooks() {
  }

  public static void setListener(Listener newListener) {
    listener = newListener;
  }

  public static void clearListener() {
    listener = null;
  }

  static void report(
      IPowerNetwork<?> network,
      double timeStep,
      int iterationCount,
      boolean converged,
      double maxVoltageDelta,
      double maxCurrentDelta
  ) {
    Listener activeListener = listener;
    if (activeListener != null) {
      activeListener.onSolveReport(new SolveReport(timeStep, iterationCount, converged, maxVoltageDelta, maxCurrentDelta));
    }
  }

  public record SolveReport(
      double timeStep,
      int iterationCount,
      boolean converged,
      double maxVoltageDelta,
      double maxCurrentDelta
  ) {}

  @FunctionalInterface
  public interface Listener {
    void onSolveReport(SolveReport report);
  }
}
