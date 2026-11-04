package org.valkyrienskies.horizons.content.items.pda

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.Level
import org.valkyrienskies.horizons.api.devices.IDevice
import org.valkyrienskies.horizons.api.devices.components.ISocket

/** Persistent probe references never retain BEs or force their chunks to load. */
data class VoltageProbe(val dimension: String, val position: BlockPos, val nodeId: Long, val port: Int) {
    fun save() = CompoundTag().also {
        it.putString("Dimension", dimension)
        it.putLong("Position", position.asLong())
        it.putLong("NodeId", nodeId)
        it.putInt("Port", port)
    }

    fun resolve(level: Level): ISocket? {
        if (dimension != level.dimension().location().toString() || !level.hasChunkAt(position)) return null
        val be = level.getBlockEntity(position) ?: return null
        val device = be as? IDevice ?: return null
        if (be.isRemoved || device.nodeId != nodeId) return null
        return device.getSocket(port)
    }

    companion object {
        fun of(level: Level, device: IDevice, socket: ISocket) = VoltageProbe(level.dimension().location().toString(), socket.blockPos, device.nodeId, socket.port)
        fun read(tag: CompoundTag?, key: String): VoltageProbe? {
            if (tag == null || !tag.contains(key, Tag.TAG_COMPOUND.toInt())) return null
            val probe = tag.getCompound(key)
            if (!probe.contains("Dimension", Tag.TAG_STRING.toInt()) || !probe.contains("Position", Tag.TAG_LONG.toInt())
                || !probe.contains("NodeId", Tag.TAG_LONG.toInt()) || !probe.contains("Port", Tag.TAG_INT.toInt())) return null
            return VoltageProbe(probe.getString("Dimension"), BlockPos.of(probe.getLong("Position")), probe.getLong("NodeId"), probe.getInt("Port"))
                .takeIf { it.nodeId >= 0 && it.port >= 0 }
        }
    }
}
