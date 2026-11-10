package org.valkyrienskies.horizons.content

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.horizons.content.client.PDADebugWires
import org.valkyrienskies.horizons.content.items.pda.PDAItem
import org.valkyrienskies.horizons.content.items.pda.VoltageProbe

class MeasurementTest {
    @Test fun probeReferencesRoundTripAndRejectIncompleteTags() {
        val probe = VoltageProbe("minecraft:overworld", BlockPos(28_000_000, 64, -100), 12345, 1)
        val stackTag = CompoundTag()
        stackTag.put(PDAItem.PROBE_A, probe.save())
        assertEquals(probe, VoltageProbe.read(stackTag, PDAItem.PROBE_A))
        assertNull(VoltageProbe.read(stackTag, PDAItem.PROBE_B))
        stackTag.getCompound(PDAItem.PROBE_A).remove("NodeId")
        assertNull(VoltageProbe.read(stackTag, PDAItem.PROBE_A))
    }

    @Test fun handAnchorReprojectsToTheSameScreenPositionWithDifferentHandAndWorldFovs() {
        val worldProjection = Matrix4f().perspective(Math.toRadians(95.0).toFloat(), 16f / 9f, 0.05f, 256f)
        val worldView = Matrix4f().rotateY(0.7f).rotateX(-0.3f)
        val worldViewProjection = Matrix4f(worldProjection).mul(worldView)
        val handProjection = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), 16f / 9f, 0.05f, 256f)
        val clip = handProjection.transform(Vector4f(0.3f, -0.2f, -0.7f, 1f))
        val camera = Vec3(100.0, 80.0, -200.0)
        val anchor = PDADebugWires.unprojectAnchor(clip, worldViewProjection, camera)!!
        val projected = worldViewProjection.transform(Vector4f((anchor.x - camera.x).toFloat(), (anchor.y - camera.y).toFloat(), (anchor.z - camera.z).toFloat(), 1f))
        assertEquals(clip.x / clip.w, projected.x / projected.w, 1e-5f)
        assertEquals(clip.y / clip.w, projected.y / projected.w, 1e-5f)
    }
}
