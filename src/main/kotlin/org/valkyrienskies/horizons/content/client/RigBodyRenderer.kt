package org.valkyrienskies.horizons.content.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.horizons.api.rigging.RigBodies
import org.valkyrienskies.horizons.impl.rigging.RigBodyManager
import org.valkyrienskies.horizons.impl.rigging.RigBodyPacket

object RigBodyRenderer {
    fun models(event: ModelEvent.RegisterAdditional) { RigBodies.getTypes().forEach { event.register(it.model) } }
    fun receive(packet: RigBodyPacket) {
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension().location() == packet.dimension) RigBodyManager.receive(level, packet.data)
    }
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val poses = event.poseStack
        val camera = event.camera.position
        val buffer = mc.renderBuffers().bufferSource()
        val renderType = RenderType.cutout()
        for (rig in RigBodies.getAll(level)) {
            val body = rig.body as? ClientVsBody ?: continue
            val transform = body.renderTransform
            val position = transform.toWorld.transformPosition(Vector3d())
            if (position.distanceSquared(Vector3d(camera.x, camera.y, camera.z)) > 256.0 * 256.0) continue
            val model = mc.modelManager.getModel(rig.type.model)
            val center = rig.type.modelCenter
            poses.pushPose()
            poses.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
            poses.mulPose(Quaternionf(transform.rotation))
            val scale = transform.scaling
            poses.scale(scale.x().toFloat(), scale.y().toFloat(), scale.z().toFloat())
            poses.translate(-center.x(), -center.y(), -center.z())
            mc.blockRenderer.modelRenderer.renderModel(poses.last(), buffer.getBuffer(renderType), null, model,
                1f, 1f, 1f, LevelRenderer.getLightColor(level, BlockPos.containing(position.x, position.y, position.z)),
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType)
            poses.popPose()
        }
        buffer.endBatch(renderType)
    }
}
