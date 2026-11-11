package org.valkyrienskies.horizons.content.circuit.simulation

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.valkyrienskies.horizons.content.circuit.BuiltinCircuitComponents
import org.valkyrienskies.horizons.content.circuit.CircuitDesign
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point
import org.valkyrienskies.horizons.potato_battery.api.network.node.CompositePowerNode
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer
import java.util.ArrayDeque

class CircuitBoardNode(design: CircuitDesign) : PowerNode(8), CompositePowerNode {
    private data class Board(val design: CircuitDesign, val components: List<IPowerNode>, val junctions: Map<Point, IPowerNode>) {
        val children: List<IPowerNode> = java.util.List.copyOf(components + junctions.values)
    }
    @Volatile private var board = build(design, emptyMap())
    @Volatile private var network: PowerNetworkServer? = null

    val design get() = board.design
    override fun getReferenceTraversalPorts(enteredPort: Int) = intArrayOf(enteredPort)
    override fun getChildren() = board.children

    fun setDesign(replacement: CircuitDesign) {
        val attached = network
        if (attached == null) board = build(replacement, retainedStates())
        else attached.withState {
            // Validate factories and states before detaching any live children.
            val next = build(replacement, retainedStates())
            attached.replaceCompositeChildren(this) { board = next }
        }
    }

    private fun retainedStates(): Map<Placed, ArrayDeque<CompoundTag>> {
        val retained = hashMapOf<Placed, ArrayDeque<CompoundTag>>()
        board.design.components.forEachIndexed { index, placed ->
            retained.getOrPut(placed) { ArrayDeque() }.add(
                placed.definition.simulation().saveState(board.components[index]).copy())
        }
        return retained
    }

    override fun onNetworkAttached(network: PowerNetworkServer) {
        this.network = network
        val current = board
        for (index in 0..7) connect(this, index, current.junctions.getValue(CircuitDesign.socket(index)), 0)
        current.design.components.forEachIndexed { index, placed ->
            CircuitDesign.pins(placed).forEachIndexed { port, pin ->
                connect(current.components[index], port, current.junctions.getValue(pin), 0)
            }
        }
        current.design.wires.forEach { wire ->
            connect(current.junctions.getValue(wire.from), 0, current.junctions.getValue(wire.to), 0)
        }
    }
    override fun onNetworkDetached() { network = null }

    fun writeState() = CompoundTag().apply {
        put("Design", board.design.write())
        put("ComponentStates", ListTag().apply {
            board.design.components.forEachIndexed { index, placed ->
                add(placed.definition.simulation().saveState(board.components[index]).copy())
            }
        })
    }

    companion object {
        private fun build(design: CircuitDesign, retained: Map<Placed, ArrayDeque<CompoundTag>>): Board {
            val components = design.components.map { placed ->
                placed.definition.createNode().also { node ->
                    retained[placed]?.poll()?.let { placed.definition.simulation().loadState(node, it) }
                }
            }
            val junctions = linkedMapOf<Point, IPowerNode>()
            fun junction(point: Point) { junctions.getOrPut(point) { object : PowerNode(1) {} } }
            (0..7).forEach { junction(CircuitDesign.socket(it)) }
            design.components.forEach { CircuitDesign.pins(it).forEach(::junction) }
            design.wires.forEach { junction(it.from); junction(it.to) }
            return Board(design, components, junctions)
        }
        private fun connect(first: IPowerNode, firstPort: Int, second: IPowerNode, secondPort: Int) {
            first.addConnection(second, firstPort, secondPort)
            second.addConnection(first, secondPort, firstPort)
        }
        @JvmStatic fun readState(tag: CompoundTag): CircuitBoardNode {
            require(tag.contains("Design", Tag.TAG_COMPOUND.toInt())) { "Missing circuit design" }
            val node = CircuitBoardNode(CircuitDesign.read(tag.getCompound("Design")))
            val states = tag.get("ComponentStates")
            require(states is ListTag && states.size == node.board.components.size &&
                (states.isEmpty() || states.elementType == Tag.TAG_COMPOUND || states.elementType == Tag.TAG_DOUBLE)) {
                "Invalid component states"
            }
            node.design.components.forEachIndexed { index, placed ->
                val state = if (states.elementType == Tag.TAG_DOUBLE)
                    BuiltinCircuitComponents.migrateLegacyState(placed.type, states.getDouble(index))
                else states.getCompound(index).copy()
                placed.definition.simulation().loadState(node.board.components[index], state)
            }
            return node
        }
    }
}
