package org.valkyrienskies.horizons.content.circuit

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Cell
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Pin
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Simulation
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType.Sprite
import org.valkyrienskies.horizons.api.circuits.CircuitComponents
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitCapacitor
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitGate
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitNmos
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeType
import org.valkyrienskies.horizons.potato_battery.api.persistence.PowerNodeTypes
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode
import java.util.function.Supplier

object BuiltinCircuitComponents {
    private fun pin(x: Int, y: Int, name: String) = Pin(x, y, "gui.horizons.circuit.pin.$name")
    private val twoPins = listOf(pin(0, 0, "first"), pin(1, 0, "second"))
    private val gatePins = listOf(pin(-1, -1, "input_a"), pin(-1, 1, "input_b"), pin(1, 0, "output"))
    private val body = listOf(Cell(0, 0))

    private fun register(name: String, item: Supplier<Item>, pins: List<Pin>, bodies: List<Cell>, simulation: Simulation) =
        CircuitComponents.register(CircuitComponentType(
            ResourceLocation.fromNamespaceAndPath("horizons", name), item, "item.horizons.circuit_$name", pins, bodies,
            Sprite(ResourceLocation.fromNamespaceAndPath("horizons", "textures/gui/circuit/$name.png"), 48, 48, 24, 24), simulation
        ))

    val resistor = register("resistor", { CircuitContent.RESISTOR.get() }, twoPins, emptyList()) { ResistorNode(1000.0) }
    val capacitor = register("capacitor", { CircuitContent.CAPACITOR.get() }, twoPins, emptyList(), object : Simulation {
        override fun createNode() = CircuitCapacitor()
        override fun saveState(node: IPowerNode) = CompoundTag().apply {
            putDouble("Voltage", (node as CircuitCapacitor).voltage)
        }
        override fun loadState(node: IPowerNode, state: CompoundTag) {
            require(state.contains("Voltage", Tag.TAG_DOUBLE.toInt())) { "Missing capacitor voltage" }
            (node as CircuitCapacitor).voltage = state.getDouble("Voltage")
        }
    })
    val nmos = register("nmos", { CircuitContent.NMOS.get() },
        listOf(pin(-1, 0, "gate"), pin(0, -1, "drain"), pin(0, 1, "source")), body) { CircuitNmos() }
    val and = register("and_gate", { CircuitContent.AND.get() }, gatePins, body) { CircuitGate(true) }
    val or = register("or_gate", { CircuitContent.OR.get() }, gatePins, body) { CircuitGate(false) }

    init {
        // Retain the original archive ID so existing worlds can restore their boards.
        PowerNodeTypes.register(PowerNodeType(ResourceLocation.fromNamespaceAndPath("potato_battery", "circuit_board"),
            CircuitBoardNode::class.java, 2,
            { version, tag ->
                require(version in 1..2) { "Unsupported circuit board version $version" }
                CircuitBoardNode.readState(tag)
            },
            { node, tag -> tag.merge(node.writeState()) }))
    }

    @JvmStatic fun initialize() = Unit

    internal fun migrateLegacyState(type: ResourceLocation, voltage: Double): CompoundTag {
        require(voltage.isFinite()) { "Invalid legacy component state" }
        return CompoundTag().apply {
            if (type == capacitor.id()) putDouble("Voltage", voltage)
            else require(voltage == 0.0) { "Unexpected legacy component state" }
        }
    }
}
