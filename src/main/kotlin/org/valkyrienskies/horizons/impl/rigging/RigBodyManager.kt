package org.valkyrienskies.horizons.impl.rigging

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.IEventBus
import org.joml.*
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.horizons.api.rigging.*
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.content.rigging.Accumulator
import org.valkyrienskies.horizons.content.ship_grabbing.PlayerGrabbingMixinDuck
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.vsCore
import java.util.concurrent.ConcurrentHashMap

/** VS persists transforms/colliders; this SavedData owns the accompanying addon data and stable body IDs. */
object RigBodyManager {
    private val worlds = ConcurrentHashMap<Level, RigData>()
    private fun data(level: Level): RigData = worlds.computeIfAbsent(level) {
        if (level is ServerLevel) level.dataStorage.computeIfAbsent({ tag -> RigData(level, tag) }, { RigData(level) }, "horizons_rig_bodies")
        else RigData(level)
    }
    @JvmStatic fun get(level: Level, id: Long): RigBody? = data(level).bodies[id]
    @JvmStatic fun getAll(level: Level): Collection<RigBody> = data(level).bodies.values.toList()
    @JvmStatic fun getVsBody(level: Level, id: Long) = level.shipWorld?.allBodies?.getById(id)
    @JvmStatic fun isGrabbed(level: Level, id: Long) = level.players().any { (it as? PlayerGrabbingMixinDuck)?.getGrabbedObjectId() == id }
    @JvmStatic fun remove(level: ServerLevel, id: Long) {
        val rig = get(level, id) ?: return
        level.players().forEach { player ->
            val grabber = player as PlayerGrabbingMixinDuck
            if (grabber.getGrabbedObjectId() == id) grabber.tryGrab(-1L)
        }
        rig.onRemoved()
        vsApi.getServerShipWorld(level.server)?.let { world -> world.allBodies.getById(id)?.let(world::deleteBody) }
        data(level).bodies.remove(id)
        sync(level)
    }

    @JvmStatic fun spawn(level: ServerLevel, type: RigBodyType<*>, position: Vector3dc, rotation: Quaterniondc, tag: CompoundTag): RigBody {
        require(position.isFinite && rotation.isFinite && rotation.lengthSquared() > 1e-9) { "Invalid rig pose" }
        val inertia = type.mass * type.cubeSize * type.cubeSize / 6.0
        val body = requireNotNull(vsApi.getServerShipWorld(level.server)).createBody(VsBodyCreateData(
            dimensionId = level.dimensionId,
            inertiaData = vsCore.newShipInertiaData(Vector3d(), type.mass, Matrix3d().identity().scale(inertia)),
            kinematics = vsCore.newBodyKinematics(Vector3d(), Vector3d(), Vector3d(position), Quaterniond(rotation).normalize(), Vector3d(1.0), Vector3d()),
            collisionShape = vsCore.newBoxBodyShape(Vector3d(type.cubeSize)),
            staticFrictionCoefficient = 0.6, dynamicFrictionCoefficient = 0.5, restitutionCoefficient = 0.0
        ))
        return type.create(level, body.id, tag.copy()).also { data(level).bodies[body.id] = it; sync(level) }
    }

    /** Exact oriented cube intersection, shared by client targeting and server reach validation. */
    fun raycast(player: Player, reach: Double = 10.0): RigBody? {
        val from = player.eyePosition
        val to = from.add(player.lookAngle.scale(reach))
        var closest = reach * reach
        var selected: RigBody? = null
        for (rig in getAll(player.level())) {
            val body = rig.body ?: continue
            val matrix = body.kinematics.transform.toModel
            val a = matrix.transformPosition(Vector3d(from.x, from.y, from.z))
            val b = matrix.transformPosition(Vector3d(to.x, to.y, to.z))
            val r = rig.type.cubeSize / 2
            val hit = AABB(-r, -r, -r, r, r, r).clip(Vec3(a.x, a.y, a.z), Vec3(b.x, b.y, b.z)).orElse(null) ?: continue
            val worldHit = body.kinematics.transform.toWorld.transformPosition(Vector3d(hit.x, hit.y, hit.z))
            val distance = from.distanceToSqr(worldHit.x, worldHit.y, worldHit.z)
            if (distance < closest) { closest = distance; selected = rig }
        }
        // Blocks (including ship blocks through VS's clip hook) occlude the rig.
        val block = player.pick(reach, 0f, false)
        return selected.takeIf { block.type == net.minecraft.world.phys.HitResult.Type.MISS || from.distanceToSqr(block.location) + 0.01 >= closest }
    }

    fun sync(level: ServerLevel) {
        val packet = RigBodyPacket(level.dimension().location(), data(level).save(CompoundTag()))
        level.players().forEach { HorizonsNetworking.sendToClient(packet, it) }
    }
    fun receive(level: Level, tag: CompoundTag) { worlds[level] = RigData(level, tag) }
    fun register(bus: IEventBus) {
        Accumulator.register()
        org.valkyrienskies.horizons.content.rigging.AccumulatorSocketPhysics.register()
        bus.addListener { event: TickEvent.LevelTickEvent ->
            val level = event.level
            if (event.phase == TickEvent.Phase.END && level is ServerLevel) {
                val loaded = vsApi.getServerShipWorld(level.server)?.loadedBodies
                getAll(level).forEach { if (loaded?.getById(it.bodyId) != null) it.tick() }
                if (level.gameTime % 20L == 0L) sync(level)
            }
        }
        bus.addListener { event: LevelEvent.Unload ->
            val level = event.level as? Level ?: return@addListener
            worlds.remove(level)
            RigBodyForces.clear(level.dimensionId)
        }
        vsApi.physTickEvent.on { event -> RigBodyForces.tick(event.world,event.delta) }
    }

    private class RigData(val level: Level, tag: CompoundTag? = null) : SavedData() {
        val bodies = linkedMapOf<Long, RigBody>()
        // Preserve unknown addon records so temporarily missing mods do not destroy their data.
        val unknown = ListTag()
        init {
            tag?.getList("Bodies", 10)?.forEach {
                val record = it as CompoundTag
                val type = ResourceLocation.tryParse(record.getString("Type"))?.let(RigBodies::getType)
                if (type == null) unknown.add(record.copy())
                else bodies[record.getLong("Id")] = type.create(level, record.getLong("Id"), record.getCompound("Data"))
            }
        }
        override fun isDirty() = true // Charge changes on the electrical solver thread.
        override fun save(tag: CompoundTag): CompoundTag {
            val records = unknown.copy()
            bodies.values.forEach { rig -> records.add(CompoundTag().apply {
                putLong("Id", rig.bodyId); putString("Type", rig.type.id.toString()); put("Data", rig.saveData())
            }) }
            tag.put("Bodies", records)
            return tag
        }
    }
}
