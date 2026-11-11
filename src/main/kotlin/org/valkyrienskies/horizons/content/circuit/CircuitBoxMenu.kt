package org.valkyrienskies.horizons.content.circuit

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.valkyrienskies.horizons.api.circuits.CircuitComponents
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire

class CircuitBoxMenu(id: Int, inventory: Inventory, val box: CircuitBoxBlockEntity) : AbstractContainerMenu(CircuitContent.MENU.get(), id) {
    constructor(id: Int, inventory: Inventory, pos: BlockPos) : this(id, inventory,
        requireNotNull(inventory.player.level().getBlockEntity(pos) as? CircuitBoxBlockEntity) { "Missing circuit box" })

    private val player = inventory.player
    private var previousPaper = box.paper.getItem(0).copy()
    private var paperEdited = false
    private var lastEditTick = Long.MIN_VALUE
    private var editsThisTick = 0
    val design get() = box.design

    init {
        addSlot(object : Slot(box.paper, 0, PAPER_X, PAPER_Y) {
            override fun mayPlace(stack: ItemStack) = stack.`is`(Items.PAPER) || stack.`is`(CircuitContent.SCHEMATIC.get())
            override fun getMaxStackSize() = 1
            override fun setChanged() {
                super.setChanged()
                paperEdited = true
            }
        })
        for (row in 0..2) for (column in 0..8)
            addSlot(Slot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18, INVENTORY_Y + row * 18))
        for (column in 0..8) addSlot(Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y))
    }

    override fun stillValid(player: Player) = !box.isRemoved && box.level == player.level() &&
        player.level().getBlockEntity(box.blockPos) === box &&
        player.distanceToSqr(box.blockPos.x + 0.5, box.blockPos.y + 0.5, box.blockPos.z + 0.5) <= 64

    override fun broadcastChanges() {
        if (!player.level().isClientSide && stillValid(player)) {
            if (paperEdited && !ItemStack.matches(previousPaper, box.paper.getItem(0)) && !player.isSpectator) {
                val paper = box.paper.getItem(0)
                if (paper.`is`(Items.PAPER)) printSchematic()
                else if (paper.`is`(CircuitContent.SCHEMATIC.get())) applySchematic()
            }
            paperEdited = false
            previousPaper = box.paper.getItem(0).copy()
        }
        super.broadcastChanges()
    }

    fun edit(action: Int, data: CompoundTag) {
        if (player.level().isClientSide || player.isSpectator || !stillValid(player)) return
        if (!data.contains("Revision", Tag.TAG_INT.toInt()) || data.getInt("Revision") != design.hashCode()) {
            error("message.horizons.circuit.changed")
            box.syncDevice()
            return
        }
        val tick = player.level().gameTime
        if (tick != lastEditTick) { lastEditTick = tick; editsThisTick = 0 }
        if (++editsThisTick > 16) return
        try {
            when (action) {
                PLACE -> place(Placed(data.getString("Type"), integer(data, "X"), integer(data, "Y"), integer(data, "Rotation")))
                REMOVE_COMPONENT -> removeComponent(integer(data, "Index"))
                ADD_WIRE -> box.updateDesign(design.withWire(Wire(
                    Point(integer(data, "FromX"), integer(data, "FromY")),
                    Point(integer(data, "ToX"), integer(data, "ToY")), integer(data, "Color"))))
                REMOVE_WIRE -> box.updateDesign(design.withoutWire(integer(data, "Index")))
                CLEAR -> clear()
                APPLY_SCHEMATIC -> applySchematic()
                else -> throw IllegalArgumentException("Unknown circuit action")
            }
        } catch (_: IllegalArgumentException) {
            error("message.horizons.circuit.invalid")
        } catch (_: IndexOutOfBoundsException) {
            error("message.horizons.circuit.invalid")
        }
        previousPaper = box.paper.getItem(0).copy()
        broadcastChanges()
    }

    private fun place(placed: Placed) {
        val next = design.withComponent(placed)
        if (CircuitComponents.find(carried)?.id() != placed.type) {
            error("message.horizons.circuit.pick_up", Component.translatable(placed.definition.translationKey()))
            broadcastFullState()
            return
        }
        box.updateDesign(next)
        if (!player.abilities.instabuild) {
            carried.shrink(1)
            if (carried.isEmpty) carried = ItemStack.EMPTY
        }
    }

    private fun removeComponent(index: Int) {
        val removed = design.components[index]
        box.updateDesign(design.withoutComponent(index))
        if (!player.abilities.instabuild) refund(removed.type, 1)
    }

    private fun clear() {
        val removed = design.components.groupingBy { it.type }.eachCount()
        box.updateDesign(CircuitDesign.empty())
        if (!player.abilities.instabuild) removed.forEach(::refund)
        feedback(Component.translatable("message.horizons.circuit.cleared"))
    }

    private fun count(type: ResourceLocation): Int {
        val item = CircuitComponents.get(type).item().get()
        return (0 until player.inventory.containerSize).sumOf { index ->
            player.inventory.getItem(index).let { if (it.`is`(item)) it.count else 0 }
        }
    }

    private fun consume(type: ResourceLocation, amount: Int) {
        val item = CircuitComponents.get(type).item().get()
        var remaining = amount
        for (index in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(index)
            if (!stack.`is`(item)) continue
            val taken = minOf(remaining, stack.count)
            stack.shrink(taken)
            remaining -= taken
            if (remaining == 0) break
        }
        player.inventory.setChanged()
    }

    private fun refund(type: ResourceLocation, amount: Int) {
        val item = CircuitComponents.get(type).item().get()
        var remaining = amount
        while (remaining > 0) {
            val stack = ItemStack(item)
            val taken = minOf(stack.maxStackSize, remaining)
            stack.count = taken
            if (!player.inventory.add(stack)) player.drop(stack, false)
            remaining -= taken
        }
        player.inventory.setChanged()
    }

    private fun printSchematic() {
        val schematic = ItemStack(CircuitContent.SCHEMATIC.get())
        schematic.orCreateTag.put("Design", design.write())
        box.paper.setItem(0, schematic)
        feedback(Component.translatable("message.horizons.circuit.printed"))
    }

    private fun applySchematic() {
        val schematic = box.paper.getItem(0)
        val tag = schematic.tag
        if (!schematic.`is`(CircuitContent.SCHEMATIC.get()) || tag == null || !tag.contains("Design", Tag.TAG_COMPOUND.toInt())) {
            error("message.horizons.circuit.schematic")
            return
        }
        val next = try { CircuitDesign.read(tag.getCompound("Design")) }
        catch (_: IllegalArgumentException) { error("message.horizons.circuit.schematic"); return }
        val balance = linkedMapOf<ResourceLocation, Int>()
        next.components.forEach { balance.merge(it.type, 1, Int::plus) }
        design.components.forEach { balance.merge(it.type, -1, Int::plus) }
        if (!player.abilities.instabuild) {
            for ((type, needed) in balance) {
                val missing = needed - count(type)
                if (missing > 0) {
                    error("message.horizons.circuit.missing_count", missing, Component.translatable(CircuitComponents.get(type).translationKey()))
                    return
                }
            }
        }
        box.updateDesign(next)
        if (!player.abilities.instabuild) {
            balance.filterValues { it > 0 }.forEach(::consume)
            balance.filterValues { it < 0 }.forEach { (type, needed) -> refund(type, -needed) }
        }
        feedback(Component.translatable("message.horizons.circuit.applied"))
    }

    private fun error(key: String, vararg args: Any) = feedback(Component.translatable(key, *args).withStyle(ChatFormatting.RED))
    private fun feedback(message: Component) {
        player.displayClientMessage(message, false)
        (player as? ServerPlayer)?.let { HorizonsNetworking.sendToClient(CircuitFeedbackPacket(containerId, message), it) }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val moved = when {
            index == 0 -> moveItemStackTo(stack, 1, slots.size, true)
            slots[0].mayPlace(stack) -> moveItemStackTo(stack, 0, 1, false)
            index < 28 -> moveItemStackTo(stack, 28, 37, false)
            else -> moveItemStackTo(stack, 1, 28, false)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        slot.onTake(player, stack)
        return original
    }

    companion object {
        const val WIDTH = 294
        const val HEIGHT = 354
        const val INVENTORY_X = 24
        const val INVENTORY_Y = 272
        const val HOTBAR_Y = 330
        const val PAPER_X = 241
        const val PAPER_Y = 100
        const val PLACE = 0
        const val REMOVE_COMPONENT = 1
        const val ADD_WIRE = 2
        const val REMOVE_WIRE = 3
        const val CLEAR = 4
        const val APPLY_SCHEMATIC = 5
        private fun integer(tag: CompoundTag, key: String): Int {
            require(tag.contains(key, Tag.TAG_INT.toInt())) { "Missing $key" }
            return tag.getInt(key)
        }
    }
}
