package org.valkyrienskies.horizons.content.items.pda

import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.valkyrienskies.horizons.api.devices.IDevice
import org.valkyrienskies.horizons.api.devices.components.SocketTargeting
import java.util.Locale

class PDAItem : Item(Properties().stacksTo(1)) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        if (player.isShiftKeyDown) {
            if (!context.level.isClientSide) clear(context.itemInHand, player)
            return InteractionResult.sidedSuccess(context.level.isClientSide)
        }
        val be = context.level.getBlockEntity(context.clickedPos) ?: return InteractionResult.PASS
        val device = be as? IDevice ?: return InteractionResult.PASS
        if (context.level.isClientSide) return InteractionResult.SUCCESS
        val reach = player.blockReach + 0.01
        val socket = SocketTargeting.find(be, player.eyePosition, player.eyePosition.add(player.lookAngle.scale(reach)))
        if (socket == null || device.powerNode == null) {
            player.displayClientMessage(Component.translatable("message.horizons.aim_at_socket"), true)
            return InteractionResult.CONSUME
        }
        val tag = context.itemInHand.orCreateTag
        val first = VoltageProbe.read(tag, PROBE_A)
        val second = VoltageProbe.read(tag, PROBE_B)
        val probe = VoltageProbe.of(context.level, device, socket)
        if (first == null || second != null) {
            tag.put(PROBE_A, probe.save())
            tag.remove(PROBE_B)
            player.displayClientMessage(Component.translatable("message.horizons.pda_first_probe"), true)
        } else {
            tag.put(PROBE_B, probe.save())
            showReading(context.itemInHand, player)
        }
        return InteractionResult.CONSUME
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (!player.isShiftKeyDown) return InteractionResultHolder.pass(stack)
        if (!level.isClientSide) clear(stack, player)
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (level.isClientSide || level.gameTime % 4L != 0L || entity !is Player) return
        if (!selected && entity.offhandItem !== stack) return
        if (VoltageProbe.read(stack.tag, PROBE_A) != null && VoltageProbe.read(stack.tag, PROBE_B) != null) showReading(stack, entity)
    }

    private fun showReading(stack: ItemStack, player: Player) {
        val a = VoltageProbe.read(stack.tag, PROBE_A)?.resolve(player.level())
        val b = VoltageProbe.read(stack.tag, PROBE_B)?.resolve(player.level())
        val deviceA = a?.blockEntity as? IDevice
        val deviceB = b?.blockEntity as? IDevice
        val network = deviceA?.powerNetwork
        val nodeA = deviceA?.powerNode
        val nodeB = deviceB?.powerNode
        if (a == null || b == null || network == null || network !== deviceB?.powerNetwork || nodeA == null || nodeB == null) {
            player.displayClientMessage(Component.translatable("message.horizons.pda_unavailable"), true)
            return
        }
        val voltage = network.getVoltageBetween(nodeA, a.port, nodeB, b.port)
        player.displayClientMessage(Component.translatable("message.horizons.pda_voltage", formatVoltage(voltage)), true)
    }

    private fun clear(stack: ItemStack, player: Player) {
        stack.tag?.remove(PROBE_A)
        stack.tag?.remove(PROBE_B)
        player.displayClientMessage(Component.translatable("message.horizons.pda_cleared"), true)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("tooltip.horizons.debug_pda"))
        tooltip.add(Component.translatable("tooltip.horizons.debug_pda_clear"))
    }

    companion object {
        const val PROBE_A = "VoltageProbeA"
        const val PROBE_B = "VoltageProbeB"
        fun formatVoltage(value: Double): String = String.format(Locale.ROOT, "%.3f V", if (value.isFinite() && kotlin.math.abs(value) >= 0.0005) value else 0.0)
    }
}
