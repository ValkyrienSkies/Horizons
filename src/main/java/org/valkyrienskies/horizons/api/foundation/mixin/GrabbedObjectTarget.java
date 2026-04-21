package org.valkyrienskies.horizons.api.foundation.mixin;

import kotlin.jvm.Volatile;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import javax.annotation.Nullable;

public class GrabbedObjectTarget {
    @Nullable
    public Vector3dc position;
    @Nullable
    public Quaterniondc rotation;

    public GrabbedObjectTarget() {
        position = null;
        rotation = null;
    }

    public GrabbedObjectTarget(Vector3dc position) {
        this.position = position;
        this.rotation = null;
    }

    public GrabbedObjectTarget(Quaterniondc rotation) {
        this.position = null;
        this.rotation = rotation;
    }

    public GrabbedObjectTarget(Vector3dc position, Quaterniondc rotation) {
        this.position = position;
        this.rotation = rotation;
    }
}
