package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

public class EJMLSolver extends AbstractStampingSolver {
  @Override
  protected double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs) {
    return solveLinearSystemWithStats(matrix, rhs).solution();
  }

  @Override
  protected LinearSolveStats solveLinearSystemWithStats(MatrixAccumulator matrix, double[] rhs) {
    long cscStart = System.nanoTime();
    CscMatrix csc = matrix.toCscMatrix();
    long cscNanos = System.nanoTime() - cscStart;

    long factorStart = System.nanoTime();
    DMatrixSparseCSC sparseMatrix = new DMatrixSparseCSC(csc.dimension(), csc.dimension(), csc.values().length);
    sparseMatrix.col_idx = csc.columnPointers().clone();
    sparseMatrix.nz_rows = csc.rowIndices().clone();
    sparseMatrix.nz_values = csc.values().clone();
    sparseMatrix.indicesSorted = true;
    sparseMatrix.nz_length = csc.values().length;

    DMatrixRMaj rhsMatrix = new DMatrixRMaj(rhs.length, 1);
    for (int i = 0; i < rhs.length; i++) {
      rhsMatrix.set(i, 0, rhs[i]);
    }

    DMatrixRMaj solution = new DMatrixRMaj(rhs.length, 1);
    LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver = LinearSolverFactory_DSCC.lu(FillReducing.NONE);
    if (!solver.setA(sparseMatrix)) {
      return new LinearSolveStats(null, cscNanos, System.nanoTime() - factorStart, 0L, csc.values().length, false);
    }
    long factorNanos = System.nanoTime() - factorStart;

    long solveStart = System.nanoTime();
    solver.solve(rhsMatrix, solution);
    long solveNanos = System.nanoTime() - solveStart;

    double[] result = new double[rhs.length];
    for (int i = 0; i < result.length; i++) {
      result[i] = solution.get(i, 0);
    }
    return new LinearSolveStats(result, cscNanos, factorNanos, solveNanos, csc.values().length, false);
  }
}
