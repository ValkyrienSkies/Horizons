package org.valkyrienskies.horizons.impl.devices.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.horizons.api.devices.components.ISocket;

import java.util.List;

public class StandardSocket implements ISocket {
    @Override
    public BlockPos getBlockPos() {
        return null;
    }

    @Override
    public BlockEntity getBlockEntity() {
        return null;
    }

    @Override
    public Vector3dc getPos() {
        return null;
    }

    @Override
    public Vector3dc getNormal() {
        return null;
    }

    @Override
    public AABBdc getBounds() {
        return null;
    }

    @Override
    public List<ISocket> getConnectedSockets() {
        return List.of();
    }

    @Override
    public boolean isConnectedTo(ISocket other) {
        return false;
    }

    @Override
    public boolean connect(ISocket other) {
        return false;
    }

    @Override
    public boolean disconnect(ISocket other) {
        return false;
    }
}
