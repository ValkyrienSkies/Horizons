package org.valkyrienskies.horizons.potato_battery.impl.network.solver;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.NodeEnergyData;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

abstract class AbstractStampingSolver implements IPBSolver {
  private static final int MAX_NONLINEAR_ITERATIONS = 80;
  private static final double VOLTAGE_CONVERGENCE = 1.0e-6;
  private static final double CURRENT_CONVERGENCE = 1.0e-8;
  private static final double MIN_VOLTAGE_SCALE = 1.0;
  private static final double MIN_CURRENT_SCALE = 1.0e-3;
  private static final double MAX_RELATIVE_VOLTAGE_STEP = 0.75;
  private static final double MAX_RELATIVE_CURRENT_STEP = 1.5;
  private final Map<IPowerNetwork<?>, CachedTopologyEntry> topologyCache = new WeakHashMap<>();

  @Override
  public void step(IPowerNetwork<?> network, int subSteps) {
    runStep(network, subSteps, null);
  }

  final StepSolveFeedback stepWithFeedback(IPowerNetwork<?> network, int subSteps) {
    StepFeedbackAccumulator accumulator = new StepFeedbackAccumulator();
    runStep(network, subSteps, accumulator);
    return accumulator.finish();
  }

  final StepPhaseStats benchmarkStep(IPowerNetwork<?> network, int subSteps) {
    StepPhaseAccumulator accumulator = new StepPhaseAccumulator();
    runStep(network, subSteps, accumulator);
    return accumulator.finish();
  }

