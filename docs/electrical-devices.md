# Electrical devices

The Redstone creative tab contains the Battery, Switch, Resistor, Lamp, and Debug
Cable. Aim at a socket while holding the cable, right-click it, and then
right-click another socket. Each socket accepts multiple cables. Selecting the
same socket twice clears the selection; selecting an existing connected pair
removes that cable.

Connections can be created within 10 world-space blocks, including between ships
and terrain. Existing cables remain connected as ships move beyond this range.
Orange lines show cables; socket outlines appear on the block being targeted,
with the selected target highlighted yellow.

The battery is an unlimited ideal 12 V source. The resistor is 10 ohms. The lamp
is a 1.44 ohm load whose emitted block-light level scales linearly to 15 at
100 W. Right-click the switch without a cable to open or close its internal
electrical path. Battery and switch use the existing models; resistor and lamp
use temporary vanilla-based models.

## Addon API

- Extend the Java `horizons.api.devices.DeviceBlockEntity`, or implement `IDevice`
  and delegate its lifecycle methods from your own block entity.
- Supply a `DeviceState`, a solver node from `createPowerNode()`, and sockets from
  `createSockets()`. A socket's port number must exist on its solver node.
- `StandardSocket` handles symmetric wiring, world-space distance checks, client
  connection updates, and a basic debug outline. Its bounds and position are
  block-local; rotate them with the block's facing when constructing sockets.
- Registration happens on server `onLoad`, when the level is available. Node IDs
  are allocated immediately and are local to that level's network. Graph changes
  take effect between solves on the simulation thread.
- Use `IPowerNetwork.updateTopology(...)` when changing a registered node or its
  wiring. The callback must only access electrical state, never Minecraft world
  state. The callback also wakes sleeping networks and invalidates topology.
- Call `removeDevice()` when permanently replacing/removing a device. The base
  block entity handles replacement removal; the built-in blocks also call it
  before their block entity is removed.
- Solver nodes and electrical equations belong in Java under `potato_battery`.
  Minecraft integration and socket APIs belong under Horizons; the built-in
  content and debug cable item are Kotlin.

The server simulates at 120 steps per second. Lamps sample published results on
the Minecraft tick thread; rendering uses client ship render transforms.

Only `PowerNodeId` is persisted in block-entity NBT. Connection descriptions are
sent to tracking clients, but the graph is not saved. Saved IDs alone cannot
restore nodes after a restart; new registrations receive fresh IDs. Graph
serialization, chunk unload/reload behavior, and automatic cable breaking are
deferred.
