package org.valkyrienskies.horizons.content.items

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.horizons.api.rigging.RigBodies
import org.valkyrienskies.horizons.content.rigging.Accumulator
import org.valkyrienskies.mod.common.getShipManagingPos

class DebugAccumulatorItem : Item(Properties().stacksTo(1)) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val hit = context.clickLocation
        val normal = context.clickedFace.normal
        val position = Vector3d(hit.x, hit.y, hit.z).add(normal.x * 0.75, normal.y * 0.75, normal.z * 0.75)
        val rotation = Quaterniond().rotationY(-Math.toRadians((context.player?.yRot ?: 0f).toDouble()))
        level.getShipManagingPos(context.clickedPos)?.transform?.let {
            it.shipToWorld.transformPosition(position)
            it.shipToWorldRotation.mul(rotation, rotation)
        }
        RigBodies.spawn(level, Accumulator.TYPE, position, rotation, CompoundTag())
        return InteractionResult.CONSUME
    }
    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("item.horizons.debug_accumulator.tooltip"))
        tooltip.add(Component.translatable("item.horizons.debug_accumulator.controls"))
    }
}
