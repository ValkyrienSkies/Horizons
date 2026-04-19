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
import java.util.IdentityHashMap;
import java.util.List;

abstract class AbstractStampingSolver implements IPBSolver {
  @Override
  public void step(IPowerNetwork<?> network, int subSteps) {
    Collection<IPowerNode> nodes = network.getNodes();
    if (nodes.isEmpty()) {
      network.clearNodeEnergyData();
      return;
    }

    double timeStep = network.getTimeStepSeconds() / Math.max(subSteps, 1);
    SolveTopology topology = SolveTopology.build(nodes);
    if (topology.totalUnknowns == 0) {
      network.clearNodeEnergyData();
      writeBackWithoutSolve(network, topology);
      return;
    }

    MatrixAccumulator matrix = new MatrixAccumulator(topology.totalUnknowns);
    double[] rhs = new double[topology.totalUnknowns];

    for (Branch branch : topology.branches) {
      double resistance = Math.max(branch.resistance, 1.0e-12);
      stampConductance(matrix, branch.aEquation, branch.bEquation, 1.0 / resistance);
    }

    for (NodeTopology nodeTopology : topology.nodeTopologies) {
      nodeTopology.node.stamp(new StampContextImpl(network, nodeTopology, matrix, rhs, timeStep));
    }

    network.clearNodeEnergyData();
    double[] solution = solveLinearSystem(matrix, rhs.clone());
    if (solution == null) {
      writeBackWithoutSolve(network, topology);
      return;
    }

    writeBack(network, topology, solution);
  }

  protected abstract double[] solveLinearSystem(MatrixAccumulator matrix, double[] rhs);

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
    private final IPowerNetwork<?> network;
    private final NodeTopology topology;
    private final MatrixAccumulator matrix;
    private final double[] rhs;
    private final double timeStep;

    private StampContextImpl(IPowerNetwork<?> network, NodeTopology topology, MatrixAccumulator matrix, double[] rhs, double timeStep) {
      this.network = network;
      this.topology = topology;
      this.matrix = matrix;
      this.rhs = rhs;
      this.timeStep = timeStep;
    }

    @Override
    public double getTimeStep() {
      return timeStep;
    }

    @Override
    public double getPreviousVoltage(int port) {
      return network.getVoltageAt(topology.node, port);
    }

    @Override
    public double getPreviousSourceCurrent(int sourceIndex) {
      return 0.0;
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
      Long2DoubleOpenHashMap seenBranches = new Long2DoubleOpenHashMap();
      seenBranches.defaultReturnValue(Double.NaN);

      for (NodeTopology topology : nodeTopologies) {
        int ownerId = nodeIds.get(topology.node);
        for (int ownerPort = 0; ownerPort < topology.portEquations.length; ownerPort++) {
          for (var connection : topology.node.getConnections(ownerPort)) {
            Integer otherId = nodeIds.get(connection.node());
            if (otherId == null || connection.port() < 0 || connection.port() >= connection.node().getPorts()) {
              continue;
            }

            long key = canonicalBranchKey(ownerId, ownerPort, otherId, connection.port());
            if (!Double.isNaN(seenBranches.get(key))) {
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
            seenBranches.put(key, connection.resistance());
          }
        }
      }

      return new SolveTopology(nodeTopologies, branches, nextEquation);
    }

    private static long canonicalBranchKey(int ownerId, int ownerPort, int otherId, int otherPort) {
      long first = (((long) ownerId) << 32) | (ownerPort & 0xffffffffL);
      long second = (((long) otherId) << 32) | (otherPort & 0xffffffffL);
      long low = Math.min(first, second);
      long high = Math.max(first, second);
      return low ^ Long.rotateLeft(high, 32);
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
}
