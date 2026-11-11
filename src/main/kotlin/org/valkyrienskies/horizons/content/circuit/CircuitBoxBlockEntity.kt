package org.valkyrienskies.horizons.content.circuit

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Matrix4d
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.horizons.api.devices.DeviceBlockEntity
import org.valkyrienskies.horizons.api.devices.PowerNetworks
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode
import org.valkyrienskies.horizons.impl.devices.components.StandardSocket

class CircuitBoxBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(CircuitContent.ENTITY.get(), pos, state), MenuProvider {
    var design = CircuitDesign.empty()
        private set
    val paper = object : SimpleContainer(1) {
        override fun setChanged() {
            super.setChanged()
            syncDevice()
        }
        override fun getMaxStackSize() = 1
    }

    fun updateDesign(next: CircuitDesign) {
        val currentLevel = level ?: return
        if (currentLevel.isClientSide) return
        loadDevice()
        PowerNetworks.get(currentLevel).withState { (powerNode as CircuitBoardNode).setDesign(next) }
        design = next
        syncDevice()
    }
    override fun createPowerNode() = CircuitBoardNode(design)
    override fun onLoad() {
        super.onLoad()
        (powerNode as? CircuitBoardNode)?.let {
            design = it.design
            syncDevice()
        }
    }
    override fun createSockets(): List<StandardSocket> {
        val yaw = when (blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction.EAST -> -Math.PI / 2
            Direction.SOUTH -> Math.PI
            Direction.WEST -> Math.PI / 2
            else -> 0.0
        }
        val transform = Matrix4d().translation(0.5, 0.0, 0.5).rotateY(yaw).translate(-0.5, 0.0, -0.5)
        return (0..7).map { port ->
            val centerX = (3.5 + 3 * (port % 4)) / 16
            val centerY = if (port < 4) 1.0 else 0.0
            val center = transform.transformPosition(Vector3d(centerX, centerY, 13.5 / 16))
            val normal = transform.transformDirection(Vector3d(0.0, if (port < 4) 1.0 else -1.0, 0.0))
            val bounds = AABBd(centerX - 1.0 / 16, if (port < 4) 15.0 / 16 else 0.0, 12.5 / 16,
                centerX + 1.0 / 16, if (port < 4) 1.0 else 1.0 / 16, 14.5 / 16).transform(transform)
            StandardSocket(port, Int.MAX_VALUE, this, center, normal, bounds)
        }
    }
    private fun writeContents(tag: CompoundTag) {
        tag.put("Design", design.write())
        tag.put("Paper", paper.getItem(0).save(CompoundTag()))
    }
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        writeContents(tag)
    }
    override fun load(tag: CompoundTag) {
        super.load(tag)
        design = if (tag.contains("Design")) CircuitDesign.read(tag.getCompound("Design")) else CircuitDesign.empty()
        paper.setItem(0, ItemStack.of(tag.getCompound("Paper")))
    }
    override fun getUpdateTag(): CompoundTag = super.getUpdateTag().also(::writeContents)
    override fun getDisplayName(): Component = Component.translatable("block.horizons.circuit_box")
    override fun createMenu(id: Int, inventory: Inventory, player: Player) = CircuitBoxMenu(id, inventory, this)
}
