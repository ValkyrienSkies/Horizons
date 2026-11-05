package org.valkyrienskies.horizons.content

import org.joml.Matrix4d
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.horizons.content.rigging.AccumulatorContacts as Contacts

class AccumulatorContactsTest {
    private fun pose(y: Double = 0.6875) = Matrix4d().translation(0.5, y, 0.5).rotateX(Math.PI)
    @Test fun requiresBothTerminalsAndCorrectPolarity() {
        val aligned = pose()
        assertTrue(Contacts.facing(aligned))
        assertTrue(Contacts.touching(Contacts.points(aligned), null))
        val upright = Matrix4d().translation(0.5, 0.6875, 0.5)
        assertFalse(Contacts.facing(upright))
        val reversed = pose().rotateY(Math.PI)
        assertFalse(Contacts.touching(Contacts.points(reversed), null))
        assertFalse(Contacts.touching(Contacts.points(pose().translate(0.2, 0.0, 0.0)), null))
        assertFalse(Contacts.touching(Contacts.points(pose(1.0)), null))
        assertFalse(Contacts.facing(pose().rotateZ(0.3)))
    }
    @Test fun detectsFallingContactBetweenTicksWithoutCapturingTeleportsOrOneTerminal() {
        val previous = Contacts.points(pose(0.8))
        assertTrue(Contacts.touching(Contacts.points(pose(0.6)), previous))
        assertFalse(Contacts.touching(Contacts.points(pose(-1.0)), previous))
        val tilted = Contacts.points(pose(0.6).rotateZ(0.5))
        assertFalse(Contacts.touching(tilted, previous))
    }
    @Test fun shipTransformCancelsOutBeforeCheckingContacts() {
        val ship = Matrix4d().translation(123.0, 89.0, -45.0).rotateXYZ(0.3, 1.2, -0.2).scale(1.5)
        val dock = Matrix4d().translation(28000000.0, 42.0, 28000000.0).rotateY(Math.PI / 2)
        val toWorld = ship.mul(dock, Matrix4d())
        val bodyWorld = toWorld.mul(pose(), Matrix4d())
        val relative = toWorld.invert(Matrix4d()).mul(bodyWorld)
        assertTrue(Contacts.facing(relative))
        assertTrue(Contacts.touching(Contacts.points(relative), null))
    }
    @Test fun releaseRequiresContactsToLeaveTray() {
        assertFalse(Contacts.clear(Contacts.points(pose())))
        assertTrue(Contacts.clear(Contacts.points(pose(1.5))))
    }
}
