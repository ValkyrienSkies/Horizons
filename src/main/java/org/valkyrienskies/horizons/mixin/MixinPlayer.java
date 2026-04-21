package org.valkyrienskies.horizons.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import org.valkyrienskies.mod.api.EntityPhysicsListener;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(Player.class)
public abstract class MixinPlayer extends LivingEntity implements PlayerGrabbingMixinDuck {
    @Unique
    long horizons$grabbedObjectId = -1L;
    @Unique
    GrabbedObjectTarget horizons$grabbedObjectTarget = new GrabbedObjectTarget();

    protected MixinPlayer(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public boolean tryGrab(long id) {
        Level level = this.level();
        if (id == -1) {
            this.horizons$grabbedObjectId = -1L;
            this.horizons$grabbedObjectTarget = new GrabbedObjectTarget();
            if (!level.isClientSide) {

            }
        }
        if (horizons$grabbedObjectId > -1L) {
            //try drop
        } else {


        }
        return false;
    }

    @Unique
    private Vector3dc getBaseTargetPos() {
        return VectorConversionsMCKt.toJOML(this.getEyePosition().add(this.getViewVector(0f)));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void postTick(CallbackInfo ci) {
        if (horizons$grabbedObjectId != -1L) {
            if (this.level().isClientSide) {
                return;
            }
            ServerLevel level = (ServerLevel) this.level();
            LoadedServerShip object = ValkyrienSkies.getShipWorld(level).getLoadedShips().getById(horizons$grabbedObjectId);

            if (object != null) {
                GrabbedObjectTarget target = getGrabbedObjectTarget();
                Vector3dc targetPos = getBaseTargetPos();
                if (target.position) {
                    targetPos = targetPos.add(target.position, new Vector3d());
                }
                Vector3dc targetRot = get
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
    public void setGrabbedObjectTarget(@Nullable Vector3dc position, @Nullable Quaterniondc rotation) {
        this.horizons$grabbedObjectTarget.position = (position != null) ? position : horizons$grabbedObjectTarget.position;
        this.horizons$grabbedObjectTarget.rotation = (rotation != null) ? rotation : horizons$grabbedObjectTarget.rotation;
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
