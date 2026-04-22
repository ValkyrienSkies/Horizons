package org.valkyrienskies.horizons.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.horizons.api.foundation.mixin.GrabbedObjectTarget;
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck;
import org.valkyrienskies.horizons.api.foundation.networking.server_to_client.ClientboundObjectGrabPacket;
import org.valkyrienskies.horizons.content.HorizonsNetworking;
import org.valkyrienskies.mod.api.EntityPhysicsListener;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.util.GameToPhysicsAdapter;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(Player.class)
public abstract class MixinPlayer extends LivingEntity implements PlayerGrabbingMixinDuck {
    @Unique
    private static final double HORIZONS_GRAB_POS_KP = 48.0;
    @Unique
    private static final double HORIZONS_GRAB_POS_KD = 12.0;
    @Unique
    private static final double HORIZONS_GRAB_MAX_ACCEL = 220.0;
    @Unique
    private static final double HORIZONS_GRAB_GRAVITY_COMPENSATION = 12.0;
    @Unique
    private static final double HORIZONS_GRAB_ROT_KP = 32.0;
    @Unique
    private static final double HORIZONS_GRAB_ROT_KD = 10.0;
    @Unique
    private static final double HORIZONS_GRAB_MAX_ANGULAR_ACCEL = 90.0;
    @Unique
    long horizons$grabbedObjectId = -1L;
    @Unique
    GrabbedObjectTarget horizons$grabbedObjectTarget = new GrabbedObjectTarget();
    @Unique
    boolean horizons$grabMode = true;

