package org.valkyrienskies.horizons.content.circuit

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.horizons.api.circuits.CircuitComponents

class CircuitDesign(components: List<Placed>, wires: List<Wire>) {
    val components: List<Placed> = java.util.List.copyOf(components)
    val wires: List<Wire> = java.util.List.copyOf(wires)

    data class Point(val x: Int, val y: Int)
    data class Placed(val type: ResourceLocation, val x: Int, val y: Int, val rotation: Int) {
        constructor(type: String, x: Int, y: Int, rotation: Int) : this(componentId(type), x, y, rotation)
        val definition get() = CircuitComponents.get(type)
    }
    data class Wire(val from: Point, val to: Point, val color: Int)

    init {
        require(components.size <= MAX_COMPONENTS && wires.size <= MAX_WIRES) { "Invalid design size" }
        val occupiedBodies = hashSetOf<Point>()
        val occupiedPins = hashSetOf<Point>()
        for (component in this.components) {
            validateComponent(component)
            for (body in bodies(component)) {
                require(inGrid(body) && occupiedBodies.add(body)) { "Overlapping/outside body" }
            }
            for (pin in pins(component)) {
                require(inGrid(pin)) { "Pin outside grid" }
                occupiedPins.add(pin)
            }
        }
        require(occupiedBodies.none { it in occupiedPins }) { "Body overlaps pin" }
        val edges = hashSetOf<Set<Point>>()
        for (wire in this.wires) {
            require(endpoint(wire.from) && endpoint(wire.to) && wire.from != wire.to && wire.color in 0..7 &&
                wire.from !in occupiedBodies && wire.to !in occupiedBodies) { "Invalid wire" }
            require(edges.add(setOf(wire.from, wire.to))) { "Duplicate wire" }
        }
    }

    fun withComponent(placed: Placed) = CircuitDesign(components + placed, wires)
    fun withoutComponent(index: Int): CircuitDesign {
        require(index in components.indices) { "Invalid component index" }
        return CircuitDesign(components.filterIndexed { i, _ -> i != index }, wires)
    }
    fun withWire(wire: Wire) = CircuitDesign(components, wires + wire)
    fun withoutWire(index: Int): CircuitDesign {
        require(index in wires.indices) { "Invalid wire index" }
        return CircuitDesign(components, wires.filterIndexed { i, _ -> i != index })
    }

    fun write() = CompoundTag().apply {
        put("Components", ListTag().apply {
            components.forEach { placed ->
                add(CompoundTag().apply {
                    putString("Type", placed.type.toString())
                    putInt("X", placed.x)
                    putInt("Y", placed.y)
                    putInt("Rotation", placed.rotation)
                })
            }
        })
        put("Wires", ListTag().apply {
            wires.forEach { wire ->
                add(CompoundTag().apply {
                    putInt("FromX", wire.from.x)
                    putInt("FromY", wire.from.y)
                    putInt("ToX", wire.to.x)
                    putInt("ToY", wire.to.y)
                    putInt("Color", wire.color)
                })
            }
        })
    }

    override fun equals(other: Any?) = other is CircuitDesign && components == other.components && wires == other.wires
    override fun hashCode() = 31 * components.hashCode() + wires.hashCode()

    companion object {
        const val SIZE = 11
        const val MAX_COMPONENTS = 121
        const val MAX_WIRES = 512

        // Designs written before component registration used unqualified Horizons IDs.
        private fun componentId(type: String): ResourceLocation =
            requireNotNull(ResourceLocation.tryParse(if (':' in type) type else "horizons:$type")) { "Invalid component ID" }

        private fun validateComponent(placed: Placed) {
            require(placed.rotation in 0..3 && inGrid(Point(placed.x, placed.y))) { "Invalid component" }
            CircuitComponents.get(placed.type)
        }

        @JvmStatic fun pins(placed: Placed): List<Point> {
            validateComponent(placed)
            return placed.definition.pins().map { transform(placed, it.x(), it.y()) }
        }
        @JvmStatic fun bodies(placed: Placed): List<Point> {
            validateComponent(placed)
            return placed.definition.bodies().map { transform(placed, it.x(), it.y()) }
        }
        private fun transform(placed: Placed, dx: Int, dy: Int) = when (placed.rotation) {
            0 -> Point(placed.x + dx, placed.y + dy)
            1 -> Point(placed.x - dy, placed.y + dx)
            2 -> Point(placed.x - dx, placed.y - dy)
            else -> Point(placed.x + dy, placed.y - dx)
        }

        @JvmStatic fun socket(index: Int): Point {
            require(index in 0..7) { "Invalid socket" }
            return Point(intArrayOf(9, 6, 4, 1)[index % 4], if (index < 4) -1 else SIZE)
        }
        fun inGrid(point: Point) = point.x in 0 until SIZE && point.y in 0 until SIZE
        fun endpoint(point: Point) = inGrid(point) || (0..7).any { socket(it) == point }
        @JvmStatic fun empty() = CircuitDesign(emptyList(), emptyList())

        @JvmStatic fun read(tag: CompoundTag): CircuitDesign {
            val parts = list(tag, "Components", MAX_COMPONENTS).map {
                val part = it as CompoundTag
                require(part.contains("Type", Tag.TAG_STRING.toInt())) { "Missing component type" }
                Placed(part.getString("Type"), integer(part, "X"), integer(part, "Y"), integer(part, "Rotation"))
            }
            val edges = list(tag, "Wires", MAX_WIRES).map {
                val edge = it as CompoundTag
                Wire(Point(integer(edge, "FromX"), integer(edge, "FromY")),
                    Point(integer(edge, "ToX"), integer(edge, "ToY")), integer(edge, "Color"))
            }
            return CircuitDesign(parts, edges)
        }
        private fun list(tag: CompoundTag, key: String, limit: Int): ListTag {
            val list = tag.get(key)
            require(list is ListTag && list.size <= limit &&
                (list.isEmpty() || list.elementType == Tag.TAG_COMPOUND)) { "Invalid $key" }
            return list
        }
        private fun integer(tag: CompoundTag, key: String): Int {
            require(tag.contains(key, Tag.TAG_INT.toInt())) { "Missing integer $key" }
            return tag.getInt(key)
        }
    }
}