  private void runStep(IPowerNetwork<?> network, int subSteps, StepAccumulator accumulator) {
    Collection<IPowerNode> nodes = network.getNodes();
    if (nodes.isEmpty()) {
      network.clearNodeEnergyData();
      return;
    }

    long topologyStart = accumulator == null ? 0L : System.nanoTime();
    SolveTopology topology = getOrBuildTopology(network, nodes);
    if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
      phaseAccumulator.topologyNanos += System.nanoTime() - topologyStart;
      phaseAccumulator.nodeCount = topology.nodeTopologies.size();
      phaseAccumulator.branchCount = topology.branches.size();
      phaseAccumulator.unknownCount = topology.totalUnknowns;
    }
    if (topology.totalUnknowns == 0) {
      long writeBackStart = accumulator == null ? 0L : System.nanoTime();
      writeBackWithoutSolve(network, topology);
      if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
        phaseAccumulator.writeBackNanos += System.nanoTime() - writeBackStart;
      }
      return;
    }

    int steps = Math.max(subSteps, 1);
    double timeStep = network.getTimeStepSeconds() / steps;
    for (int subStep = 0; subStep < steps; subStep++) {
      if (accumulator != null) {
        accumulator.substeps++;
      }
      double[] solution = solveNonlinearSystem(network, topology, timeStep, accumulator);
      if (solution == null) {
        long writeBackStart = accumulator == null ? 0L : System.nanoTime();
        writeBackWithoutSolve(network, topology);
        if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
          phaseAccumulator.writeBackNanos += System.nanoTime() - writeBackStart;
        }
        return;
      }
      long writeBackStart = accumulator == null ? 0L : System.nanoTime();
      writeBack(network, topology, solution);
      if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
        phaseAccumulator.writeBackNanos += System.nanoTime() - writeBackStart;
      }
      for (NodeTopology nodeTopology : topology.nodeTopologies) {
        nodeTopology.node.onSubstepComplete(network, timeStep);
      }
    }
  }

  protected abstract double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs);

  private SolveTopology getOrBuildTopology(IPowerNetwork<?> network, Collection<IPowerNode> nodes) {
    if (network instanceof TopologyCacheableNetwork cacheableNetwork) {
      CachedTopologyEntry cached = topologyCache.get(network);
      long revision = cacheableNetwork.getTopologyRevision();
      if (cached != null && cached.revision == revision) {
        return cached.topology;
      }

      SolveTopology topology = SolveTopology.build(nodes);
      topologyCache.put(network, new CachedTopologyEntry(revision, topology));
      return topology;
    }

    return SolveTopology.build(nodes);
  }

  private double[] solveNonlinearSystem(
      IPowerNetwork<?> network,
      SolveTopology topology,
      double timeStep,
      StepAccumulator accumulator
  ) {
    double[] guess = createInitialGuess(network, topology);

    for (int iteration = 0; iteration < MAX_NONLINEAR_ITERATIONS; iteration++) {
      if (accumulator != null) {
        accumulator.nonlinearIterations++;
      }
      long stampStart = accumulator == null ? 0L : System.nanoTime();
      MatrixAccumulator matrix = new MatrixAccumulator(topology.totalUnknowns);
      double[] rhs = new double[topology.totalUnknowns];

      for (Branch branch : topology.branches) {
        double resistance = Math.max(branch.resistance, 1.0e-12);
        stampConductance(matrix, branch.aEquation, branch.bEquation, 1.0 / resistance);
      }

      for (NodeTopology nodeTopology : topology.nodeTopologies) {
        nodeTopology.node.stamp(new StampContextImpl(nodeTopology, matrix, rhs, timeStep, guess));
      }
      if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
        phaseAccumulator.stampNanos += System.nanoTime() - stampStart;
      }

      long solveStart = accumulator == null ? 0L : System.nanoTime();
      double[] candidate = solveLinearSystem(matrix, rhs.clone());
      if (accumulator instanceof StepPhaseAccumulator phaseAccumulator) {
        phaseAccumulator.solveNanos += System.nanoTime() - solveStart;
      }
      if (candidate == null || !isFinite(candidate)) {
        return null;
      }

      double maxVoltageDelta = 0.0;
      double maxCurrentDelta = 0.0;
      for (NodeTopology nodeTopology : topology.nodeTopologies) {
        for (int equation : nodeTopology.portEquations) {
          maxVoltageDelta = Math.max(maxVoltageDelta, Math.abs(candidate[equation] - guess[equation]));
        }
        for (int sourceIndex = 0; sourceIndex < nodeTopology.sourceCount; sourceIndex++) {
          int equation = nodeTopology.sourceEquationBase + sourceIndex;
          maxCurrentDelta = Math.max(maxCurrentDelta, Math.abs(candidate[equation] - guess[equation]));
        }
      }
      if (maxVoltageDelta <= VOLTAGE_CONVERGENCE && maxCurrentDelta <= CURRENT_CONVERGENCE) {
        return candidate;
      }

      guess = dampStep(guess, candidate, topology);
    }

    if (accumulator != null) {
      accumulator.iterationLimitHit = true;
    }
    return guess;
  }

  static final class StepSolveFeedback {
    private final int nonlinearIterations;
    private final int substeps;
    private final boolean iterationLimitHit;

    private StepSolveFeedback(int nonlinearIterations, int substeps, boolean iterationLimitHit) {
      this.nonlinearIterations = nonlinearIterations;
      this.substeps = substeps;
      this.iterationLimitHit = iterationLimitHit;
    }

    public int nonlinearIterations() {
      return nonlinearIterations;
    }

    public int substeps() {
      return substeps;
    }

    public boolean iterationLimitHit() {
      return iterationLimitHit;
    }
  }

  static final class StepPhaseStats {
    private final long topologyNanos;
    private final long stampNanos;
    private final long solveNanos;
    private final long writeBackNanos;
    private final int nonlinearIterations;
    private final int nodeCount;
    private final int branchCount;
    private final int unknownCount;
    private final int substeps;

    private StepPhaseStats(
        long topologyNanos,
        long stampNanos,
        long solveNanos,
        long writeBackNanos,
        int nonlinearIterations,
        int nodeCount,
        int branchCount,
        int unknownCount,
        int substeps
    ) {
      this.topologyNanos = topologyNanos;
      this.stampNanos = stampNanos;
      this.solveNanos = solveNanos;
      this.writeBackNanos = writeBackNanos;
      this.nonlinearIterations = nonlinearIterations;
      this.nodeCount = nodeCount;
      this.branchCount = branchCount;
      this.unknownCount = unknownCount;
      this.substeps = substeps;
    }

    long topologyNanos() {
      return topologyNanos;
    }

    long stampNanos() {
      return stampNanos;
    }

    long solveNanos() {
      return solveNanos;
    }

    long writeBackNanos() {
      return writeBackNanos;
    }

    int nonlinearIterations() {
      return nonlinearIterations;
    }

    int nodeCount() {
      return nodeCount;
    }

    int branchCount() {
      return branchCount;
    }

    int unknownCount() {
      return unknownCount;
    }

    int substeps() {
      return substeps;
    }

    long totalMeasuredNanos() {
      return topologyNanos + stampNanos + solveNanos + writeBackNanos;
    }
  }

  private abstract static class StepAccumulator {
    protected int nonlinearIterations;
    protected int substeps;
    protected boolean iterationLimitHit;
  }

  private static final class StepFeedbackAccumulator extends StepAccumulator {
    private StepSolveFeedback finish() {
      return new StepSolveFeedback(nonlinearIterations, substeps, iterationLimitHit);
    }
  }

  private static final class StepPhaseAccumulator extends StepAccumulator {
    private long topologyNanos;
    private long stampNanos;
    private long solveNanos;
    private long writeBackNanos;
    private int nodeCount;
    private int branchCount;
    private int unknownCount;

    private StepPhaseStats finish() {
      return new StepPhaseStats(
          topologyNanos,
          stampNanos,
          solveNanos,
          writeBackNanos,
          nonlinearIterations,
          nodeCount,
          branchCount,
          unknownCount,
          substeps
      );
    }
  }

  private record CachedTopologyEntry(long revision, SolveTopology topology) {}

  private static double[] createInitialGuess(IPowerNetwork<?> network, SolveTopology topology) {
    double[] guess = new double[topology.totalUnknowns];
    for (NodeTopology nodeTopology : topology.nodeTopologies) {
      for (int port = 0; port < nodeTopology.portEquations.length; port++) {
        guess[nodeTopology.portEquations[port]] = network.getVoltageAt(nodeTopology.node, port);
      }
    }
    return guess;
  }

  private static boolean isFinite(double[] vector) {
    for (double value : vector) {
      if (!Double.isFinite(value)) {
        return false;
      }
    }
    return true;
  }

  private static double[] dampStep(double[] previous, double[] candidate, SolveTopology topology) {
    double damping = 1.0;
    for (NodeTopology nodeTopology : topology.nodeTopologies) {
      for (int equation : nodeTopology.portEquations) {
        damping = Math.min(
            damping,
            relativeStepLimit(previous[equation], candidate[equation], MIN_VOLTAGE_SCALE, MAX_RELATIVE_VOLTAGE_STEP)
        );
      }
      for (int sourceIndex = 0; sourceIndex < nodeTopology.sourceCount; sourceIndex++) {
        int equation = nodeTopology.sourceEquationBase + sourceIndex;
        damping = Math.min(
            damping,
            relativeStepLimit(previous[equation], candidate[equation], MIN_CURRENT_SCALE, MAX_RELATIVE_CURRENT_STEP)
        );
      }
    }

    if (damping >= 1.0) {
      return candidate;
    }

    double[] damped = candidate.clone();
    for (int equation = 0; equation < damped.length; equation++) {
      damped[equation] = previous[equation] + (candidate[equation] - previous[equation]) * damping;
    }
    return damped;
  }

  private static double relativeStepLimit(double previousValue, double candidateValue, double minimumScale, double relativeLimit) {
    double delta = Math.abs(candidateValue - previousValue);
    if (delta <= 0.0) {
      return 1.0;
    }
    double scale = Math.max(Math.abs(previousValue), minimumScale);
    double allowedDelta = scale * relativeLimit;
    if (delta <= allowedDelta) {
      return 1.0;
    }
    return allowedDelta / delta;
  }

  private static void writeBackWithoutSolve(IPowerNetwork<?> network, SolveTopology topology) {
    for (NodeTopology nodeTopology : topology.nodeTopologies) {
      for (int port = 0; port < nodeTopology.portEquations.length; port++) {
        network.setNodeEnergyData(nodeTopology.node, port, new NodeEnergyData(0.0, 0.0, false));
      }
    }
  }

  private static void writeBack(IPowerNetwork<?> network, SolveTopology topology, double[] solution) {
    for (NodeTopology nodeTopology : topology.nodeTopologies) {
      double[] portCurrents = new double[nodeTopology.portEquations.length];

      for (Branch branch : topology.branches) {
        double branchCurrent = branch.current(solution);
        if (branch.owner == nodeTopology.node) {
          portCurrents[branch.ownerPort] += branchCurrent;
        }
        if (branch.other == nodeTopology.node) {
          portCurrents[branch.otherPort] -= branchCurrent;
        }
      }

      for (int port = 0; port < nodeTopology.portEquations.length; port++) {
        double voltage = voltageForEquation(solution, nodeTopology.portEquations[port]);
        network.setNodeEnergyData(nodeTopology.node, port, new NodeEnergyData(voltage, portCurrents[port], false));
      }
    }
  }

  protected static double voltageForEquation(double[] solution, int equation) {
    return equation < 0 ? 0.0 : solution[equation];
  }

  private static void stampConductance(MatrixAccumulator matrix, int aEquation, int bEquation, double conductance) {
    if (aEquation >= 0) {
      matrix.add(aEquation, aEquation, conductance);
    }
    if (bEquation >= 0) {
      matrix.add(bEquation, bEquation, conductance);
    }
    if (aEquation >= 0 && bEquation >= 0) {
      matrix.add(aEquation, bEquation, -conductance);
      matrix.add(bEquation, aEquation, -conductance);
    }
  }

  protected static final class MatrixAccumulator {
    private final int dimension;
    private final Long2DoubleOpenHashMap entries = new Long2DoubleOpenHashMap();

    private MatrixAccumulator(int dimension) {
      this.dimension = dimension;
      this.entries.defaultReturnValue(0.0);
    }

    int getDimension() {
      return dimension;
    }

    void add(int row, int column, double value) {
      long key = (((long) row) << 32) | (column & 0xffffffffL);
      entries.put(key, entries.get(key) + value);
    }

    List<MatrixEntry> sortedEntries() {
      List<MatrixEntry> sorted = new ArrayList<>(entries.size());
      for (Long2DoubleMap.Entry entry : entries.long2DoubleEntrySet()) {
        double value = entry.getDoubleValue();
        if (Math.abs(value) <= 1.0e-18) {
          continue;
        }

        long key = entry.getLongKey();
        sorted.add(new MatrixEntry((int) (key >>> 32), (int) key, value));
      }

      sorted.sort(Comparator.comparingInt(MatrixEntry::column).thenComparingInt(MatrixEntry::row));
      return sorted;
    }

    CscMatrix toCscMatrix() {
      List<MatrixEntry> sorted = sortedEntries();
      int[] columnPointers = new int[dimension + 1];
      int[] rowIndices = new int[sorted.size()];
      double[] values = new double[sorted.size()];

      int index = 0;
      for (int column = 0; column < dimension; column++) {
        columnPointers[column] = index;
        while (index < sorted.size() && sorted.get(index).column() == column) {
          MatrixEntry entry = sorted.get(index);
          rowIndices[index] = entry.row();
          values[index] = entry.value();
          index++;
        }
      }
      columnPointers[dimension] = sorted.size();

      return new CscMatrix(dimension, columnPointers, rowIndices, values);
    }
  }

  protected record CscMatrix(int dimension, int[] columnPointers, int[] rowIndices, double[] values) {}

  private record MatrixEntry(int row, int column, double value) {}

  private static final class StampContextImpl implements CircuitStampContext {
    private final NodeTopology topology;
    private final MatrixAccumulator matrix;
    private final double[] rhs;
    private final double timeStep;
    private final double[] stateVector;

    private StampContextImpl(NodeTopology topology, MatrixAccumulator matrix, double[] rhs, double timeStep, double[] stateVector) {
      this.topology = topology;
      this.matrix = matrix;
      this.rhs = rhs;
      this.timeStep = timeStep;
      this.stateVector = stateVector;
    }

    @Override
    public double getTimeStep() {
      return timeStep;
    }

    @Override
    public double getPreviousVoltage(int port) {
      return voltageForEquation(stateVector, equationForPort(port));
    }

    @Override
    public double getPreviousSourceCurrent(int sourceIndex) {
      if (sourceIndex < 0 || sourceIndex >= topology.sourceCount) {
        throw new IllegalArgumentException("Invalid voltage source index " + sourceIndex);
      }
      return stateVector[topology.sourceEquationBase + sourceIndex];
    }

    @Override
    public void stampConductance(int portA, int portB, double conductance) {
      AbstractStampingSolver.stampConductance(matrix, equationForPort(portA), equationForPort(portB), conductance);
    }

    @Override
    public void stampCurrentSource(int fromPort, int toPort, double current) {
      int fromEquation = equationForPort(fromPort);
      int toEquation = equationForPort(toPort);

      if (fromEquation >= 0) {
        rhs[fromEquation] -= current;
      }
      if (toEquation >= 0) {
        rhs[toEquation] += current;
      }
    }

    @Override
    public void stampVoltageSource(int sourceIndex, int positivePort, int negativePort, double voltage) {
      if (sourceIndex < 0 || sourceIndex >= topology.sourceCount) {
        throw new IllegalArgumentException("Invalid voltage source index " + sourceIndex);
      }

      int equation = topology.sourceEquationBase + sourceIndex;
      int positiveEquation = equationForPort(positivePort);
      int negativeEquation = equationForPort(negativePort);

      if (positiveEquation >= 0) {
        matrix.add(positiveEquation, equation, 1.0);
        matrix.add(equation, positiveEquation, 1.0);
      }
      if (negativeEquation >= 0) {
        matrix.add(negativeEquation, equation, -1.0);
        matrix.add(equation, negativeEquation, -1.0);
      }

      rhs[equation] += voltage;
    }

    @Override
    public void stampVCCS(int outPositive, int outNegative, int controlPositive, int controlNegative, double transconductance) {
      int outPositiveEquation = equationForPort(outPositive);
      int outNegativeEquation = equationForPort(outNegative);
      int controlPositiveEquation = equationForPort(controlPositive);
      int controlNegativeEquation = equationForPort(controlNegative);

      if (outPositiveEquation >= 0 && controlPositiveEquation >= 0) {
        matrix.add(outPositiveEquation, controlPositiveEquation, transconductance);
      }
      if (outPositiveEquation >= 0 && controlNegativeEquation >= 0) {
        matrix.add(outPositiveEquation, controlNegativeEquation, -transconductance);
      }
      if (outNegativeEquation >= 0 && controlPositiveEquation >= 0) {
        matrix.add(outNegativeEquation, controlPositiveEquation, -transconductance);
      }
      if (outNegativeEquation >= 0 && controlNegativeEquation >= 0) {
        matrix.add(outNegativeEquation, controlNegativeEquation, transconductance);
      }
    }

    @Override
    public void stampVCVS(int sourceIndex, int outPositive, int outNegative, int controlPositive, int controlNegative, double gain) {
      if (sourceIndex < 0 || sourceIndex >= topology.sourceCount) {
        throw new IllegalArgumentException("Invalid voltage source index " + sourceIndex);
      }

      int equation = topology.sourceEquationBase + sourceIndex;
      int outPositiveEquation = equationForPort(outPositive);
      int outNegativeEquation = equationForPort(outNegative);
      int controlPositiveEquation = equationForPort(controlPositive);
      int controlNegativeEquation = equationForPort(controlNegative);

      if (outPositiveEquation >= 0) {
        matrix.add(outPositiveEquation, equation, 1.0);
        matrix.add(equation, outPositiveEquation, 1.0);
      }
      if (outNegativeEquation >= 0) {
        matrix.add(outNegativeEquation, equation, -1.0);
        matrix.add(equation, outNegativeEquation, -1.0);
      }
      if (controlPositiveEquation >= 0) {
        matrix.add(equation, controlPositiveEquation, -gain);
      }
      if (controlNegativeEquation >= 0) {
        matrix.add(equation, controlNegativeEquation, gain);
      }
    }

    private int equationForPort(int port) {
      if (port == GROUND) {
        return -1;
      }
      if (port < 0 || port >= topology.portEquations.length) {
        throw new IllegalArgumentException("Invalid port " + port + " for node with " + topology.portEquations.length + " ports");
      }

      return topology.portEquations[port];
    }
  }

  protected static final class SolveTopology {
    private final List<NodeTopology> nodeTopologies;
    private final List<Branch> branches;
    private final int totalUnknowns;

    private SolveTopology(List<NodeTopology> nodeTopologies, List<Branch> branches, int totalUnknowns) {
      this.nodeTopologies = nodeTopologies;
      this.branches = branches;
      this.totalUnknowns = totalUnknowns;
    }

    private static SolveTopology build(Collection<IPowerNode> nodes) {
      IdentityHashMap<IPowerNode, Integer> nodeIds = new IdentityHashMap<>();
      List<NodeTopology> nodeTopologies = new ArrayList<>(nodes.size());
      int nextNodeId = 0;
      int nextEquation = 0;

      for (IPowerNode node : nodes) {
        nodeIds.put(node, nextNodeId++);
        int[] portEquations = new int[node.getPorts()];
        for (int port = 0; port < portEquations.length; port++) {
          portEquations[port] = nextEquation++;
        }

        int sourceBase = nextEquation;
        int sourceCount = Math.max(node.getVoltageSourceCount(), 0);
        nextEquation += sourceCount;
        nodeTopologies.add(new NodeTopology(node, portEquations, sourceBase, sourceCount));
      }

      List<Branch> branches = new ArrayList<>();
      HashSet<BranchKey> seenBranches = new HashSet<>();

      for (NodeTopology topology : nodeTopologies) {
        int ownerId = nodeIds.get(topology.node);
        for (int ownerPort = 0; ownerPort < topology.portEquations.length; ownerPort++) {
          for (var connection : topology.node.getConnections(ownerPort)) {
            Integer otherId = nodeIds.get(connection.node());
            if (otherId == null || connection.port() < 0 || connection.port() >= connection.node().getPorts()) {
              continue;
            }

            BranchKey key = canonicalBranchKey(ownerId, ownerPort, otherId, connection.port());
            if (!seenBranches.add(key)) {
              continue;
            }

            NodeTopology otherTopology = nodeTopologies.get(otherId);
            branches.add(new Branch(
                topology.node,
                ownerPort,
                topology.portEquations[ownerPort],
                otherTopology.node,
                connection.port(),
                otherTopology.portEquations[connection.port()],
                connection.resistance()
            ));
          }
        }
      }

      return new SolveTopology(nodeTopologies, branches, nextEquation);
    }

    private static BranchKey canonicalBranchKey(int ownerId, int ownerPort, int otherId, int otherPort) {
      long first = (((long) ownerId) << 32) | (ownerPort & 0xffffffffL);
      long second = (((long) otherId) << 32) | (otherPort & 0xffffffffL);
      return first <= second ? new BranchKey(first, second) : new BranchKey(second, first);
    }
  }

  protected static final class NodeTopology {
    private final IPowerNode node;
    private final int[] portEquations;
    private final int sourceEquationBase;
    private final int sourceCount;

    private NodeTopology(IPowerNode node, int[] portEquations, int sourceEquationBase, int sourceCount) {
      this.node = node;
      this.portEquations = portEquations;
      this.sourceEquationBase = sourceEquationBase;
      this.sourceCount = sourceCount;
    }
  }

  protected static final class Branch {
    private final IPowerNode owner;
    private final int ownerPort;
    private final int aEquation;
    private final IPowerNode other;
    private final int otherPort;
    private final int bEquation;
    private final double resistance;

    private Branch(IPowerNode owner, int ownerPort, int aEquation, IPowerNode other, int otherPort, int bEquation, double resistance) {
      this.owner = owner;
      this.ownerPort = ownerPort;
      this.aEquation = aEquation;
      this.other = other;
      this.otherPort = otherPort;
      this.bEquation = bEquation;
      this.resistance = resistance;
    }

    private double current(double[] solution) {
      double voltageA = voltageForEquation(solution, aEquation);
      double voltageB = voltageForEquation(solution, bEquation);
      return (voltageA - voltageB) / Math.max(resistance, 1.0e-12);
    }
  }

  private record BranchKey(long lowEndpoint, long highEndpoint) {}
}
