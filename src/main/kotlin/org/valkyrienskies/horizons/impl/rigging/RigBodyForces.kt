package org.valkyrienskies.horizons.impl.rigging

import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Matrix4d
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.horizons.content.ship_grabbing.GrabRotation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

/** RigBody equivalent to GTPA */
object RigBodyForces {
    private data class Target(val position: Vector3d, val rotation: Quaterniond, val expires: Long)
    private val targets = ConcurrentHashMap<Pair<String, Long>, Target>()
    private val throws = ConcurrentHashMap<Pair<String, Long>, Vector3d>()
    private data class ContactAlignment(val shipId: Long?, val trayToLevel: Matrix4d)
    private val alignments = ConcurrentHashMap<Pair<String, Long>, ContactAlignment>()
    /** The game has verified contact. Finish seating the latch on the physics thread before solving it. */
    fun seatContact(dimension: String, id: Long, shipId: Long?, frame: Matrix4d) {
        alignments[dimension to id] = ContactAlignment(shipId, Matrix4d(frame))
    }
    fun cancelSeating(dimension: String, id: Long) { alignments.remove(dimension to id) }
    fun hold(dimension: String, id: Long, position: Vector3dc, rotation: Quaterniondc) {
        targets[dimension to id] = Target(Vector3d(position), Quaterniond(rotation), System.nanoTime() + 500_000_000L)
    }
    fun release(dimension: String, id: Long) { targets.remove(dimension to id); cancelSeating(dimension,id) }
    fun throwBody(dimension: String, id: Long, direction: Vector3d) { release(dimension, id); throws[dimension to id] = direction }
    fun clear(dimension: String) {
        targets.keys.removeIf { it.first == dimension }; throws.keys.removeIf { it.first == dimension }
        alignments.keys.removeIf { it.first == dimension }
    }
    fun tick(level: PhysLevel, dt: Double) {
        alignments.forEach { (key, contact) ->
            if (key.first != level.dimension || !alignments.remove(key,contact)) return@forEach
            val body = level.getBodyById(key.second) ?: return@forEach
            val dock = contact.shipId?.let(level::getBodyById)
            if (contact.shipId != null && dock == null) return@forEach
            val frame = dock?.kinematics?.transform?.toWorld?.mul(contact.trayToLevel, Matrix4d()) ?: contact.trayToLevel
            val position = frame.transformPosition(Vector3d(0.5,0.6875,0.5))
            val rotation = frame.getUnnormalizedRotation(Quaterniond()).rotateX(Math.PI)
            val velocity = dock?.velocity?.let(::Vector3d) ?: Vector3d()
            val angular = dock?.angularVelocity?.let(::Vector3d) ?: Vector3d()
            if (dock != null) velocity.add(angular.cross(position.sub(dock.kinematics.position,Vector3d()),Vector3d()))
            body.setTransform(position,rotation,body.kinematics.scaling)
            // Absorb the latch's capture impulse rather than asking a new constraint to correct an
            // entire game tick of penetration and relative velocity in its first physics step.
            val step = dt.coerceAtLeast(1e-4)
            body.applyWorldForce(velocity.sub(body.velocity).mul(body.inertiaData.mass/step),position)
            val inertia = body.inertiaData.inertiaTensor
            body.applyWorldTorque(angular.sub(body.angularVelocity).mul((inertia.m00()+inertia.m11()+inertia.m22())/(3*step)))
        }
        targets.forEach { (key, target) ->
            if (key.first != level.dimension) return@forEach
            if (target.expires < System.nanoTime()) { targets.remove(key, target); return@forEach }
            val body = level.getBodyById(key.second) ?: return@forEach
            val error = GrabRotation.error(target.rotation, body.kinematics.rotation) ?: run {
                targets.remove(key,target)
                return@forEach
            }
            val acceleration = target.position.sub(body.kinematics.position, Vector3d()).mul(48.0).sub(body.velocity.mul(12.0, Vector3d()))
            limit(acceleration, 220.0)
            val force = acceleration.add(0.0, 10.0, 0.0).mul(body.inertiaData.mass)
            val sin = sqrt(error.x * error.x + error.y * error.y + error.z * error.z)
            val torque = Vector3d(error.x, error.y, error.z).mul(if (sin < 1e-6) 0.0 else 64.0 * atan2(sin, error.w) / sin)
                .sub(body.angularVelocity.mul(10.0, Vector3d()))
            limit(torque, 90.0)
            val tensor = body.inertiaData.inertiaTensor
            torque.mul((tensor.m00() + tensor.m11() + tensor.m22()) / 3.0)
            if (!force.isFinite || !torque.isFinite || !body.kinematics.position.isFinite) {
                targets.remove(key,target)
                return@forEach
            }
            body.applyWorldForce(force, body.kinematics.position)
            body.applyWorldTorque(torque)
        }
        throws.forEach { (key, direction) ->
            if (key.first == level.dimension && throws.remove(key, direction)) level.getBodyById(key.second)?.let {
                it.applyWorldForce(direction.mul(500.0 * it.inertiaData.mass, Vector3d()), it.kinematics.position)
            }
        }
    }
    private fun limit(v: Vector3d, max: Double) { if (v.lengthSquared() > max * max) v.normalize(max) }
}
