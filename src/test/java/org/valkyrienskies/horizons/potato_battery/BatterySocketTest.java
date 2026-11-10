package org.valkyrienskies.horizons.potato_battery;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeTypes;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.*;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BatterySocketTest {
  static Stream<IPBSolver> solvers() { return Stream.of(new EJMLSolver(), new JKLUSolver()); }
  @ParameterizedTest @MethodSource("solvers")
  void cellDrainsOnlyWhenConnectedAndCanMoveToAnotherSocket(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    BatterySocketNode dock = new BatterySocketNode();
    ResistorNode load = new ResistorNode(1.2);
    long id = network.addNode(dock);
    network.addNode(load);
    network.updateTopology(() -> {
      dock.addConnection(load, 0, 0); load.addConnection(dock, 0, 0);
      dock.addConnection(load, 1, 1); load.addConnection(dock, 1, 1);
    });
    FiniteBatteryNode cell = new FiniteBatteryNode(12, 10, 360_000);
    network.updateTopology(() -> dock.retain("42", cell));
    network.energyTick(0.05);
    assertEquals(0, load.getDissipatedPower(network), 1e-8);
    assertEquals(1, cell.getStateOfCharge());
    network.updateTopology(() -> dock.connect("42", cell));
    network.energyTick(1.0 / 120);
    assertEquals(5, network.getNodeEnergyData(dock, 0).getCurrent(), 1e-4);
    assertTrue(cell.getStateOfCharge() < 1);
    double charge = cell.getStateOfCharge();
    network.updateTopology(dock::disconnect);
    for (int i = 0; i < 10; i++) network.energyTick(0.05);
    assertEquals(0, load.getDissipatedPower(network), 1e-8);
    assertEquals(charge, cell.getStateOfCharge());
    assertSame(dock, network.getNode(id), "Removing the cell preserves the dock's node and cable identity");
    network.updateTopology(() -> dock.connect("42", cell));
    network.energyTick(0.05);
    assertTrue(cell.getStateOfCharge() < charge);
  }

  @ParameterizedTest @MethodSource("solvers")
  void restoredChargeRemainsOpenUntilPhysicalJointIsVerified(IPBSolver solver) {
    BatterySocketNode original = new BatterySocketNode();
    original.connect("93", new FiniteBatteryNode(12, 10, 360_000, 0.37));
    BatterySocketNode restored = (BatterySocketNode) PowerNodeTypes.decode(PowerNodeTypes.encode(original));
    assertEquals("93", restored.getBatteryId());
    assertEquals(0.37, restored.getBattery().getStateOfCharge());
    assertFalse(restored.isConnected());
    assertEquals(12, restored.getBattery().getMaxVoltage());
    restored.disconnect();
    assertNull(((BatterySocketNode) PowerNodeTypes.decode(PowerNodeTypes.encode(restored))).getBattery());
  }
}
