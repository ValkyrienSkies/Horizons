package org.valkyrienskies.horizons.potato_battery;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.SwitchNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.VoltageSourceNode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DeviceCircuitTest {
  static Stream<IPBSolver> solvers() { return Stream.of(new EJMLSolver(), new JKLUSolver()); }

  @ParameterizedTest @MethodSource("solvers")
  void switchWakesSleepingCircuitAndControlsHundredWattLamp(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    VoltageSourceNode battery = new VoltageSourceNode(12);
    SwitchNode toggle = new SwitchNode(false);
    ResistorNode lamp = new ResistorNode(1.44);
    long batteryId = network.addNode(battery);
    long switchId = network.addNode(toggle);
    long lampId = network.addNode(lamp);
    assertNotEquals(batteryId, switchId);
    assertNotEquals(switchId, lampId);
    network.updateTopology(() -> {
      connect(battery, 0, toggle, 0);
      connect(toggle, 1, lamp, 0);
      connect(lamp, 1, battery, 1);
    });
    settle(network);
    assertSame(lamp, network.getNode(lampId));
    assertEquals(0, lamp.getDissipatedPower(network), 1e-5);
    network.updateTopology(() -> toggle.setClosed(true));
    settle(network);
    assertEquals(100, lamp.getDissipatedPower(network), 0.01);
    network.updateTopology(() -> toggle.setClosed(false));
    settle(network);
    assertEquals(0, lamp.getDissipatedPower(network), 1e-5);
    network.updateTopology(() -> toggle.setClosed(true));
    settle(network);
    network.removeNode(switchId);
    settle(network);
    assertNull(network.getNode(switchId));
    assertFalse(battery.isConnected(toggle));
    assertFalse(lamp.isConnected(toggle));
    assertEquals(0, lamp.getDissipatedPower(network), 1e-5);
  }

  @ParameterizedTest @MethodSource("solvers")
  void multipleCablesOnOnePortPowerParallelLoadsAndCanBeRemoved(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    VoltageSourceNode battery = new VoltageSourceNode(12);
    ResistorNode lamp = new ResistorNode(1.44);
    ResistorNode resistor = new ResistorNode(10);
    network.addNode(battery);
    network.addNode(lamp);
    network.addNode(resistor);
    network.updateTopology(() -> {
      connect(battery, 0, lamp, 0);
      connect(battery, 1, lamp, 1);
      connect(battery, 0, resistor, 0);
      connect(battery, 1, resistor, 1);
    });
    settle(network);
    assertEquals(100, lamp.getDissipatedPower(network), 0.01);
    assertEquals(14.4, resistor.getDissipatedPower(network), 0.01);
    network.updateTopology(() -> {
      battery.removeConnection(lamp, 0, 0);
      lamp.removeConnection(battery, 0, 0);
    });
    settle(network);
    assertEquals(0, lamp.getDissipatedPower(network), 1e-5);
    assertEquals(14.4, resistor.getDissipatedPower(network), 0.01);
    assertEquals(1.0 / 120, network.getTimeStepSeconds(), 1e-12);
  }

  private static void connect(IPowerNode a, int port, IPowerNode b, int otherPort) {
    a.addConnection(b, port, otherPort);
    b.addConnection(a, otherPort, port);
  }

  @ParameterizedTest @MethodSource("solvers")
  void seriesResistorReducesLampPowerInsteadOfBreakingTheCircuit(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    VoltageSourceNode battery = new VoltageSourceNode(12);
    ResistorNode resistor = new ResistorNode(10);
    ResistorNode lamp = new ResistorNode(1.44);
    network.addNode(battery);
    network.addNode(resistor);
    network.addNode(lamp);
    network.updateTopology(() -> {
      connect(battery, 0, resistor, 0);
      connect(resistor, 1, lamp, 0);
      connect(lamp, 1, battery, 1);
    });
    settle(network);

    double expectedCurrent = 12 / (10 + 1.44 + 3e-6);
    double lampVoltage = network.getVoltageAt(lamp, 0) - network.getVoltageAt(lamp, 1);
    assertEquals(expectedCurrent * 1.44, lampVoltage, 1e-4);
    assertEquals(expectedCurrent * expectedCurrent * 1.44, lamp.getDissipatedPower(network), 1e-4);
    assertEquals(expectedCurrent * expectedCurrent * 10, resistor.getDissipatedPower(network), 1e-4);
  }
  private static void settle(PowerNetworkServer network) {
    for (int i = 0; i < 5; i++) network.energyTick(1.0 / 120);
  }
}
