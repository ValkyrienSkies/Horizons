package org.valkyrienskies.horizons.content.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import org.valkyrienskies.horizons.content.blockentities.AmmeterBlockEntity
import org.valkyrienskies.horizons.content.items.pda.PDAItem
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs

object MeasurementOverlay {
    private var voltageMessage: Component? = null
    private var voltageLevel: WeakReference<ClientLevel>? = null
    private var voltageReceivedAt = 0L

    fun receiveMessage(event: ClientChatReceivedEvent.System) {
        if (!event.isOverlay) return
        voltageMessage = null
        voltageLevel = null
        val key = (event.message.contents as? TranslatableContents)?.key
        if (key != "message.horizons.pda_voltage" && key != "message.horizons.pda_unavailable") return
        // Render measurements ourselves so putting the PDA away hides them immediately.
        event.isCanceled = true
        val mc = Minecraft.getInstance()
        if (!holdingPda(mc)) return
        mc.gui.setOverlayMessage(Component.empty(), false)
        voltageMessage = event.message
        voltageLevel = WeakReference(mc.level)
        voltageReceivedAt = System.nanoTime()
    }

    private fun holdingPda(mc: Minecraft): Boolean =
        mc.player?.let { it.mainHandItem.item is PDAItem || it.offhandItem.item is PDAItem } == true

    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay == VanillaGuiOverlay.RECORD_OVERLAY.type()) {
            val mc = Minecraft.getInstance()
            if (!holdingPda(mc) || mc.level !== voltageLevel?.get() || System.nanoTime() - voltageReceivedAt > 3_000_000_000L) {
                voltageMessage = null
                voltageLevel = null
            }
            if (!mc.options.hideGui) voltageMessage?.let { text ->
                event.guiGraphics.drawCenteredString(mc.font, text, event.window.guiScaledWidth / 2, event.window.guiScaledHeight - 72, 0xFFFFFF)
            }
            return
        }
        if (event.overlay != VanillaGuiOverlay.CROSSHAIR.type()) return
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult as? BlockHitResult ?: return
        val meter = mc.level?.getBlockEntity(hit.blockPos) as? AmmeterBlockEntity ?: return
        val text = Component.translatable("hud.horizons.ammeter_current", formatCurrent(meter.measuredCurrent))
        val x = event.window.guiScaledWidth / 2
        val y = event.window.guiScaledHeight / 2 + 18
        val halfWidth = mc.font.width(text) / 2 + 4
        event.guiGraphics.fill(x - halfWidth, y - 3, x + halfWidth, y + 12, 0xA0000000.toInt())
        event.guiGraphics.drawCenteredString(mc.font, text, x, y, 0xFFFFFF)
    }

    fun formatCurrent(value: Double): String {
        val current = value.takeIf { it.isFinite() && abs(it) >= 0.0000005 } ?: 0.0
        val scale = if (current != 0.0 && abs(current) < 0.001) 1_000_000.0 else if (current != 0.0 && abs(current) < 1) 1000.0 else 1.0
        val unit = if (scale == 1_000_000.0) "µA" else if (scale == 1000.0) "mA" else "A"
        return String.format(Locale.ROOT, "%.3f %s", current * scale, unit)
    }
}
