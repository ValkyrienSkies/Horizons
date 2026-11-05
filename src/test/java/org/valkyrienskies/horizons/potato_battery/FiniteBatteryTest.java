package org.valkyrienskies.horizons.potato_battery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.FiniteBatteryNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.VoltageSourceNode;

import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class FiniteBatteryTest {
  static Stream<IPBSolver> solvers() { return Stream.of(new EJMLSolver(), new JKLUSolver()); }

  private static PowerNetworkServer network(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    return network;
  }

  private static void connect(PowerNetworkServer network, FiniteBatteryNode battery, ResistorNode load) {
    network.addNode(battery);
    network.addNode(load);
    network.updateTopology(() -> {
      battery.addConnection(load, 0, 0);
      load.addConnection(battery, 0, 0);
      battery.addConnection(load, 1, 1);
      load.addConnection(battery, 1, 1);
    });
  }

  @ParameterizedTest @MethodSource("solvers")
  void voltageIsLinearInChargeAndIdlePileDoesNotDrain(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    FiniteBatteryNode full = new FiniteBatteryNode(12, 0.5, 3600);
    FiniteBatteryNode half = new FiniteBatteryNode(12, 0.5, 3600, 0.5);
    network.addNode(full);
    network.addNode(half);
    for (int i = 0; i < 20; i++) network.energyTick(0.05);
    assertEquals(12, network.getVoltageBetween(full, 0, full, 1), 1e-5);
    assertEquals(6, network.getVoltageBetween(half, 0, half, 1), 1e-5);
    assertEquals(1, full.getStateOfCharge());
    assertEquals(0.5, half.getStateOfCharge());
    assertEquals(3600, full.getStoredEnergyJoules());
    assertEquals(900, half.getStoredEnergyJoules());
  }

  @ParameterizedTest @MethodSource("solvers")
  void voltageSagsUnderLoadAndShortCircuitCurrentIsLimited(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    FiniteBatteryNode battery = new FiniteBatteryNode(12, 0.5, 3600);
    ResistorNode load = new ResistorNode(24);
    connect(network, battery, load);
    network.energyTick(1.0 / 120);
    assertEquals(24, battery.getInternalResistance());
    assertEquals(0.25, network.getNodeEnergyData(battery, 0).getCurrent(), 1e-5);
    assertEquals(6, network.getVoltageBetween(battery, 0, battery, 1), 1e-4);

    PowerNetworkServer shorted = network(solver);
    FiniteBatteryNode shortedBattery = new FiniteBatteryNode(12, 0.5, 3600);
    connect(shorted, shortedBattery, new ResistorNode(1e-6));
    shorted.energyTick(1.0 / 120);
    double current = shorted.getNodeEnergyData(shortedBattery, 0).getCurrent();
    assertTrue(current > 0.499 && current <= shortedBattery.getMaxCurrent());
    assertTrue(shortedBattery.getStoredEnergyJoules() < 3600, "A short must consume reactants too");
  }

  @ParameterizedTest @MethodSource("solvers")
  void dischargesContinuouslyAndCannotDeliverMoreThanItsStoredEnergy(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    FiniteBatteryNode battery = new FiniteBatteryNode(12, 0.5, 0.6);
    ResistorNode load = new ResistorNode(24);
    connect(network, battery, load);
    double deliveredEnergy = 0;
    double previousCharge = 1;
    for (int i = 0; i < 1000; i++) {
      network.energyTick(0.01);
      deliveredEnergy += load.getDissipatedPower(network) * 0.01;
      assertTrue(battery.getStateOfCharge() <= previousCharge);
      assertTrue(battery.getStoredEnergyJoules() >= 0);
      previousCharge = battery.getStateOfCharge();
    }
    assertTrue(deliveredEnergy > 0.25 && deliveredEnergy < 0.3, "About half the energy goes to the matched load");
    assertTrue(battery.getStateOfCharge() < 1e-7, "A nearly settled circuit must not sleep before it discharges");
    assertTrue(load.getDissipatedPower(network) < 1e-12);
  }

  @ParameterizedTest @MethodSource("solvers")
  void emptyPileProducesNoPowerAndExternalVoltageCannotRechargeIt(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    FiniteBatteryNode empty = new FiniteBatteryNode(12, 0.5, 3600, 0);
    ResistorNode load = new ResistorNode(24);
    connect(network, empty, load);
    network.energyTick(0.05);
    assertEquals(0, load.getDissipatedPower(network), 1e-10);
    VoltageSourceNode charger = new VoltageSourceNode(24);
    network.addNode(charger);
    network.updateTopology(() -> {
      empty.addConnection(charger, 0, 0);
      charger.addConnection(empty, 0, 0);
      empty.addConnection(charger, 1, 1);
      charger.addConnection(empty, 1, 1);
    });
    for (int i = 0; i < 10; i++) network.energyTick(0.05);
    assertTrue(network.getNodeEnergyData(empty, 0).getCurrent() < 0);
    assertEquals(0, empty.getStateOfCharge());
    assertEquals(0, empty.getStoredEnergyJoules());
  }

  @ParameterizedTest @MethodSource("solvers")
  void largeTimeStepsDoNotCreateEnergyOrNegativeCharge(IPBSolver solver) {
    PowerNetworkServer network = network(solver);
    FiniteBatteryNode battery = new FiniteBatteryNode(12, 0.5, 0.6);
    ResistorNode load = new ResistorNode(24);
    connect(network, battery, load);
    network.energyTick(100);
    assertTrue(battery.getStateOfCharge() >= 0 && battery.getStateOfCharge() < 0.001);
    assertTrue(load.getDissipatedPower(network) * 100 < 0.6);
  }

  @Test void rejectsInvalidParameters() {
    assertThrows(IllegalArgumentException.class, () -> new FiniteBatteryNode(0, 0.5, 3600));
    assertThrows(IllegalArgumentException.class, () -> new FiniteBatteryNode(12, -1, 3600));
    assertThrows(IllegalArgumentException.class, () -> new FiniteBatteryNode(12, 0.5, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new FiniteBatteryNode(12, 0.5, 3600, 1.1));
  }
}
