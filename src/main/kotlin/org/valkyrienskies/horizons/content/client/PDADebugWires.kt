package org.valkyrienskies.horizons.content.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector4f
import org.valkyrienskies.horizons.content.items.pda.PDAItem
import org.valkyrienskies.horizons.content.items.pda.VoltageProbe
import org.valkyrienskies.horizons.impl.render.SocketRenderSpace
import org.valkyrienskies.horizons.impl.render.SocketRenderer
import java.util.EnumMap

object PDADebugWires {
    private data class HandAnchor(val clip: Vector4f, val capturedAt: Long)
    private val anchors = EnumMap<InteractionHand, HandAnchor>(InteractionHand::class.java)

    /** Called after the baked model's display transform, before its centre translation. */
    @JvmStatic
    fun capture(stack: ItemStack, context: ItemDisplayContext, leftHand: Boolean, pose: PoseStack) {
        if (stack.item !is PDAItem) return
        if (!context.firstPerson() && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return
        val player = Minecraft.getInstance().player ?: return
        val arm = if (leftHand) HumanoidArm.LEFT else HumanoidArm.RIGHT
        val hand = if (arm == player.mainArm) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        val held = player.getItemInHand(hand)
        if (held.item !is PDAItem || (!context.firstPerson() && held !== stack)) return
        val clip = Vector4f(0f, 0f, 0f, 1f)
        Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(pose.last().pose()).transform(clip)
        anchors[hand] = HandAnchor(clip, System.nanoTime())
    }

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val worldViewProjection = Matrix4f(event.projectionMatrix).mul(RenderSystem.getModelViewMatrix()).mul(event.poseStack.last().pose())
        val buffers = mc.renderBuffers().bufferSource()
        val consumer = buffers.getBuffer(RenderType.lines())
        for (hand in InteractionHand.values()) {
            val stack = player.getItemInHand(hand)
            if (stack.item !is PDAItem) { anchors.remove(hand); continue }
            val anchor = anchors[hand] ?: continue
            // First-person hands render after the world, so their anchor is reused on the following frame.
            if (System.nanoTime() - anchor.capturedAt > 250_000_000L) continue
            val origin = unprojectAnchor(anchor.clip, worldViewProjection, event.camera.position) ?: continue
            for ((index, key) in listOf(PDAItem.PROBE_A, PDAItem.PROBE_B).withIndex()) {
                val socket = VoltageProbe.read(stack.tag, key)?.resolve(level) ?: continue
                val end = SocketRenderSpace.toWorld(socket, socket.pos)
                SocketRenderer.line(event.poseStack, consumer, origin, end, if (index == 0) 1f else 0.25f, 0.25f, if (index == 0) 0.25f else 1f)
            }
        }
        buffers.endBatch(RenderType.lines())
    }

    /** Unproject the actual item anchor through the world camera; handles the distinct hand FOV. */
    internal fun unprojectAnchor(clip: Vector4f, worldViewProjection: Matrix4f, camera: Vec3): Vector3d? {
        val point = Matrix4f(worldViewProjection).invert().transform(Vector4f(clip))
        if (!point.w.isFinite() || kotlin.math.abs(point.w) < 1e-8f) return null
        val result = Vector3d((point.x / point.w).toDouble() + camera.x, (point.y / point.w).toDouble() + camera.y, (point.z / point.w).toDouble() + camera.z)
        return result.takeIf { it.isFinite }
    }
}
