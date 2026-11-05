package org.valkyrienskies.horizons.potato_battery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ACVoltageSourceNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;

import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ACVoltageSourceTest {
  static Stream<IPBSolver> solvers() { return Stream.of(new EJMLSolver(), new JKLUSolver()); }

  private static PowerNetworkServer network(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    return network;
  }

  @ParameterizedTest @MethodSource("solvers")
  void sineWaveReversesVoltageAndCurrentAndDeliversExpectedRmsPower(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    ACVoltageSourceNode source = new ACVoltageSourceNode(12, 1);
    ResistorNode load = new ResistorNode(10);
    network.addNode(source);
    network.addNode(load);
    network.updateTopology(() -> {
      source.addConnection(load, 0, 0); load.addConnection(source, 0, 0);
      source.addConnection(load, 1, 1); load.addConnection(source, 1, 1);
    });
    double totalPower = 0;
    for (int tick = 1; tick <= 240; tick++) {
      network.energyTick(1.0 / 120);
      double expected = 12 * Math.sin(2 * Math.PI * tick / 120);
      assertEquals(expected, network.getVoltageBetween(source, 0, source, 1), 1e-5);
      assertEquals(expected / (10 + 2e-6), network.getNodeEnergyData(source, 0).getCurrent(), 1e-5);
      assertEquals(expected, source.getVoltage(), 1e-10);
      totalPower += load.getDissipatedPower(network);
    }
    assertEquals(12 * 12 / 2.0 / 10, totalPower / 240, 1e-4);
  }

  @ParameterizedTest @MethodSource("solvers")
  void phaseUsesElapsedSimulationTimeAndSurvivesWrappedCyclesWithoutSleeping(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    ACVoltageSourceNode source = new ACVoltageSourceNode(12, 1);
    network.addNode(source);
    // Four-second ticks sample identical phases in every capped substep. The clock must still run.
    for (int i = 0; i < 10; i++) network.energyTick(4);
    network.energyTick(1.0 / 120);
    assertEquals(12 * Math.sin(2 * Math.PI / 120), network.getVoltageBetween(source, 0, source, 1), 1e-6);
    network.energyTick(0.25);
    assertEquals(12 * Math.sin(2 * Math.PI * (0.25 + 1.0 / 120)), network.getVoltageBetween(source, 0, source, 1), 1e-6);
    assertTrue(source.getPhaseCycles() >= 0 && source.getPhaseCycles() < 1);
  }

  @ParameterizedTest @MethodSource("solvers")
  void frequencyAndInitialPhaseControlTheWaveform(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    ACVoltageSourceNode source = new ACVoltageSourceNode(5, 50, 0.25);
    network.addNode(source);
    double elapsed = 0;
    for (double dt : new double[] {0.003, 0.007, 0.011, 0.02, 0.1}) {
      network.energyTick(dt);
      elapsed += dt;
      assertEquals(5 * Math.sin(2 * Math.PI * (0.25 + elapsed * 50)), network.getVoltageBetween(source, 0, source, 1), 1e-6);
    }
  }

  @Test void rejectsInvalidParameters() {
    assertThrows(IllegalArgumentException.class, () -> new ACVoltageSourceNode(Double.NaN, 1));
    assertThrows(IllegalArgumentException.class, () -> new ACVoltageSourceNode(-12, 1));
    assertThrows(IllegalArgumentException.class, () -> new ACVoltageSourceNode(12, 0));
    assertThrows(IllegalArgumentException.class, () -> new ACVoltageSourceNode(12, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> new ACVoltageSourceNode(12, 1, 1));
  }
}
