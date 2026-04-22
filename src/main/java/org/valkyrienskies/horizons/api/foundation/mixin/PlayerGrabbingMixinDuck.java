package org.valkyrienskies.horizons.api.foundation.mixin;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.world.PhysLevel;

import javax.annotation.Nullable;

public interface PlayerGrabbingMixinDuck {
    boolean tryGrab(long id);
    boolean tryThrow(long id);
    long getGrabbedObjectId();
    void setGrabbedObjectId(long id);

    void physTick(@NotNull PhysLevel physLevel);

    GrabbedObjectTarget getGrabbedObjectTarget();
    void setGrabbedObjectTarget(GrabbedObjectTarget target);
    void setGrabbedObjectTarget(double distance, @Nullable Vector3dc position, @Nullable Quaterniondc rotation);
    boolean inGrabMode();
    void toggleGrabMode();
}
