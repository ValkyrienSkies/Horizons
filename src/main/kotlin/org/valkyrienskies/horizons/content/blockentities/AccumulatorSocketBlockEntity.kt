package org.valkyrienskies.horizons.content.blockentities

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.internal.joints.*
import org.valkyrienskies.horizons.api.devices.DeviceBlockEntity
import org.valkyrienskies.horizons.api.devices.PowerNetworks
import org.valkyrienskies.horizons.api.devices.components.ISocket
import org.valkyrienskies.horizons.api.rigging.RigBodies
import org.valkyrienskies.horizons.content.rigging.Accumulator
import org.valkyrienskies.horizons.content.rigging.AccumulatorContacts
import org.valkyrienskies.horizons.content.HorizonsSounds
import org.valkyrienskies.horizons.impl.devices.components.StandardSocket
import org.valkyrienskies.horizons.impl.rigging.RigBodyForces
import org.valkyrienskies.horizons.potato_battery.impl.nodes.BatterySocketNode
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.api.vsApi

class AccumulatorSocketBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(HorizonsBlockEntities.ACCUMULATOR_SOCKET.get(), pos, state) {
    var attachedId = -1L
        private set
    private var jointId: Int? = null
    private var pending = false
    private var generation = 0
    private val previous = mutableMapOf<Long, List<Vector3d>>()
    override fun createPowerNode() = BatterySocketNode()
    private fun yaw() = when (blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
        Direction.EAST -> -Math.PI / 2; Direction.SOUTH -> Math.PI; Direction.WEST -> Math.PI / 2; else -> 0.0
    }
    private fun trayToBlock() = Matrix4d().translation(0.5, 0.0, 0.5).rotateY(yaw()).translate(-0.5, 0.0, -0.5)
    private fun trayToLevel() = Matrix4d().translation(worldPosition.x.toDouble(), worldPosition.y.toDouble(), worldPosition.z.toDouble()).mul(trayToBlock())
    private fun trayToWorld(): Matrix4d {
        val matrix = trayToLevel()
        return level.getShipManagingPos(worldPosition)?.transform?.shipToWorld?.mul(matrix, Matrix4d()) ?: matrix
    }
    override fun createSockets(): List<ISocket> = (0..1).map { port ->
        val x = if (port == 0) 3.0 else 9.0
        val matrix = trayToBlock()
        val bounds = AABBd(x/16, 1.0/16, -2.0/16, (x+4)/16, 5.0/16, 0.0).transform(matrix)
        StandardSocket(port, Int.MAX_VALUE, this, matrix.transformPosition(Vector3d((x+2)/16, 3.0/16, -2.0/16)),
            matrix.transformDirection(Vector3d(0.0, 0.0, -1.0)), bounds)
    }
    fun tick() {
        val level = level as? ServerLevel ?: return
        if (powerNode !is BatterySocketNode) return
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        if (jointId != null && gtpa.getJointById(jointId!!) == null) { disconnect(); return }
        if (jointId != null) {
            val accumulator = RigBodies.get(level, attachedId) as? Accumulator
            val body = accumulator?.body
            if (body != null) {
                val relative = trayToWorld().invert().mul(body.kinematics.transform.toWorld)
                val contacts = AccumulatorContacts.points(relative)
                // Also reject stale constraints and externally moved bodies that no longer touch the tray.
                if (!AccumulatorContacts.facing(relative) || contacts.withIndex().any { (i, p) ->
                    p.distanceSquared(Vector3d(if (i == 0) 0.3125 else 0.6875, 0.125, 0.5)) > 0.125 * 0.125
                }) { disconnect(); return }
            }
        }
        if (pending || jointId != null) return
        if (attachedId >= 0) {
            val accumulator = RigBodies.get(level, attachedId) as? Accumulator ?: run { disconnect(); return }
            if (accumulator.dockPos != worldPosition || RigBodies.isGrabbed(level, attachedId)) { disconnect(); return }
            if (accumulator.jointId != null) {
                jointId = accumulator.jointId
                connectCell(accumulator)
            } else if (vsApi.getServerShipWorld(level.server)?.loadedBodies?.getById(attachedId) != null) {
                val transform = trayToWorld().invert().mul(accumulator.body!!.kinematics.transform.toWorld)
                if (AccumulatorContacts.facing(transform) && AccumulatorContacts.touching(AccumulatorContacts.points(transform), null)) attach(accumulator)
                else disconnect()
            }
            return
        }
        val worldToTray = trayToWorld().invert()
        val nearby = mutableSetOf<Long>()
        for (accumulator in RigBodies.getAll(level).filterIsInstance<Accumulator>()) {
            if (vsApi.getServerShipWorld(level.server)?.loadedBodies?.getById(accumulator.bodyId) == null) continue
            val body = accumulator.body ?: continue
            val bodyToTray = worldToTray.mul(body.kinematics.transform.toWorld, Matrix4d())
            val points = AccumulatorContacts.points(bodyToTray)
            if (accumulator.blockedDock == worldPosition && AccumulatorContacts.clear(points)) accumulator.blockedDock = null
            if (points[0].distanceSquared(Vector3d(0.5, 0.125, 0.5)) > 4.0) continue
            nearby.add(accumulator.bodyId)
            val old = previous.put(accumulator.bodyId, points)
            if (accumulator.dockPos != null || accumulator.blockedDock == worldPosition || RigBodies.isGrabbed(level, accumulator.bodyId)) continue
            if (AccumulatorContacts.facing(bodyToTray) && AccumulatorContacts.touching(points, old)) {
                attachedId = accumulator.bodyId
                accumulator.dockPos = worldPosition.immutable()
                setChanged()
                attach(accumulator, true)
                break
            }
        }
        previous.keys.retainAll(nearby)
    }
    private fun attach(accumulator: Accumulator, audible: Boolean = false) {
        val level = level as? ServerLevel ?: return
        val node = powerNode as? BatterySocketNode ?: return
        val ship = level.getShipManagingPos(worldPosition)
        val frame = trayToLevel()
        val dockAnchor = frame.transformPosition(Vector3d(0.5, 0.125, 0.5))
        // VS joints use model coordinates: shipyard positions for ships, world positions for ground.
        // The backend accounts for the body's center of mass itself.
        val joint = VSFixedJoint(accumulator.bodyId,
            VSJointPose(Vector3d(0.0, 0.5625, 0.0), Quaterniond().rotationX(Math.PI)),
            ship?.id, VSJointPose(dockAnchor, Quaterniond().rotationY(yaw())),
            // Allow capture impacts from a 500 kg cell without making the latch unbreakable.
            VSJointMaxForceTorque(250_000f, 50_000f), 1e-7)
        RigBodyForces.seatContact(level.dimensionId,accumulator.bodyId,ship?.id,frame)
        pending = true
        val token = ++generation
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        gtpa.addJoint(joint) { id -> level.server.execute {
            if (isRemoved || generation != token || attachedId != accumulator.bodyId || accumulator.dockPos != worldPosition) {
                if (id >= 0 && gtpa.getJointById(id) != null) gtpa.removeJoint(id)
                return@execute
            }
            pending = false
            if (id < 0 || gtpa.getJointById(id) == null) { disconnect(); return@execute }
            jointId = id
            accumulator.jointId = id
            connectCell(accumulator)
            if (audible) playDockSound(HorizonsSounds.BATTERY_CONNECTED.get())
            setChanged()
        } }
    }
    private fun connectCell(accumulator: Accumulator) {
        val node = powerNode as? BatterySocketNode ?: return
        val network = PowerNetworks.get(level!!)
        network.withState {
            reconcileCharge(node, accumulator)
            val cell = accumulator.cell
            network.updateTopology { node.connect(accumulator.bodyId.toString(), cell) }
        }
    }
    private fun reconcileCharge(node: BatterySocketNode, accumulator: Accumulator) {
        val saved = node.battery
        if (node.batteryId == accumulator.bodyId.toString() && saved != null && saved.stateOfCharge < accumulator.cell.stateOfCharge) accumulator.cell = saved
    }
    fun disconnect() {
        val level = level as? ServerLevel ?: return
        val accumulator = RigBodies.get(level, attachedId) as? Accumulator
        RigBodyForces.cancelSeating(level.dimensionId,attachedId)
        accumulator?.let { if (it.dockPos == worldPosition) it.dockPos = null; it.blockedDock = worldPosition.immutable() }
        val ownedJoint = accumulator?.jointId
        accumulator?.releaseJoint()
        if (ownedJoint != null && jointId == ownedJoint) jointId = null
        releaseJoint()
        val node = powerNode as? BatterySocketNode
        val wasConnected = if (node != null) {
            val network = PowerNetworks.get(level)
            network.withState {
                val connected = node.isConnected
                if (accumulator != null) reconcileCharge(node, accumulator)
                network.updateTopology { node.disconnect() }
                connected
            }
        } else false
        attachedId = -1L
        if (wasConnected) playDockSound(HorizonsSounds.BATTERY_DISCONNECTED.get())
        setChanged()
    }
    private fun playDockSound(sound: SoundEvent) {
        val level = level as? ServerLevel ?: return
        val position = trayToWorld().transformPosition(Vector3d(0.5, 0.125, 0.5))
        level.playSound(null, position.x, position.y, position.z, sound, SoundSource.BLOCKS, 1f, 1f)
    }
    private fun releaseJoint() {
        ++generation
        pending = false
        val level = level as? ServerLevel ?: return
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        jointId?.let { if (gtpa.getJointById(it) != null) gtpa.removeJoint(it) }
        jointId = null
    }
    override fun setRemoved() { suspendAttachment(); super.setRemoved() }
    override fun onChunkUnloaded() { suspendAttachment(); super.onChunkUnloaded() }
    private fun suspendAttachment() {
        // The accumulator owns the native joint while the BE is absent; unloading is not unplugging.
        ++generation
        pending = false
        jointId = null
        val level = level as? ServerLevel ?: return
        RigBodyForces.cancelSeating(level.dimensionId,attachedId)
        val node = powerNode as? BatterySocketNode ?: return
        val network = PowerNetworks.get(level)
        network.withState { network.updateTopology { node.battery?.let { node.retain(node.batteryId, it) } } }
    }
    override fun saveAdditional(tag: CompoundTag) { super.saveAdditional(tag); tag.putLong("Accumulator", attachedId) }
    override fun load(tag: CompoundTag) { super.load(tag); attachedId = if (tag.contains("Accumulator")) tag.getLong("Accumulator") else -1L }
}
