package org.valkyrienskies.horizons.devices;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.valkyrienskies.horizons.impl.devices.persistence.PowerNetworkArchive;
import org.valkyrienskies.horizons.impl.devices.persistence.PowerNetworkSavedData;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeType;
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeTypes;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PowerNetworkArchiveTest {
  private static final BlockPos BATTERY = new BlockPos(15, 64, 0);
  private static final BlockPos LAMP = new BlockPos(16, 64, 0);
  static Stream<Supplier<IPBSolver>> solvers() { return Stream.of(EJMLSolver::new, JKLUSolver::new); }

  @ParameterizedTest @MethodSource("solvers")
  void restartKeepsIdsAndOnlyInstantiatesNodesInActivatedChunks(Supplier<IPBSolver> solver) throws Exception {
    PowerNetworkArchive original = archive(solver.get());
    var battery = original.attach(BATTERY, "test:battery", -1, () -> new VoltageSourceNode(12));
    var lamp = original.attach(LAMP, "test:lamp", -1, () -> new ResistorNode(1.44));
    original.connect(battery.id(), 0, lamp.id(), 0, 1e-6);
    original.connect(battery.id(), 1, lamp.id(), 1, 1e-6);
    CompoundTag saved = diskRoundtrip(original);
    assertEquals(2, saved.getList("Chunks", Tag.TAG_COMPOUND).size());
    assertTrue(saved.getList("Chunks", Tag.TAG_COMPOUND).getCompound(0).getByteArray("Payload").length > 0);

    PowerNetworkArchive restored = archive(solver.get());
    restored.read(saved);
    assertTrue(restored.getNetwork().getNodes().isEmpty());
    var attachedBattery = restored.attach(BATTERY, "test:battery", battery.id(), () -> { throw new AssertionError("Must fetch existing node"); });
    assertEquals(battery.id(), attachedBattery.id());
    assertEquals(1, restored.getNetwork().getNodes().size());
    assertNull(restored.getNetwork().getNode(lamp.id()));
    var attachedLamp = restored.attach(LAMP, "test:lamp", lamp.id(), () -> { throw new AssertionError("Must fetch existing node"); });
    assertEquals(lamp.id(), attachedLamp.id());
    settle(restored);
    assertEquals(100, ((ResistorNode) attachedLamp.node()).getDissipatedPower(restored.getNetwork()), 0.01);
    assertSame(attachedLamp.node(), restored.attach(LAMP, "test:lamp", lamp.id(), () -> new ResistorNode(10)).node());
    assertEquals(2, restored.getNetwork().getNodes().size());
    var next = restored.attach(new BlockPos(32, 64, 0), "test:new", -1, () -> new ResistorNode(5));
    assertTrue(next.id() > lamp.id());
  }

  @ParameterizedTest @MethodSource("solvers")
  void unloadingOpensTheCircuitAndReactivationDoesNotDuplicateCables(Supplier<IPBSolver> solver) {
    PowerNetworkArchive archive = archive(solver.get());
    var battery = archive.attach(BATTERY, "test:battery", -1, () -> new VoltageSourceNode(12));
    var lamp = archive.attach(LAMP, "test:lamp", -1, () -> new ResistorNode(1.44));
    archive.connect(battery.id(), 0, lamp.id(), 0, 1e-6);
    archive.connect(battery.id(), 1, lamp.id(), 1, 1e-6);
    for (int pass = 0; pass < 4; pass++) {
      archive.suspendChunk(PowerNetworkArchive.chunkOf(BATTERY));
      assertNull(archive.getNetwork().getNode(battery.id()));
      assertEquals(2, archive.getConnections(lamp.id()).size());
      settle(archive);
      assertEquals(0, ((ResistorNode) lamp.node()).getDissipatedPower(archive.getNetwork()), 1e-8);
      archive.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
      settle(archive);
      assertEquals(100, ((ResistorNode) lamp.node()).getDissipatedPower(archive.getNetwork()), 0.01);
      assertEquals(2, lamp.node().getConnections().size());
    }
  }

  @Test void deletingLoadedEndpointAlsoEditsUnloadedPeersWithoutActivatingThem() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var battery = archive.attach(BATTERY, "test:battery", -1, () -> new VoltageSourceNode(12));
    var lamp = archive.attach(LAMP, "test:lamp", -1, () -> new ResistorNode(1.44));
    archive.connect(battery.id(), 0, lamp.id(), 0, 1e-6);
    archive.suspendChunk(PowerNetworkArchive.chunkOf(LAMP));
    archive.removeNode(battery.id());
    assertTrue(archive.getNetwork().getNodes().isEmpty());
    assertFalse(archive.isChunkActive(PowerNetworkArchive.chunkOf(LAMP)));
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    assertTrue(restored.getConnections(lamp.id()).isEmpty());
    assertTrue(restored.getNetwork().getNode(lamp.id()).getConnections().isEmpty());
    assertNull(restored.getNetwork().getNode(battery.id()));
  }

  @Test void disconnectUpdatesUnloadedPeerRecords() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var a = archive.attach(BATTERY, "test:a", -1, () -> new ResistorNode(10));
    var b = archive.attach(LAMP, "test:b", -1, () -> new ResistorNode(10));
    archive.connect(a.id(), 0, b.id(), 1, 0.2);
    archive.suspendChunk(PowerNetworkArchive.chunkOf(LAMP));
    assertTrue(archive.disconnect(a.id(), 0, b.id(), 1));
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    restored.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    assertTrue(restored.getConnections(a.id()).isEmpty());
    assertTrue(restored.getConnections(b.id()).isEmpty());
  }

  @Test void fallbackPreservesUnclaimedIdsButCopiedDevicesGetFreshIdentities() {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var original = archive.attach(BATTERY, "test:resistor", 4000, () -> new ResistorNode(10));
    assertEquals(4000, original.id());
    var copy = archive.attach(LAMP, "test:resistor", 4000, () -> new ResistorNode(10));
    assertTrue(copy.id() > 4000);
    assertSame(original.node(), archive.getNetwork().getNode(4000));
    var corrected = archive.attach(BATTERY, "test:resistor", copy.id(), () -> new ResistorNode(20));
    assertEquals(original.id(), corrected.id());
    var replacement = archive.attach(BATTERY, "test:lamp", original.id(), () -> new ResistorNode(1.44));
    assertNotEquals(original.id(), replacement.id());
    assertNull(archive.getNetwork().getNode(original.id()));
  }

  @Test void switchStateAndNonDefaultResistanceSurviveRestart() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var toggle = archive.attach(BATTERY, "test:switch", -1, () -> new SwitchNode(false));
    var resistor = archive.attach(LAMP, "test:resistor", -1, () -> new ResistorNode(37.5));
    archive.getNetwork().updateTopology(() -> ((SwitchNode) toggle.node()).setClosed(true));
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    restored.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    assertTrue(((SwitchNode) restored.getNetwork().getNode(toggle.id())).isClosed());
    assertEquals(37.5, ((ResistorNode) restored.getNetwork().getNode(resistor.id())).getResistance());
  }

  @Test void unavailableNodeTypesKeepTheirOpaquePayload() throws Exception {
    PowerNetworkArchive source = archive(new EJMLSolver());
    var node = source.attach(BATTERY, "missing:addon_device", -1, () -> new ResistorNode(10));
    CompoundTag saved = diskRoundtrip(source);
    CompoundTag chunk = saved.getList("Chunks", Tag.TAG_COMPOUND).getCompound(0);
    CompoundTag payload = NbtIo.readCompressed(new ByteArrayInputStream(chunk.getByteArray("Payload")));
    payload.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).getCompound("Electrical").putString("Type", "missing:custom_node");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    NbtIo.writeCompressed(payload, buffer);
    chunk.putByteArray("Payload", buffer.toByteArray());
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(saved);
    restored.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
    assertNull(restored.getNetwork().getNode(node.id()));
    assertThrows(IllegalStateException.class, () -> restored.attach(BATTERY, "missing:addon_device", node.id(), () -> new ResistorNode(10)));
    assertArrayEquals(chunk.getByteArray("Payload"), diskRoundtrip(restored).getList("Chunks", Tag.TAG_COMPOUND).getCompound(0).getByteArray("Payload"));
  }

  @Test void customElectricalStateIsFrozenAcrossUnloadAndShipStorageCoordinatesAreStable() throws Exception {
    PowerNodeTypes.register(new PowerNodeType<>(ResourceLocation.fromNamespaceAndPath("archive_test", "stateful"), StatefulNode.class, 1,
        (version, tag) -> { StatefulNode node = new StatefulNode(); node.state = tag.getDouble("State"); return node; },
        (node, tag) -> tag.putDouble("State", node.state)));
    PowerNetworkArchive archive = archive(new EJMLSolver());
    BlockPos shipPosition = new BlockPos(28_000_003, 128, -100);
    var node = archive.attach(shipPosition, "test:custom", -1, StatefulNode::new);
    archive.getNetwork().updateTopology(() -> ((StatefulNode) node.node()).state = 24.5);
    archive.suspendChunk(PowerNetworkArchive.chunkOf(shipPosition));
    for (int i = 0; i < 10; i++) archive.getNetwork().energyTick(1.0 / 120);
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    var attached = restored.attach(shipPosition, "test:custom", node.id(), StatefulNode::new);
    assertEquals(node.id(), attached.id());
    assertEquals(24.5, ((StatefulNode) attached.node()).state);
  }

  @Test void snapshotWaitsForAnInProgressSolve() throws Exception {
    CountDownLatch solving = new CountDownLatch(1), release = new CountDownLatch(1), snapshotStarted = new CountDownLatch(1);
    PowerNetworkArchive archive = archive((network, steps) -> {
      solving.countDown();
      try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Solve was not released"); }
      catch (InterruptedException e) { throw new RuntimeException(e); }
    });
    archive.attach(BATTERY, "test:resistor", -1, () -> new ResistorNode(10));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> solve = workers.submit(() -> archive.getNetwork().energyTick(1.0 / 120));
      assertTrue(solving.await(3, TimeUnit.SECONDS));
      Future<?> snapshot = workers.submit(() -> { snapshotStarted.countDown(); archive.write(new CompoundTag()); });
      assertTrue(snapshotStarted.await(3, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> snapshot.get(100, TimeUnit.MILLISECONDS));
      release.countDown();
      solve.get(3, TimeUnit.SECONDS);
      snapshot.get(3, TimeUnit.SECONDS);
    } finally { release.countDown(); workers.shutdownNow(); }
  }

  @Test void unsupportedArchiveVersionIsRejected() {
    CompoundTag tag = new CompoundTag();
    tag.putInt("FormatVersion", 999);
    assertThrows(IllegalArgumentException.class, () -> archive(new EJMLSolver()).read(tag));
    PowerNetworkSavedData data = PowerNetworkSavedData.load(tag);
    assertFalse(data.isDirty());
    assertThrows(IllegalStateException.class, data::getArchive);
    assertThrows(IllegalStateException.class, () -> data.save(new CompoundTag()));
  }

  @Test void idAllocatorSurvivesWhenEveryNodeWasDeleted() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var first = archive.attach(BATTERY, "test:resistor", 8000, () -> new ResistorNode(10));
    archive.removeNode(first.id());
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    assertTrue(restored.getNetwork().getNodes().isEmpty());
    assertTrue(restored.attach(LAMP, "test:lamp", -1, () -> new ResistorNode(1.44)).id() > first.id());
  }

  @Test void ammeterTypeAndConnectionsSurviveRestart() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var battery = archive.attach(BATTERY, "test:battery", -1, () -> new VoltageSourceNode(12));
    var meter = archive.attach(new BlockPos(16, 64, 1), "test:meter", -1, AmmeterNode::new);
    var load = archive.attach(LAMP, "test:load", -1, () -> new ResistorNode(10));
    archive.connect(battery.id(), 0, meter.id(), 0, 1e-6);
    archive.connect(meter.id(), 1, load.id(), 0, 1e-6);
    archive.connect(load.id(), 1, battery.id(), 1, 1e-6);
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    restored.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    settle(restored);
    assertEquals(12 / (10 + 3e-6), ((AmmeterNode) restored.getNetwork().getNode(meter.id())).getCurrent(restored.getNetwork()), 1e-5);
  }

  @Test void entirelyUnloadedCircuitCanRestartWithChunksLoadedInReverseOrder() throws Exception {
    PowerNetworkArchive archive = archive(new EJMLSolver());
    var battery = archive.attach(BATTERY, "test:battery", -1, () -> new VoltageSourceNode(12));
    var lamp = archive.attach(LAMP, "test:lamp", -1, () -> new ResistorNode(1.44));
    archive.connect(battery.id(), 0, lamp.id(), 0, 1e-6);
    archive.connect(battery.id(), 1, lamp.id(), 1, 1e-6);
    archive.suspendAll();
    assertTrue(archive.getNetwork().getNodes().isEmpty());
    PowerNetworkArchive restored = archive(new EJMLSolver());
    restored.read(diskRoundtrip(archive));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    settle(restored);
    assertEquals(0, ((ResistorNode) restored.getNetwork().getNode(lamp.id())).getDissipatedPower(restored.getNetwork()), 1e-8);
    restored.activateChunk(PowerNetworkArchive.chunkOf(BATTERY));
    settle(restored);
    assertEquals(100, ((ResistorNode) restored.getNetwork().getNode(lamp.id())).getDissipatedPower(restored.getNetwork()), 0.01);
  }

  private static PowerNetworkArchive archive(IPBSolver solver) {
    PowerNetworkServer network = new PowerNetworkServer(solver);
    network.markLoaded();
    return new PowerNetworkArchive(network, () -> {});
  }

  @ParameterizedTest @MethodSource("solvers")
  void acSourceSettingsAndPhaseFreezeAcrossUnloadAndRestart(Supplier<IPBSolver> solver) throws Exception {
    PowerNetworkArchive original = archive(solver.get());
    var source = original.attach(BATTERY, "test:ac_source", -1, () -> new ACVoltageSourceNode(17, 2.5));
    for (int i = 0; i < 37; i++) original.getNetwork().energyTick(1.0 / 120);
    double phase = ((ACVoltageSourceNode) source.node()).getPhaseCycles();
    original.suspendChunk(PowerNetworkArchive.chunkOf(BATTERY));
    for (int i = 0; i < 100; i++) original.getNetwork().energyTick(0.05);
    PowerNetworkArchive restored = archive(solver.get());
    restored.read(diskRoundtrip(original));
    var attached = restored.attach(BATTERY, "test:ac_source", source.id(), () -> { throw new AssertionError("Must reuse phase"); });
    ACVoltageSourceNode node = (ACVoltageSourceNode) attached.node();
    assertEquals(source.id(), attached.id());
    assertEquals(phase, node.getPhaseCycles());
    assertEquals(17, node.getPeakVoltage());
    assertEquals(2.5, node.getFrequencyHz());
    restored.getNetwork().energyTick(0.01);
    assertEquals(17 * Math.sin(2 * Math.PI * (phase + 0.025)), restored.getNetwork().getVoltageBetween(node, 0, node, 1), 1e-6);
  }

  @ParameterizedTest @MethodSource("solvers")
  void finiteBatteryChargeFreezesWhenUnloadedAndSurvivesRestart(Supplier<IPBSolver> solver) throws Exception {
    PowerNetworkArchive original = archive(solver.get());
    var pile = original.attach(BATTERY, "test:voltaic_pile", -1, () -> new FiniteBatteryNode(12, 0.5, 3.6));
    var load = original.attach(LAMP, "test:load", -1, () -> new ResistorNode(24));
    original.connect(pile.id(), 0, load.id(), 0, 1e-6);
    original.connect(pile.id(), 1, load.id(), 1, 1e-6);
    for (int i = 0; i < 100; i++) original.getNetwork().energyTick(0.05);
    double charge = ((FiniteBatteryNode) pile.node()).getStateOfCharge();
    assertTrue(charge > 0 && charge < 0.5);
    original.suspendChunk(PowerNetworkArchive.chunkOf(BATTERY));
    for (int i = 0; i < 100; i++) original.getNetwork().energyTick(0.05);

    PowerNetworkArchive restored = archive(solver.get());
    restored.read(diskRoundtrip(original));
    restored.activateChunk(PowerNetworkArchive.chunkOf(LAMP));
    settle(restored);
    assertEquals(0, ((ResistorNode) restored.getNetwork().getNode(load.id())).getDissipatedPower(restored.getNetwork()), 1e-8);
    var attached = restored.attach(BATTERY, "test:voltaic_pile", pile.id(), () -> { throw new AssertionError("Must reuse saved charge"); });
    FiniteBatteryNode battery = (FiniteBatteryNode) attached.node();
    assertEquals(pile.id(), attached.id());
    assertEquals(charge, battery.getStateOfCharge());
    assertEquals(12, battery.getMaxVoltage());
    assertEquals(0.5, battery.getMaxCurrent());
    assertEquals(3.6, battery.getMaxEnergyJoules());
    settle(restored);
    assertTrue(battery.getStateOfCharge() < charge);
    assertTrue(((ResistorNode) restored.getNetwork().getNode(load.id())).getDissipatedPower(restored.getNetwork()) > 0);
  }
  private static CompoundTag diskRoundtrip(PowerNetworkArchive archive) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    NbtIo.writeCompressed(archive.write(new CompoundTag()), buffer);
    return NbtIo.readCompressed(new ByteArrayInputStream(buffer.toByteArray()));
  }
  private static void settle(PowerNetworkArchive archive) {
    for (int i = 0; i < 4; i++) archive.getNetwork().energyTick(1.0 / 120);
  }
  private static final class StatefulNode extends ResistorNode {
    double state;
    StatefulNode() { super(10); }
  }
}
