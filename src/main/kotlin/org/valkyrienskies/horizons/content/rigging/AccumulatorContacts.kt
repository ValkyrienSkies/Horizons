package org.valkyrienskies.horizons.content.rigging

import org.joml.Matrix4dc
import org.joml.Vector3d
import kotlin.math.abs

/** Coordinates are in the unrotated tray model; paired terminals also enforce polarity. */
object AccumulatorContacts {
    fun points(bodyToTray: Matrix4dc) = listOf(-0.1875, 0.1875).map {
        bodyToTray.transformPosition(Vector3d(it, 0.5625, 0.0))
    }
    fun facing(bodyToTray: Matrix4dc) = bodyToTray.transformDirection(Vector3d(0.0, 1.0, 0.0)).normalize().y < -0.985
    fun touching(points: List<Vector3d>, previous: List<Vector3d>?): Boolean = points.indices.all { i ->
        val p = points[i]
        val x = if (i == 0) 0.3125 else 0.6875
        fun onPad(v: Vector3d) = abs(v.x - x) <= 0.045 && abs(v.z - 0.5) <= 0.045 && abs(v.y - 0.125) <= 0.025
        if (onPad(p)) true else {
            val old = previous?.get(i)
            // Catch a falling contact crossing the pad between game ticks, but never a teleport.
            if (old == null || old.distanceSquared(p) > 0.25 || old.y <= 0.125 || p.y >= 0.125) false
            else onPad(Vector3d(old).lerp(p, (old.y - 0.125) / (old.y - p.y)))
        }
    }
    fun clear(points: List<Vector3d>) = points.all { it.y > 0.5 || it.y < -0.25 || it.x < 0.0 || it.x > 1.0 || it.z < 0.0 || it.z > 1.0 }
}
