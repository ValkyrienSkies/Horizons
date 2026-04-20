package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import edu.ufl.cise.klu.common.KLU_common;
import edu.ufl.cise.klu.common.KLU_numeric;
import edu.ufl.cise.klu.common.KLU_symbolic;
import edu.ufl.cise.klu.tdouble.Dklu_analyze;
import edu.ufl.cise.klu.tdouble.Dklu_defaults;
import edu.ufl.cise.klu.tdouble.Dklu_factor;
import edu.ufl.cise.klu.tdouble.Dklu_refactor;
import edu.ufl.cise.klu.tdouble.Dklu_solve;

import java.util.Arrays;

public class JKLUSolver extends AbstractStampingSolver {
  private static final double JKLU_VOLTAGE_CONVERGENCE = 1.0e-5;
  private static final double JKLU_CURRENT_CONVERGENCE = 1.0e-7;
  private final KLU_common common = new KLU_common();
  private int cachedDimension = -1;
  private int[] cachedColumnPointers;
  private int[] cachedRowIndices;
  private KLU_symbolic cachedSymbolic;
  private KLU_numeric cachedNumeric;

  public JKLUSolver() {
    if (Dklu_defaults.klu_defaults(common) == 0) {
      throw new IllegalStateException("Failed to initialize JKLU defaults");
    }
  }

  @Override
  protected double voltageConvergence() {
    return JKLU_VOLTAGE_CONVERGENCE;
  }

  @Override
  protected double currentConvergence() {
    return JKLU_CURRENT_CONVERGENCE;
  }

  @Override
  protected synchronized double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs) {
    return solveLinearSystemWithStats(matrix, rhs).solution();
  }

  @Override
  protected synchronized LinearSolveStats solveLinearSystemWithStats(MatrixAccumulator matrix, double[] rhs) {
    long cscStart = System.nanoTime();
    CscMatrix csc = matrix.toCscMatrix();
    long cscNanos = System.nanoTime() - cscStart;

    LinearSolveStats cachedAttempt = solveWithCache(csc, rhs.clone(), cscNanos);
    return cachedAttempt.solution() != null ? cachedAttempt : solveFresh(csc, rhs, cscNanos);
  }

  private LinearSolveStats solveWithCache(CscMatrix csc, double[] rhs, long cscNanos) {
    boolean reusedPattern = matchesCachedPattern(csc);
    long factorStart = System.nanoTime();
    if (!matchesCachedPattern(csc)) {
      cachedSymbolic = Dklu_analyze.klu_analyze(csc.dimension(), csc.columnPointers(), csc.rowIndices(), common);
      if (cachedSymbolic == null) {
        cachedNumeric = null;
        return new LinearSolveStats(null, cscNanos, System.nanoTime() - factorStart, 0L, csc.values().length, false);
      }
      cachedNumeric = null;
      cachedDimension = csc.dimension();
      cachedColumnPointers = csc.columnPointers().clone();
      cachedRowIndices = csc.rowIndices().clone();
    }

    if (cachedNumeric == null) {
      cachedNumeric = Dklu_factor.klu_factor(csc.columnPointers(), csc.rowIndices(), csc.values(), cachedSymbolic, common);
    } else if (Dklu_refactor.klu_refactor(csc.columnPointers(), csc.rowIndices(), csc.values(), cachedSymbolic, cachedNumeric, common) == 0) {
      cachedNumeric = Dklu_factor.klu_factor(csc.columnPointers(), csc.rowIndices(), csc.values(), cachedSymbolic, common);
    }

    if (cachedNumeric == null) {
      invalidateCache();
      return new LinearSolveStats(null, cscNanos, System.nanoTime() - factorStart, 0L, csc.values().length, reusedPattern);
    }
    long factorNanos = System.nanoTime() - factorStart;

    long solveStart = System.nanoTime();
    if (Dklu_solve.klu_solve(cachedSymbolic, cachedNumeric, csc.dimension(), 1, rhs, 0, common) == 0) {
      invalidateCache();
      return new LinearSolveStats(null, cscNanos, factorNanos, System.nanoTime() - solveStart, csc.values().length, reusedPattern);
    }
    return new LinearSolveStats(rhs, cscNanos, factorNanos, System.nanoTime() - solveStart, csc.values().length, reusedPattern);
  }

  private LinearSolveStats solveFresh(CscMatrix csc, double[] rhs, long cscNanos) {
    KLU_common freshCommon = new KLU_common();
    if (Dklu_defaults.klu_defaults(freshCommon) == 0) {
      return new LinearSolveStats(null, cscNanos, 0L, 0L, csc.values().length, false);
    }

    long factorStart = System.nanoTime();
    KLU_symbolic symbolic = Dklu_analyze.klu_analyze(csc.dimension(), csc.columnPointers(), csc.rowIndices(), freshCommon);
    if (symbolic == null) {
      return new LinearSolveStats(null, cscNanos, System.nanoTime() - factorStart, 0L, csc.values().length, false);
    }

    KLU_numeric numeric = Dklu_factor.klu_factor(csc.columnPointers(), csc.rowIndices(), csc.values(), symbolic, freshCommon);
    if (numeric == null) {
      return new LinearSolveStats(null, cscNanos, System.nanoTime() - factorStart, 0L, csc.values().length, false);
    }
    long factorNanos = System.nanoTime() - factorStart;

    long solveStart = System.nanoTime();
    return Dklu_solve.klu_solve(symbolic, numeric, csc.dimension(), 1, rhs, 0, freshCommon) == 0
        ? new LinearSolveStats(null, cscNanos, factorNanos, System.nanoTime() - solveStart, csc.values().length, false)
        : new LinearSolveStats(rhs, cscNanos, factorNanos, System.nanoTime() - solveStart, csc.values().length, false);
  }

  private boolean matchesCachedPattern(CscMatrix csc) {
    return cachedSymbolic != null
        && cachedDimension == csc.dimension()
        && Arrays.equals(cachedColumnPointers, csc.columnPointers())
        && Arrays.equals(cachedRowIndices, csc.rowIndices());
  }

  private void invalidateCache() {
    cachedDimension = -1;
    cachedColumnPointers = null;
    cachedRowIndices = null;
    cachedSymbolic = null;
    cachedNumeric = null;
  }
}
