package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import edu.ufl.cise.klu.common.KLU_common;
import edu.ufl.cise.klu.common.KLU_numeric;
import edu.ufl.cise.klu.common.KLU_symbolic;
import edu.ufl.cise.klu.tdouble.Dklu_analyze;
import edu.ufl.cise.klu.tdouble.Dklu_defaults;
import edu.ufl.cise.klu.tdouble.Dklu_factor;
import edu.ufl.cise.klu.tdouble.Dklu_solve;

public class JKLUSolver extends AbstractStampingSolver {
  @Override
  protected double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs) {
    CscMatrix csc = matrix.toCscMatrix();
    KLU_common common = new KLU_common();
    if (Dklu_defaults.klu_defaults(common) == 0) {
      return null;
    }

    KLU_symbolic symbolic = Dklu_analyze.klu_analyze(csc.dimension(), csc.columnPointers(), csc.rowIndices(), common);
    if (symbolic == null) {
      return null;
    }

    KLU_numeric numeric = Dklu_factor.klu_factor(csc.columnPointers(), csc.rowIndices(), csc.values(), symbolic, common);
    if (numeric == null) {
      return null;
    }

    return Dklu_solve.klu_solve(symbolic, numeric, csc.dimension(), 1, rhs, 0, common) == 0 ? null : rhs;
  }
}
