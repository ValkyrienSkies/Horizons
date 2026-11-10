package org.valkyrienskies.horizons.content.ship_grabbing

import org.joml.Quaterniond
import org.joml.Quaterniondc

internal object GrabRotation {
    /** q and -q describe the same orientation. Choose the shortest rotation without scaling it. */
    fun error(target: Quaterniondc, current: Quaterniondc): Quaterniond? {
        if (!valid(target) || !valid(current)) return null
        val error = Quaterniond(target).normalize()
            .mul(Quaterniond(current).normalize().conjugate()).normalize()
        // Quaterniond.scale scales the represented transform and takes sqrt(factor).
        // A negative factor produces NaNs; negate the four components instead.
        if (error.w < 0.0) error.set(-error.x, -error.y, -error.z, -error.w)
        return error.takeIf { it.isFinite }
    }

    private fun valid(rotation: Quaterniondc): Boolean {
        val norm = rotation.lengthSquared()
        return rotation.isFinite && norm.isFinite() && norm > 1e-12
    }
}
