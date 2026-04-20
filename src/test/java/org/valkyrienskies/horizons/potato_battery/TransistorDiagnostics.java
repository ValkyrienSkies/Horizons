package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NMOSTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.PMOSTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.PNPTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class TransistorDiagnostics {
  private static final double WIRE_RESISTANCE = 1.0e-6;

  private TransistorDiagnostics() {}

  public static void main(String[] args) throws IOException {
    int stepsPerPoint = Integer.getInteger("power.transistor.steps_per_point", 24);
    List<SolverSpec> solvers = selectedSolvers(System.getProperty("power.solver", "all"));
    Path outputDir = Path.of("build", "reports", "transistor-diagnostics");
    Files.createDirectories(outputDir);

    for (SolverSpec solver : solvers) {
      List<String> rows = new ArrayList<>();
      rows.add("scenario,bias,vbe_or_vsg,vce_or_vsd,ib_or_ig,ic_or_id,terminal0,terminal1,terminal2");
      rows.addAll(runNpnSweep(solver, stepsPerPoint));
      rows.addAll(runPnpSweep(solver, stepsPerPoint));
      rows.addAll(runNmosSweep(solver, stepsPerPoint));
      rows.addAll(runPmosSweep(solver, stepsPerPoint));

      Path output = outputDir.resolve(solver.name().toLowerCase(Locale.ROOT) + ".csv");
      Files.write(output, rows, StandardCharsets.UTF_8);
      System.out.println("Wrote " + output.toAbsolutePath());
    }
  }

  private static List<String> runNpnSweep(SolverSpec solver, int stepsPerPoint) {
    List<String> rows = new ArrayList<>();
    for (int step = 0; step <= 40; step++) {
      double baseSupply = step * 0.05;
      FixedVoltageNode vcc = new FixedVoltageNode(5.0);
      FixedVoltageNode vb = new FixedVoltageNode(baseSupply);
      ResistorNode rc = new ResistorNode(1.0e3);
      ResistorNode rb = new ResistorNode(100.0e3);
      NPNTransistorNode q = new NPNTransistorNode();
      GroundNode ground = new GroundNode();

      PowerNetworkServer network = new PowerNetworkServer(null, null, solver.factory().get());
      addNode(network, 0, vcc);
      addNode(network, 1, vb);
      addNode(network, 2, rc);
      addNode(network, 3, rb);
      addNode(network, 4, q);
      addNode(network, 5, ground);

      connectBidirectional(vcc, 0, rc, 0, WIRE_RESISTANCE);
      connectBidirectional(rc, 1, q, 1, WIRE_RESISTANCE);
      connectBidirectional(vb, 0, rb, 0, WIRE_RESISTANCE);
      connectBidirectional(rb, 1, q, 0, WIRE_RESISTANCE);
      connectBidirectional(q, 2, ground, 0, WIRE_RESISTANCE);

      settle(network, stepsPerPoint);

      double vbe = network.getVoltageAt(q, 0) - network.getVoltageAt(q, 2);
      double vce = network.getVoltageAt(q, 1) - network.getVoltageAt(q, 2);
      double ib = Math.abs(network.getVoltageAt(rb, 0) - network.getVoltageAt(rb, 1)) / 100.0e3;
      double ic = Math.abs(network.getVoltageAt(rc, 0) - network.getVoltageAt(rc, 1)) / 1.0e3;
      rows.add(csv(
          "npn",
          baseSupply,
          vbe,
          vce,
          ib,
          ic,
          network.getVoltageAt(q, 0),
          network.getVoltageAt(q, 1),
          network.getVoltageAt(q, 2)
      ));
    }
    return rows;
  }

  private static List<String> runPnpSweep(SolverSpec solver, int stepsPerPoint) {
    List<String> rows = new ArrayList<>();
    for (int step = 0; step <= 40; step++) {
      double baseSupply = 5.0 - step * 0.05;
      FixedVoltageNode vcc = new FixedVoltageNode(5.0);
      FixedVoltageNode vb = new FixedVoltageNode(baseSupply);
      ResistorNode rc = new ResistorNode(1.0e3);
      ResistorNode rb = new ResistorNode(100.0e3);
      PNPTransistorNode q = new PNPTransistorNode();
      GroundNode ground = new GroundNode();

      PowerNetworkServer network = new PowerNetworkServer(null, null, solver.factory().get());
      addNode(network, 0, vcc);
      addNode(network, 1, vb);
      addNode(network, 2, rc);
      addNode(network, 3, rb);
      addNode(network, 4, q);
      addNode(network, 5, ground);

      connectBidirectional(vcc, 0, q, 2, WIRE_RESISTANCE);
      connectBidirectional(q, 1, rc, 0, WIRE_RESISTANCE);
      connectBidirectional(rc, 1, ground, 0, WIRE_RESISTANCE);
      connectBidirectional(vb, 0, rb, 0, WIRE_RESISTANCE);
      connectBidirectional(rb, 1, q, 0, WIRE_RESISTANCE);

      settle(network, stepsPerPoint);

      double veb = network.getVoltageAt(q, 2) - network.getVoltageAt(q, 0);
      double vec = network.getVoltageAt(q, 2) - network.getVoltageAt(q, 1);
      double ib = Math.abs(network.getVoltageAt(rb, 0) - network.getVoltageAt(rb, 1)) / 100.0e3;
      double ic = Math.abs(network.getVoltageAt(rc, 0) - network.getVoltageAt(rc, 1)) / 1.0e3;
      rows.add(csv(
          "pnp",
          baseSupply,
          veb,
          vec,
          ib,
          ic,
          network.getVoltageAt(q, 0),
          network.getVoltageAt(q, 1),
          network.getVoltageAt(q, 2)
      ));
    }
    return rows;
  }

  private static List<String> runNmosSweep(SolverSpec solver, int stepsPerPoint) {
    List<String> rows = new ArrayList<>();
    for (int step = 0; step <= 100; step++) {
      double gateSupply = step * 0.05;
      FixedVoltageNode vcc = new FixedVoltageNode(5.0);
      FixedVoltageNode vg = new FixedVoltageNode(gateSupply);
      ResistorNode rd = new ResistorNode(1.0e3);
      NMOSTransistorNode m = new NMOSTransistorNode();
      GroundNode ground = new GroundNode();

      PowerNetworkServer network = new PowerNetworkServer(null, null, solver.factory().get());
      addNode(network, 0, vcc);
      addNode(network, 1, vg);
      addNode(network, 2, rd);
      addNode(network, 3, m);
      addNode(network, 4, ground);

      connectBidirectional(vcc, 0, rd, 0, WIRE_RESISTANCE);
      connectBidirectional(rd, 1, m, 0, WIRE_RESISTANCE);
      connectBidirectional(vg, 0, m, 1, WIRE_RESISTANCE);
      connectBidirectional(m, 2, ground, 0, WIRE_RESISTANCE);

      settle(network, stepsPerPoint);

      double vgs = network.getVoltageAt(m, 1) - network.getVoltageAt(m, 2);
      double vds = network.getVoltageAt(m, 0) - network.getVoltageAt(m, 2);
      double id = Math.abs(network.getVoltageAt(rd, 0) - network.getVoltageAt(rd, 1)) / 1.0e3;
      rows.add(csv(
          "nmos",
          gateSupply,
          vgs,
          vds,
          0.0,
          id,
          network.getVoltageAt(m, 0),
          network.getVoltageAt(m, 1),
          network.getVoltageAt(m, 2)
      ));
    }
    return rows;
  }

  private static List<String> runPmosSweep(SolverSpec solver, int stepsPerPoint) {
    List<String> rows = new ArrayList<>();
    for (int step = 0; step <= 100; step++) {
      double gateSupply = 5.0 - step * 0.05;
      FixedVoltageNode vcc = new FixedVoltageNode(5.0);
      FixedVoltageNode vg = new FixedVoltageNode(gateSupply);
      ResistorNode rd = new ResistorNode(1.0e3);
      PMOSTransistorNode m = new PMOSTransistorNode();
      GroundNode ground = new GroundNode();

      PowerNetworkServer network = new PowerNetworkServer(null, null, solver.factory().get());
      addNode(network, 0, vcc);
      addNode(network, 1, vg);
      addNode(network, 2, rd);
      addNode(network, 3, m);
      addNode(network, 4, ground);

      connectBidirectional(vcc, 0, m, 2, WIRE_RESISTANCE);
      connectBidirectional(m, 0, rd, 0, WIRE_RESISTANCE);
      connectBidirectional(rd, 1, ground, 0, WIRE_RESISTANCE);
      connectBidirectional(vg, 0, m, 1, WIRE_RESISTANCE);

      settle(network, stepsPerPoint);

      double vsg = network.getVoltageAt(m, 2) - network.getVoltageAt(m, 1);
      double vsd = network.getVoltageAt(m, 2) - network.getVoltageAt(m, 0);
      double id = Math.abs(network.getVoltageAt(rd, 0) - network.getVoltageAt(rd, 1)) / 1.0e3;
      rows.add(csv(
          "pmos",
          gateSupply,
          vsg,
          vsd,
          0.0,
          id,
          network.getVoltageAt(m, 0),
          network.getVoltageAt(m, 1),
          network.getVoltageAt(m, 2)
      ));
    }
    return rows;
  }

  private static void settle(PowerNetworkServer network, int stepsPerPoint) {
    for (int i = 0; i < stepsPerPoint; i++) {
      network.physTick();
    }
  }

  private static String csv(
      String scenario,
      double bias,
      double metricA,
      double metricB,
      double metricC,
      double metricD,
      double terminal0,
      double terminal1,
      double terminal2
  ) {
    return String.format(
        Locale.ROOT,
        "%s,%.6f,%.9f,%.9f,%.12f,%.12f,%.9f,%.9f,%.9f",
        scenario,
        bias,
        metricA,
        metricB,
        metricC,
        metricD,
        terminal0,
        terminal1,
        terminal2
    );
  }

  private static void addNode(PowerNetworkServer network, int x, PowerNode node) {
    network.addNode(new BlockPos(x, 0, 0), node);
  }

  private static void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
    a.addConnection(b, aPort, bPort, resistance);
    b.addConnection(a, bPort, aPort, resistance);
  }

  private static List<SolverSpec> selectedSolvers(String solverName) {
    String normalized = solverName.toLowerCase(Locale.ROOT);
    if ("ejml".equals(normalized)) {
      return List.of(new SolverSpec("EJML", EJMLSolver::new));
    }
    if ("jklu".equals(normalized)) {
      return List.of(new SolverSpec("JKLU", JKLUSolver::new));
    }
    return List.of(
        new SolverSpec("EJML", EJMLSolver::new),
        new SolverSpec("JKLU", JKLUSolver::new)
    );
  }

  private record SolverSpec(String name, Supplier<IPBSolver> factory) {}
}
