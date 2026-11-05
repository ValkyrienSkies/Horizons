package org.valkyrienskies.horizons.content.ship_grabbing

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.horizons.api.foundation.mixin.GrabbedObjectTarget
import org.valkyrienskies.horizons.api.foundation.networking.server_to_client.ClientboundObjectGrabPacket
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.api.rigging.RigBodies
import org.valkyrienskies.horizons.impl.rigging.RigBodyManager
import org.valkyrienskies.horizons.impl.rigging.RigBodyForces
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.util.toJOML
import kotlin.math.atan2
import kotlin.math.sqrt

interface PlayerGrabbingMixinDuck {
    val horizonsGrabbingState: GrabbingState

    companion object {
        private const val GRAB_POS_KP = 48.0
        private const val GRAB_POS_KD = 12.0
        private const val GRAB_MAX_ACCEL = 220.0
        private const val GRAB_GRAVITY_COMPENSATION = 12.0
        private const val GRAB_ROT_KP = 32.0
        private const val GRAB_ROT_KD = 10.0
        private const val GRAB_MAX_ANGULAR_ACCEL = 90.0
    }

    fun getGrabbedObjectId(): Long = horizonsGrabbingState.grabbedObjectId

    fun setGrabbedObjectId(id: Long) {
        horizonsGrabbingState.grabbedObjectId = id
    }

    fun getGrabbedObjectTarget(): GrabbedObjectTarget = horizonsGrabbingState.grabbedObjectTarget

    fun setGrabbedObjectTarget(target: GrabbedObjectTarget) {
        horizonsGrabbingState.grabbedObjectTarget = target
    }

    fun setGrabbedObjectTarget(distance: Double, position: Vector3dc?, rotation: Quaterniondc?) {
        val t = horizonsGrabbingState.grabbedObjectTarget
        if (distance.isFinite() && distance >= 0.0) t.distance = distance.coerceIn(2.0, 40.0)
        if (position != null && position.isFinite && position.lengthSquared() <= 16.0) t.position = Vector3d(position)
        if (rotation != null && rotation.isFinite && rotation.lengthSquared() > 1e-9) t.rotation = Quaterniond(rotation).normalize()
    }

    fun inGrabMode(): Boolean = horizonsGrabbingState.grabMode

    fun toggleGrabMode() {
        horizonsGrabbingState.grabMode = !horizonsGrabbingState.grabMode
    }

