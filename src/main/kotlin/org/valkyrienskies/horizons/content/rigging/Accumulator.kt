package org.valkyrienskies.horizons.content.rigging

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.horizons.api.rigging.*
import org.valkyrienskies.horizons.content.blockentities.AccumulatorSocketBlockEntity
import org.valkyrienskies.horizons.potato_battery.impl.nodes.FiniteBatteryNode
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId

class Accumulator(level: Level, id: Long, tag: CompoundTag) : RigBody(level, id, TYPE) {
    var cell = FiniteBatteryNode(12.0, 10.0, 360_000.0,
        if (tag.contains("Charge")) tag.getDouble("Charge").takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0 else 1.0)
    var dockPos: BlockPos? = if (tag.contains("Dock")) BlockPos.of(tag.getLong("Dock")) else null
    var blockedDock: BlockPos? = if (tag.contains("BlockedDock")) BlockPos.of(tag.getLong("BlockedDock")) else null
    // Runtime ownership outlives the dock BE when its chunk unloads. Recreated after a server restart.
    var jointId: Int? = null
    override fun saveData() = CompoundTag().apply {
        putDouble("Charge", cell.stateOfCharge)
        dockPos?.let { putLong("Dock", it.asLong()) }
        blockedDock?.let { putLong("BlockedDock", it.asLong()) }
    }
    override fun onGrab(player: ServerPlayer): Boolean {
        dockPos?.let { pos ->
            if (level.hasChunkAt(pos)) (level.getBlockEntity(pos) as? AccumulatorSocketBlockEntity)?.disconnect()
            blockedDock = pos
        }
        releaseJoint()
        dockPos = null
        return true
    }
    override fun tick() {
        jointId?.let { id ->
            if (ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).getJointById(id) == null) {
                jointId = null
                dockPos?.let { pos ->
                    if (level.hasChunkAt(pos)) (level.getBlockEntity(pos) as? AccumulatorSocketBlockEntity)?.disconnect()
                    blockedDock = pos
                }
                dockPos = null
            }
        }
        dockPos?.let { pos ->
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) !is AccumulatorSocketBlockEntity) {
                releaseJoint()
                blockedDock = pos
                dockPos = null
            }
        }
    }
    fun releaseJoint() {
        org.valkyrienskies.horizons.impl.rigging.RigBodyForces.cancelSeating(level.dimensionId,bodyId)
        jointId?.let { ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(it) }
        jointId = null
    }
    override fun onRemoved() {
        dockPos?.let { if (level.hasChunkAt(it)) (level.getBlockEntity(it) as? AccumulatorSocketBlockEntity)?.disconnect() }
        releaseJoint()
    }
    companion object {
        @JvmField val TYPE: RigBodyType<Accumulator> = RigBodies.register(RigBodyType(
            ResourceLocation("horizons", "accumulator"), ResourceLocation("horizons", "block/vs_battery"),
            13.5 / 16.0, 500.0, Vector3d(0.5, 0.4375, 0.5), ::Accumulator))
        fun register() { /* Initializes the type before SavedData or model registration. */ }
    }
}
