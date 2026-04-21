package org.valkyrienskies.horizons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.internal.collision.VsiConvexPolygonc;
import org.valkyrienskies.horizons.api.foundation.mixin.PlayerGrabbingMixinDuck;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import javax.annotation.Nullable;
import java.util.List;

@Mixin(EntityShipCollisionUtils.class)
public class MixinEntityShipCollisionUtils {
    @Unique
    @Nullable
    private static Entity horizons$tracked_entity = null;

    @Inject(method = "getShipPolygonsCollidingWithEntity", at = @At("HEAD"), remap = false)
    private void horizons$captureEntity(
        final Entity entity,
        final Vec3 movement,
        final AABB entityBoundingBox,
        final Level world,
        final CallbackInfoReturnable<List<VsiConvexPolygonc>> cir
    ) {
        horizons$tracked_entity = entity;
    }

    @Inject(method = "getShipPolygonsCollidingWithEntity", at = @At("RETURN"), remap = false)
    private void horizons$clearEntity(
        final Entity entity,
        final Vec3 movement,
        final AABB entityBoundingBox,
        final Level world,
        final CallbackInfoReturnable<List<VsiConvexPolygonc>> cir
    ) {
        horizons$tracked_entity = null;
    }

    @WrapOperation(
        method = "getShipPolygonsCollidingWithEntity$lambda$8$lambda$7(Lorg/valkyrienskies/core/api/ships/properties/ShipTransform;Lorg/valkyrienskies/core/api/ships/LoadedShip;Ljava/util/List;DDDDDD)V",
        at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", remap = false),
        remap = false
    )
    private static boolean horizons$skipGrabbedShipCollision(
        final List<VsiConvexPolygonc> collidingPolygons,
        final Object shipPolygon,
        final Operation<Boolean> original,
        final ShipTransform shipTransform,
        final LoadedShip shipObject,
        final List<VsiConvexPolygonc> originalCollidingPolygons,
        final double minX,
        final double minY,
        final double minZ,
        final double maxX,
        final double maxY,
        final double maxZ
    ) {
        final Entity entity = horizons$tracked_entity;
        if (entity instanceof PlayerGrabbingMixinDuck lifter && lifter.getGrabbedObjectId() == shipObject.getId()) {
            return false;
        }

        return original.call(collidingPolygons, shipPolygon);
    }
}