    fun tryGrab(id: Long): Boolean {
        val player = this as Player
        val level = player.level()
        val oldId = getGrabbedObjectId()
        if (id == -1L || oldId > -1L) {
            if (!level.isClientSide) {
                RigBodyForces.release(level.dimensionId, oldId)
                RigBodies.get(level, oldId)?.onRelease(player as ServerPlayer)
            }
            horizonsGrabbingState.grabbedObjectId = -1L
            horizonsGrabbingState.grabbedObjectTarget = GrabbedObjectTarget()
            if (!level.isClientSide) HorizonsNetworking.sendToClient(ClientboundObjectGrabPacket(-1L), player as ServerPlayer)
            return true
        }
        val rig = RigBodies.get(level, id)
        if (rig != null) {
            val body = rig.body ?: return false
            if (!level.isClientSide && (RigBodies.isGrabbed(level, id) || RigBodyManager.raycast(player)?.bodyId != id || !rig.onGrab(player as ServerPlayer))) return false
            horizonsGrabbingState.grabbedObjectTarget = GrabbedObjectTarget()
            val p = body.kinematics.position
            setGrabbedObjectTarget(player.eyePosition.distanceTo(net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z())).coerceIn(2.0, 10.0), null, body.kinematics.rotation)
        }
        horizonsGrabbingState.grabbedObjectId = id
        return true
    }

    fun tryThrow(id: Long): Boolean {
        if (id != horizonsGrabbingState.grabbedObjectId) return false
        val player = this as Player
        if (player.level().isClientSide) return false
        val level = player.level() as ServerLevel
        val rig = RigBodies.get(level, id)
        if (rig != null) {
            tryGrab(-1L)
            RigBodyForces.throwBody(level.dimensionId, id, player.lookAngle.toJOML())
            return true
        }
        val ship: LoadedServerShip? = level.server.shipWorld!!.loadedShips.getById(horizonsGrabbingState.grabbedObjectId)
        horizonsGrabbingState.grabbedObjectId = -1L
        horizonsGrabbingState.grabbedObjectTarget = GrabbedObjectTarget()
        if (ship != null) {
            val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
            val force: Vector3dc = player.getViewVector(0f).normalize().scale(500.0 * ship.inertiaData.mass).toJOML()
            gtpa.applyWorldForce(ship.id, force, null)
        }
        return true
    }

    fun horizonsPostTick() {
        if (horizonsGrabbingState.grabbedObjectId == -1L) return
        val player = this as Player
        if (player.level().isClientSide) return
        val level = player.level() as ServerLevel
        val rig = RigBodies.get(level, getGrabbedObjectId())
        if (rig != null) {
            val body = rig.body
            if (body == null || !player.isAlive) { tryGrab(-1L); return }
            val target = getGrabbedObjectTarget()
            val position = player.eyePosition.add(player.lookAngle.scale(target.distance)).toJOML()
            target.position?.let { position.add(it) }
            RigBodyForces.hold(level.dimensionId, rig.bodyId, position, target.rotation ?: body.kinematics.rotation)
            return
        }
        val ship: LoadedServerShip? = level.server.shipWorld!!.loadedShips.getById(horizonsGrabbingState.grabbedObjectId)
        if (ship == null) {
            tryGrab(-1L)
            return
        }
        val target = horizonsGrabbingState.grabbedObjectTarget
        var targetPos: Vector3dc = player.eyePosition.add(player.getViewVector(0f).normalize().scale(target.distance)).toJOML()
        target.position?.let { targetPos = targetPos.add(it, Vector3d()) }
        val targetRot: Quaterniondc = target.rotation ?: ship.kinematics.rotation

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val force = computeGrabForce(ship, targetPos)
        if (force.isFinite && force.lengthSquared() > 1.0e-6) {
            gtpa.applyWorldForce(ship.id, force, null)
        }
        val torque = computeGrabTorque(ship, targetRot)
        if (torque.isFinite && torque.lengthSquared() > 1.0e-6) {
            gtpa.applyWorldTorque(ship.id, torque)
        }
    }

    private fun computeGrabForce(ship: LoadedServerShip, targetPos: Vector3dc): Vector3dc {
        val positionError = targetPos.sub(ship.transform.positionInWorld, Vector3d())
        val desiredAcceleration = positionError.mul(GRAB_POS_KP, Vector3d())
            .sub(ship.velocity.mul(GRAB_POS_KD, Vector3d()))
        clampMagnitude(desiredAcceleration, GRAB_MAX_ACCEL)
        desiredAcceleration.add(0.0, GRAB_GRAVITY_COMPENSATION, 0.0)
        return desiredAcceleration.mul(ship.inertiaData.mass)
    }

    private fun computeGrabTorque(ship: LoadedServerShip, targetRot: Quaterniondc): Vector3dc {
        val rotationError = GrabRotation.error(targetRot, ship.transform.shipToWorldRotation) ?: return Vector3d()

        val sinHalfAngle = sqrt(
            rotationError.x * rotationError.x +
                rotationError.y * rotationError.y +
                rotationError.z * rotationError.z
        )
        if (sinHalfAngle < 1.0e-6) {
            return ship.angularVelocity.mul(-GRAB_ROT_KD * averageInertia(ship), Vector3d())
        }

        val angle = 2.0 * atan2(sinHalfAngle, rotationError.w)
        val axis = Vector3d(rotationError.x, rotationError.y, rotationError.z).div(sinHalfAngle)
        val desiredAngularAcceleration = axis.mul(angle * GRAB_ROT_KP)
            .sub(ship.angularVelocity.mul(GRAB_ROT_KD, Vector3d()))
        clampMagnitude(desiredAngularAcceleration, GRAB_MAX_ANGULAR_ACCEL)
        return desiredAngularAcceleration.mul(averageInertia(ship))
    }

    private fun averageInertia(ship: LoadedServerShip): Double {
        val inertia = ship.inertiaData.inertiaTensor
        return (inertia.m00() + inertia.m11() + inertia.m22()) / 3.0
    }

    private fun clampMagnitude(vector: Vector3d, maxMagnitude: Double) {
        val lengthSquared = vector.lengthSquared()
        val maxSquared = maxMagnitude * maxMagnitude
        if (lengthSquared > maxSquared && lengthSquared > 1.0e-9) {
            vector.mul(maxMagnitude / sqrt(lengthSquared))
        }
    }

}
