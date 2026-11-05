package org.valkyrienskies.horizons.content

import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.horizons.content.ship_grabbing.GrabRotation

class GrabRotationTest {
    private fun opposite(q: Quaterniond) = Quaterniond(-q.x,-q.y,-q.z,-q.w)
    private fun assertSameRotationError(a: Quaterniond, b: Quaterniond) {
        assertTrue(a.isFinite && b.isFinite)
        assertEquals(a.x,b.x,1e-12); assertEquals(a.y,b.y,1e-12)
        assertEquals(a.z,b.z,1e-12); assertEquals(a.w,b.w,1e-12)
        assertEquals(1.0,b.lengthSquared(),1e-12)
    }

    @Test fun equivalentQuaternionSignsProduceTheSameFiniteError() {
        val target = Quaterniond().rotateXYZ(0.7,-0.4,1.2)
        val current = Quaterniond().rotateXYZ(-0.2,0.3,-0.6)
        val expected = GrabRotation.error(target,current)!!
        assertSameRotationError(expected,GrabRotation.error(opposite(target),current)!!)
        assertSameRotationError(expected,GrabRotation.error(target,opposite(current))!!)
        assertSameRotationError(expected,GrabRotation.error(opposite(target),opposite(current))!!)
    }

    @Test fun negativeIdentityDoesNotCreateNaNsOrSpuriousRotation() {
        assertSameRotationError(Quaterniond(),GrabRotation.error(Quaterniond(0.0,0.0,0.0,-1.0),Quaterniond())!!)
    }

    @Test fun rotationsPastHalfATurnUseTheShortestDirection() {
        val halfAngle = Math.toRadians(190.0)/2
        val target = Quaterniond(Math.sin(halfAngle),0.0,0.0,Math.cos(halfAngle))
        val error = GrabRotation.error(target,Quaterniond())!!
        assertTrue(error.isFinite && error.w >= 0.0 && error.x < 0.0, "Unexpected rotation error: $error")
        assertEquals(Math.toRadians(170.0),2*Math.atan2(kotlin.math.abs(error.x),error.w),1e-12)
    }

    @Test fun normalizesCopiesAndRejectsInvalidRotations() {
        val target = Quaterniond(0.0,0.0,0.0,-2.0)
        assertSameRotationError(Quaterniond(),GrabRotation.error(target,Quaterniond(0.0,0.0,0.0,3.0))!!)
        assertEquals(-2.0,target.w)
        for (bad in listOf(Quaterniond(0.0,0.0,0.0,0.0),Quaterniond(Double.NaN,0.0,0.0,1.0),Quaterniond(0.0,Double.POSITIVE_INFINITY,0.0,1.0))) {
            assertNull(GrabRotation.error(bad,Quaterniond()))
            assertNull(GrabRotation.error(Quaterniond(),bad))
        }
    }
}