    protected MixinPlayer(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public boolean inGrabMode() {
        return horizons$grabMode;
    }

    @Override
    public void toggleGrabMode() {
        horizons$grabMode = !horizons$grabMode;
    }

    @Override
    public boolean tryGrab(long id) {
        Level level = this.level();
        if (id == -1) {
            this.horizons$grabbedObjectId = -1L;
            this.horizons$grabbedObjectTarget = new GrabbedObjectTarget();
            if (!level.isClientSide) {
                HorizonsNetworking.sendToClient(new ClientboundObjectGrabPacket(-1L), (ServerPlayer) (Object) this);
            }
        }
        if (horizons$grabbedObjectId > -1L) {
            horizons$grabbedObjectId = -1L;
            this.horizons$grabbedObjectTarget = new GrabbedObjectTarget();
            return true;
        } else {
            horizons$grabbedObjectId = id;
            return true;
        }
    }

    @Override
    public boolean tryThrow(long id) {
        if (id != horizons$grabbedObjectId) {
            return false;
        }
        if (this.level().isClientSide) {
            return false;
        }
        ServerLevel level = (ServerLevel) this.level();
        LoadedServerShip object = ValkyrienSkies.getShipWorld(level.getServer()).getLoadedShips().getById(horizons$grabbedObjectId);
        this.horizons$grabbedObjectId = -1L;
        this.horizons$grabbedObjectTarget = new GrabbedObjectTarget();
        if (object != null) {
            GameToPhysicsAdapter gtpa = ValkyrienSkiesMod.getOrCreateGTPA(ValkyrienSkies.getDimensionId(level));
            Vector3dc force = VectorConversionsMCKt.toJOML(this.getViewVector(0f).normalize().scale(500.0 * object.getInertiaData().getMass()));
            gtpa.applyWorldForce(object.getId(), force, null);
        }
        return true;
    }

    @Unique
    private Vector3dc getBaseTargetPos() {
        return VectorConversionsMCKt.toJOML(this.getEyePosition().add(this.getViewVector(0f).normalize().scale(this.horizons$grabbedObjectTarget.distance)));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void postTick(CallbackInfo ci) {
        if (horizons$grabbedObjectId != -1L) {
            if (this.level().isClientSide) {
                return;
            }
            ServerLevel level = (ServerLevel) this.level();
            LoadedServerShip object = ValkyrienSkies.getShipWorld(level.getServer()).getLoadedShips().getById(horizons$grabbedObjectId);

            if (object != null) {
                GrabbedObjectTarget target = getGrabbedObjectTarget();
                Vector3dc targetPos = getBaseTargetPos();
                if (target.position != null) {
                    targetPos = targetPos.add(target.position, new Vector3d());
                }
                Quaterniondc targetRot = object.getKinematics().getRotation();
                if (target.rotation != null) {
                    targetRot = target.rotation;
                }

                GameToPhysicsAdapter gtpa = ValkyrienSkiesMod.getOrCreateGTPA(ValkyrienSkies.getDimensionId(level));
                Vector3dc force = horizons$computeGrabForce(object, targetPos);
                if (force.lengthSquared() > 1.0e-6) {
                    gtpa.applyWorldForce(object.getId(), force, null);
                }
                Vector3dc torque = horizons$computeGrabTorque(object, targetRot);
                if (torque.lengthSquared() > 1.0e-6) {
                    gtpa.applyWorldTorque(object.getId(), torque);
                }
            } else {
                tryGrab(-1);
            }
        }
    }

    @Override
    public long getGrabbedObjectId() {
        return horizons$grabbedObjectId;
    }

    @Override
    public void setGrabbedObjectId(long id) {
        horizons$grabbedObjectId = id;
    }

    @Override
    public GrabbedObjectTarget getGrabbedObjectTarget() {
        return horizons$grabbedObjectTarget;
    }

    @Override
    public void setGrabbedObjectTarget(GrabbedObjectTarget target) {
        this.horizons$grabbedObjectTarget = target;
    }

    @Override
    public void setGrabbedObjectTarget(double distance, @Nullable Vector3dc position, @Nullable Quaterniondc rotation) {
        this.horizons$grabbedObjectTarget.distance = (distance >= 0.0) ? distance : horizons$grabbedObjectTarget.distance;
        this.horizons$grabbedObjectTarget.position = (position != null) ? position : horizons$grabbedObjectTarget.position;
        this.horizons$grabbedObjectTarget.rotation = (rotation != null) ? rotation : horizons$grabbedObjectTarget.rotation;
    }

    @Unique
    private Vector3dc horizons$computeGrabForce(LoadedServerShip object, Vector3dc targetPos) {
        Vector3d positionError = targetPos.sub(object.getTransform().getPositionInWorld(), new Vector3d());
        Vector3d desiredAcceleration = positionError.mul(HORIZONS_GRAB_POS_KP, new Vector3d())
            .sub(object.getVelocity().mul(HORIZONS_GRAB_POS_KD, new Vector3d()));
        horizons$clampMagnitude(desiredAcceleration, HORIZONS_GRAB_MAX_ACCEL);
        desiredAcceleration.add(0.0, HORIZONS_GRAB_GRAVITY_COMPENSATION, 0.0);
        return desiredAcceleration.mul(object.getInertiaData().getMass());
    }

    @Unique
    private Vector3dc horizons$computeGrabTorque(LoadedServerShip object, Quaterniondc targetRot) {
        Quaterniond rotationError = targetRot.mul(object.getTransform().getShipToWorldRotation().conjugate(new Quaterniond()), new Quaterniond())
            .normalize();
        if (rotationError.w < 0.0) {
            rotationError.scale(-1.0);
        }

        double sinHalfAngle = Math.sqrt(rotationError.x * rotationError.x + rotationError.y * rotationError.y + rotationError.z * rotationError.z);
        if (sinHalfAngle < 1.0e-6) {
            return object.getAngularVelocity().mul(-HORIZONS_GRAB_ROT_KD * horizons$getAverageInertia(object), new Vector3d());
        }

        double angle = 2.0 * Math.atan2(sinHalfAngle, rotationError.w);
        Vector3d axis = new Vector3d(rotationError.x, rotationError.y, rotationError.z).div(sinHalfAngle);
        Vector3d desiredAngularAcceleration = axis.mul(angle * HORIZONS_GRAB_ROT_KP)
            .sub(object.getAngularVelocity().mul(HORIZONS_GRAB_ROT_KD, new Vector3d()));
        horizons$clampMagnitude(desiredAngularAcceleration, HORIZONS_GRAB_MAX_ANGULAR_ACCEL);
        return desiredAngularAcceleration.mul(horizons$getAverageInertia(object));
    }

    @Unique
    private double horizons$getAverageInertia(LoadedServerShip object) {
        return (
            object.getInertiaData().getInertiaTensor().m00() +
            object.getInertiaData().getInertiaTensor().m11() +
            object.getInertiaData().getInertiaTensor().m22()
        ) / 3.0;
    }

    @Unique
    private static void horizons$clampMagnitude(Vector3d vector, double maxMagnitude) {
        double lengthSquared = vector.lengthSquared();
        double maxSquared = maxMagnitude * maxMagnitude;
        if (lengthSquared > maxSquared && lengthSquared > 1.0e-9) {
            vector.mul(maxMagnitude / Math.sqrt(lengthSquared));
        }
    }

//    @Override
//    public @NotNull String getDimension() {
//        return ValkyrienSkies.getDimensionId(this.level());
//    }
//
//    @Override
//    public void setDimension(@NotNull String s) {
//    }
}
