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
        assertFalse(Contacts.facing(pose().rotateZ(Math.toRadians(30.0))))
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
    @Test fun acceptsOffCenterTiltedAndLowRestingBatteries() {
        val offset = Matrix4d().translation(0.6,0.75,0.6).rotateX(Math.PI)
        assertTrue(Contacts.touching(Contacts.points(offset),null))
        val tilted = pose().rotateZ(Math.toRadians(12.0))
        assertTrue(Contacts.facing(tilted))
        assertTrue(Contacts.touching(Contacts.points(tilted),null))
        // The collision cube can rest on the tray floor with its decorative terminals below it.
        assertTrue(Contacts.touching(Contacts.points(pose(0.484375)),null))
        assertFalse(Contacts.touching(Contacts.points(pose(1.0)),null))
    }
    @Test fun capturedBatteryStaysWithinTheLargerReleaseRegion() {
        val captured = Matrix4d().translation(0.62,0.92,0.62).rotateX(Math.PI)
        assertTrue(Contacts.touching(Contacts.points(captured),null))
        assertTrue(Contacts.retained(captured))
        assertTrue(Contacts.retained(pose().translate(0.14,0.0,0.0)))
        assertFalse(Contacts.retained(pose().translate(0.2,0.0,0.0)))
        assertFalse(Contacts.retained(pose().rotateY(Math.PI)))
    }
}
