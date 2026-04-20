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
  protected synchronized double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs) {
    CscMatrix csc = matrix.toCscMatrix();
    double[] cachedAttempt = solveWithCache(csc, rhs.clone());
    return cachedAttempt != null ? cachedAttempt : solveFresh(csc, rhs);
  }

  private double[] solveWithCache(CscMatrix csc, double[] rhs) {
    if (!matchesCachedPattern(csc)) {
      cachedSymbolic = Dklu_analyze.klu_analyze(csc.dimension(), csc.columnPointers(), csc.rowIndices(), common);
      if (cachedSymbolic == null) {
        cachedNumeric = null;
        return null;
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
      return null;
    }

    if (Dklu_solve.klu_solve(cachedSymbolic, cachedNumeric, csc.dimension(), 1, rhs, 0, common) == 0) {
      invalidateCache();
      return null;
    }
    return rhs;
  }

  private double[] solveFresh(CscMatrix csc, double[] rhs) {
    KLU_common freshCommon = new KLU_common();
    if (Dklu_defaults.klu_defaults(freshCommon) == 0) {
      return null;
    }

    KLU_symbolic symbolic = Dklu_analyze.klu_analyze(csc.dimension(), csc.columnPointers(), csc.rowIndices(), freshCommon);
    if (symbolic == null) {
      return null;
    }

    KLU_numeric numeric = Dklu_factor.klu_factor(csc.columnPointers(), csc.rowIndices(), csc.values(), symbolic, freshCommon);
    if (numeric == null) {
      return null;
    }

    return Dklu_solve.klu_solve(symbolic, numeric, csc.dimension(), 1, rhs, 0, freshCommon) == 0 ? null : rhs;
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
